package de.anomic.kelondro;

// a collectionIndex is an index to collection (kelondroCollection) objects
// such a collection ist defined by the following parameters
// - chunksize
// - chunkcount
// each of such a collection is stored in a byte[] which may or may not have space for more chunks
// than already exists in such an array. To store these arrays, we reserve entries in kelondroArray
// database files. There will be a set of array files for different sizes of the collection arrays.
// the 1st file has space for <loadfactor> chunks, the 2nd file for <loadfactor> * <loadfactor> chunks,
// the 3rd file for <loadfactor>^^3 chunks, and the n-th file for <loadfactor>^^n chunks.
// if the loadfactor is 4, then we have the following capacities:
// file 0:    4
// file 1:   16
// file 2:   64
// file 3:  256
// file 4: 1024
// file 5: 4096
// file 6:16384
// file 7:65536
// the maximum number of such files is called the partitions number.
// we don't want that these files grow too big, an kelondroOutOfLimitsException is throws if they
// are oversized.
// the collection arrays may be migration to another size during run-time, which means that not only the
// partitions as mentioned above are maintained, but also a set of "shadow-partitions", that represent old
// partitions and where data is read only and slowly migrated to the default partitions.

import java.io.File;
import java.io.IOException;

public class kelondroCollectionIndex {

    private kelondroIndex index;
    private File path;
    private String filenameStub;
    private int loadfactor;
    private int chunksize;
    private int partitions;
    private int maxChunks;
    private kelondroArray[] array;
    private int[] arrayCapacity;
    
    private static File arrayFile(File path, String filenameStub, int loadfactor, int chunksize, int partitionNumber) {
        String lf = Integer.toHexString(loadfactor).toUpperCase();
        while (lf.length() < 2) lf = "0" + lf;
        String cs = Integer.toHexString(chunksize).toUpperCase();
        while (cs.length() < 4) cs = "0" + cs;
        String pn = Integer.toHexString(partitionNumber).toUpperCase();
        while (pn.length() < 2) pn = "0" + pn;
        return new File(path, filenameStub + "." + lf + "." + cs + "." + pn + ".kca"); // kelondro collection array
    }

    private static final long day = 1000 * 60 * 60 * 24;
    
    private static int daysSince2000(long time) {
        return (int) (time / day) - 10957;
    }
    
    public kelondroCollectionIndex(File path, String filenameStub, int keyLength, kelondroOrder indexOrder, long buffersize,
                                   int loadfactor, int chunksize, int partitions) throws IOException {
        this.path = path;
        this.filenameStub = filenameStub;
        this.chunksize = chunksize;
        this.partitions = partitions;
        this.loadfactor = loadfactor;

        // create index file(s)
        int[] columns;
        columns = new int[3];
        columns[0] = keyLength;
        columns[1] = 4; // chunksize (number of bytes in a single chunk, needed for migration option)
        columns[2] = 4; // chunkcount (number of chunks in this collection)
        columns[3] = 4; // index (position in index file)
        columns[4] = 2; // update time in days since 1.1.2000
        index = new kelondroSplittedTree(path, filenameStub, indexOrder, buffersize, 8, columns, 1, 80, true);

        // create array files
        this.array = new kelondroArray[partitions];
        this.arrayCapacity = new int[partitions];
        
        // open array files
        int load = 1;
        
        for (int i = 0; i < partitions; i++) {
            load = load * loadfactor;
            array[i] = openArrayFile(chunksize, i);
            arrayCapacity[i] = load;
        }
        this.maxChunks = load;
    }
    
    private kelondroArray openArrayFile(int genericChunkSize, int partitionNumber) throws IOException {
        File f = arrayFile(path, filenameStub, loadfactor, genericChunkSize, partitionNumber);
        
        if (f.exists()) {
            return new kelondroArray(f);
        } else {
            int load = 1; for (int i = 0; i < partitionNumber; i++) load = load * loadfactor;
            int[] columns = new int[4];
            columns[0] = index.columnSize(0); // add always the key
            columns[1] = 4; // chunkcount (raw format)
            columns[2] = 2; // last time read
            columns[3] = 2; // last time wrote
            columns[4] = 2; // flag string, assigns collection order as currently stored in table
            columns[5] = load * genericChunkSize;
            return new kelondroArray(f, columns, 0, true);
        }
    }
    
    private int arrayIndex(int requestedCapacity) throws kelondroOutOfLimitsException{
        // the requestedCapacity is the number of wanted chunks
        for (int i = 0; i < arrayCapacity.length; i++) {
            if (arrayCapacity[i] >= requestedCapacity) return i;
        }
        throw new kelondroOutOfLimitsException(maxChunks, requestedCapacity);
    }
    
    public void put(byte[] key, kelondroCollection collection) throws IOException, kelondroOutOfLimitsException {
        if (collection.size() > maxChunks) throw new kelondroOutOfLimitsException(maxChunks, collection.size());

        // first find an old entry, if one exists
        byte[][] oldindexrow = index.get(key);
        
        // define the new storage array
        byte[][] newarrayrow = new byte[][]{key,
                                            kelondroNaturalOrder.encodeLong((long) collection.size(), 4),
                                            collection.getOrderingSignature().getBytes(),
                                            collection.toByteArray()};
        if (oldindexrow == null) {
            // the collection is new
            // find appropriate partition for the collection:
            int part = arrayIndex(collection.size());
            
            // write a new entry in this array
            int newRowNumber = array[part].add(newarrayrow);
            // store the new row number in the index
            index.put(new byte[][]{key,
                                   kelondroNaturalOrder.encodeLong(this.chunksize, 4),
                                   kelondroNaturalOrder.encodeLong(collection.size(), 4),
                                   kelondroNaturalOrder.encodeLong((long) newRowNumber, 4),
                                   kelondroNaturalOrder.encodeLong(daysSince2000(System.currentTimeMillis()), 2)
                                  });
        } else {
            // overwrite the old collection
            // read old information
            //int chunksize  = (int) kelondroNaturalOrder.decodeLong(oldindexrow[1]); // needed only for migration
            int chunkcount = (int) kelondroNaturalOrder.decodeLong(oldindexrow[2]);
            int rownumber  = (int) kelondroNaturalOrder.decodeLong(oldindexrow[3]);
            int oldPartitionNumber = arrayIndex(chunkcount);
            int newPartitionNumber = arrayIndex(collection.size());
            
            // see if we need new space or if we can overwrite the old space
            if (oldPartitionNumber == newPartitionNumber) {
                // we don't need a new slot, just write in the old one
                array[oldPartitionNumber].set(rownumber, newarrayrow);
                // update the index entry
                index.put(new byte[][]{key,
                                       kelondroNaturalOrder.encodeLong(this.chunksize, 4),
                                       kelondroNaturalOrder.encodeLong(collection.size(), 4),
                                       kelondroNaturalOrder.encodeLong((long) rownumber, 4),
                                       kelondroNaturalOrder.encodeLong(daysSince2000(System.currentTimeMillis()), 2)
                                      });
            } else {
                // we need a new slot, that means we must first delete the old entry
                array[oldPartitionNumber].remove(rownumber);
                // write a new entry in the other array
                int newRowNumber = array[newPartitionNumber].add(newarrayrow);
                // store the new row number in the index
                index.put(new byte[][]{key,
                                       kelondroNaturalOrder.encodeLong(this.chunksize, 4),
                                       kelondroNaturalOrder.encodeLong(collection.size(), 4),
                                       kelondroNaturalOrder.encodeLong((long) newRowNumber, 4),
                                       kelondroNaturalOrder.encodeLong(daysSince2000(System.currentTimeMillis()), 2)
                                      });
            }
        }
    }
    
    public kelondroCollection get(byte[] key) throws IOException {
        // find an entry, if one exists
        byte[][] indexrow = index.get(key);
        if (indexrow == null) return null;
        // read values
        int chunksize  = (int) kelondroNaturalOrder.decodeLong(indexrow[1]);
        int chunkcount = (int) kelondroNaturalOrder.decodeLong(indexrow[2]);
        int rownumber  = (int) kelondroNaturalOrder.decodeLong(indexrow[3]);
        int partitionnumber = arrayIndex(chunkcount);
        // open array entry
        byte[][] arrayrow = array[partitionnumber].get(rownumber);
        if (arrayrow == null) throw new kelondroException(arrayFile(this.path, this.filenameStub, this.loadfactor, chunksize, partitionnumber).toString(), "array does not contain expected row");
        // read the row and define a collection
        int chunkcountInArray = (int) kelondroNaturalOrder.decodeLong(arrayrow[1]);
        if (chunkcountInArray != chunkcount) throw new kelondroException(arrayFile(this.path, this.filenameStub, this.loadfactor, chunksize, partitionnumber).toString(), "array has different chunkcount than index: index = " + chunkcount + ", array = " + chunkcountInArray);
        return new kelondroCollection(chunksize, chunkcount, new String(arrayrow[2]), arrayrow[3]);
    }
    
    public void remove(byte[] key) throws IOException {
        // find an entry, if one exists
        byte[][] indexrow = index.get(key);
        if (indexrow == null) return;
        // read values
        //int chunksize  = (int) kelondroNaturalOrder.decodeLong(indexrow[1]);
        int chunkcount = (int) kelondroNaturalOrder.decodeLong(indexrow[2]);
        int rownumber  = (int) kelondroNaturalOrder.decodeLong(indexrow[3]);
        int partitionnumber = arrayIndex(chunkcount);
        // remove array entry
        array[partitionnumber].remove(rownumber);
    }
    
    /*
    public Iterator collections(boolean up, boolean rotating) throws IOException {
        // Objects are of type kelondroCollection
    }
    */
    
    public static void main(String[] args) {
        System.out.println(new java.util.Date(10957 * day));
        System.out.println(new java.util.Date(0));
        System.out.println(daysSince2000(System.currentTimeMillis()));
    }
}
