package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

public class FileSystemManager {

    private final int MAXFILES = 5;
    private final int MAXBLOCKS = 10;
    private static FileSystemManager instance = null;
    private final RandomAccessFile disk;
    private final ReentrantLock globalLock = new ReentrantLock();

    private static final int BLOCK_SIZE = 128; // Example block size

    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks

    public FileSystemManager(String filename, int totalSize) throws IOException {
        // Initialize the file system manager with a file
        if(instance == null) {
            // Init disk file
            File diskFile = new File(filename);
            this.disk = new RandomAccessFile(diskFile, "rw");

            // Set disk size
            this.disk.setLength(totalSize);

            // Init inode table - size -> MAXFILES 
            this.inodeTable = new FEntry[MAXFILES];
            for (int i = 0; i < MAXFILES; i++) {
                this.inodeTable[i] = null; // null is 
            }
            
            // Initialize the free block list (bitmap)
            this.freeBlockList = new boolean[MAXBLOCKS];
            for (int i = 0; i < MAXBLOCKS; i++) {
                this.freeBlockList[i] = true; // true = free, false = allocated
            }

            instance = this;
        } else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
    }

    public void createFile(String fileName) throws Exception {
        int indexAvailableNode = -1;

        // Lock critical section - cannot create file concurrently
        globalLock.lock();

        try {
            // Check if fileName already exists
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    if (inodeTable[i].getFilename() == fileName) {
                        throw new UnsupportedOperationException("File already exists.");
                    }
                }
            }

            // Check for available FEntry
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] == null) {
                    indexAvailableNode = i;
                    break;
                }
            }
            
            if (indexAvailableNode != -1) {
                // Create FEntry at available node
                inodeTable[indexAvailableNode] = new FEntry(fileName, (short) 0, (short) -1);
            } else {
                throw new UnsupportedOperationException("Not enough space to create file");
            }

            System.out.println("Created file: " + fileName);
        } finally {
            globalLock.unlock();
        }
    }


    // TODO: Add readFile, writeFile and other required methods,
}
