package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;
import ca.concordia.filesystem.datastructures.FNode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

public class FileSystemManager {

    private final int MAXFILES = 5;
    private final int MAXBLOCKS = 10;
    private static FileSystemManager instance = null;
    private final RandomAccessFile disk;
    private final ReentrantLock globalLock = new ReentrantLock();

    private static final int BLOCK_SIZE = 128; // Example block size

    private static final int FENTRY_SIZE = 15; // 11 filename + 2 bytes size + 2 bytes firstBlock
    private static final int FNODE_SIZE = 4;   // 4 bytes for next pointer (using int)
    
    private final int fileSysMetadataBlocks;     // Number of blocks reserved for metadata
    private final int firstDataBlock;    // First block available for file data

    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks
    private FNode[] blockList; // Array of blocks

    // --- Constructor & File System Initialization --- //
    public FileSystemManager(String filename, int totalSize) throws IOException {
        // Initialize the file system manager with a file
        if (instance == null) {
            // Init disk file
            File diskFile = new File(filename);

            // Delete existing file system to start from new
            if (diskFile.exists()) {
                diskFile.delete();
                System.out.println("Deleted existing filesystem file");
            }

            this.disk = new RandomAccessFile(diskFile, "rw");

            // Set disk size
            this.disk.setLength(totalSize);

            // Init inode
            this.inodeTable = new FEntry[MAXFILES];
            for (int i = 0; i < MAXFILES; i++) {
                this.inodeTable[i] = null; // null is 
            }

            // Initialize block nodes array
            this.blockList = new FNode[MAXBLOCKS];
            for (int i = 0; i < MAXBLOCKS; i++) {
                this.blockList[i] = new FNode(i);
            }

            // Initialize the free block list (bitmap)
            this.freeBlockList = new boolean[MAXBLOCKS];
            for (int i = 0; i < MAXBLOCKS; i++) {
                this.freeBlockList[i] = true; // true = free, false = allocated
            }

            // Calculate metadata size
            int totalMetadataSize = (MAXFILES * FENTRY_SIZE) + (MAXBLOCKS * FNODE_SIZE); // (15*5) + (10*4) = 115 bytes 
            this.fileSysMetadataBlocks = (int) Math.ceil((double) totalMetadataSize / BLOCK_SIZE); // ceiling(115/128) = 1 Block
            this.firstDataBlock = fileSysMetadataBlocks; // Block 1

            writeFileSystemMetadata();

            instance = this;
        } else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
    }

    // --- Methods --- //

    public void createFile(String fileName) throws Exception {
        int indexAvailableNode = -1;

        // Lock critical section - cannot create file concurrently
        globalLock.lock();

        try {
            // Check if fileName already exists
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    if (inodeTable[i].getFilename().equals(fileName)) {
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
    
    public void writeFile(String filename, String[] contents) throws Exception {
        globalLock.lock();

        try {
            // Find the existing file
            int fileIndex = -1;
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null && inodeTable[i].getFilename().equals(filename)) {
                    fileIndex = i;
                    break;
                }
            }

            if (fileIndex == -1) {
                throw new UnsupportedOperationException("File does not exist.");
            }

            // Empty contents of file
            deleteContents(inodeTable[fileIndex].getFirstBlock());

            // Convert String[] to String
            StringBuilder contentBuilder = new StringBuilder();

            // Skip command and file name
            for (int i = 2; i < contents.length; i++) {
                contentBuilder.append(contents[i]);

                // Space between words
                if (i < contents.length - 1) {
                    contentBuilder.append(" ");
                }
            }

            // Convert content to bytes
            byte[] args = contentBuilder.toString().getBytes(StandardCharsets.UTF_8);

            // Calculate # of blocks needed
            int blocksNeeded = (int) Math.ceil((double) args.length / BLOCK_SIZE);

            if (blocksNeeded > MAXBLOCKS) {
                throw new UnsupportedOperationException("Not enough block space to write.");
            }

            // Count number of free blocks available
            int numberFreeBlocks = 0;

            for (int i = firstDataBlock; i < freeBlockList.length; i++) {
                if (freeBlockList[i] == true) {
                    numberFreeBlocks++;
                }
            }

            if (numberFreeBlocks < blocksNeeded) {
                throw new UnsupportedOperationException("Not enough block space to write.");
            }

            // Change freeBlockList to allocate data
            int count = 0;
            for (int i = firstDataBlock; i < freeBlockList.length && count < blocksNeeded; i++) {
                if (freeBlockList[i] == true) {
                    freeBlockList[i] = false; // allocate to file
                    count++;
                }
            }

            // TODO: implement write to disk, update metadata and link nodes

        } finally {
            globalLock.unlock();
        }
    }

    public void deleteFile(String fileName) throws Exception {
        globalLock.lock();

        try {
            // Look for file name and assign null
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    if (inodeTable[i].getFilename().equals(fileName)) {
                        // Delete contents of file
                        deleteContents(inodeTable[i].getFirstBlock());

                        inodeTable[i] = null;

                        System.out.println("Deleted file and contents of: " + fileName);
                        return;
                    }
                }
            }

            throw new UnsupportedOperationException("No content to delete");
        } finally {
            globalLock.unlock();
        }
    }

    public String[] listFiles() {
        // Count number of existing files
        int count = 0;
        for (int i = 0; i < inodeTable.length; i++) {
            if (inodeTable[i] != null) {
                count++;
            }
        }

        String[] list = new String[count];

        // Return list of file names
        if (count == 0) {
            return list;
        } else {
            int listIndex = 0;

            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    list[listIndex] = inodeTable[i].getFilename();
                    listIndex++;
                }
            }
    
            return list;
        }
    }
    
    // --- Functions --- //
    private void writeFileSystemMetadata() throws IOException {
        disk.seek(0);

        // Write FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            if (inodeTable[i] != null) {
                // Write filename in bytes separated
                byte[] nameBytes = new byte[11];
                byte[] fileNameByte = inodeTable[i].getFilename().getBytes(StandardCharsets.UTF_8);
                System.arraycopy(fileNameByte, 0, nameBytes, 0, Math.min(fileNameByte.length, 11));
                disk.write(nameBytes);

                // Write filesize (2 bytes)
                disk.writeShort(inodeTable[i].getFilesize());

                // Write firstBlock (2 bytes)
                disk.writeShort(inodeTable[i].getFirstBlock());
            } else {
                // If empty write zeroes
                disk.write(new byte[FENTRY_SIZE]);
            }
        }

        // Write FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            // Write next pointer (4 bytes)
            disk.writeInt(blockList[i].getNext());
        }
    }
   
    private void deleteContents(int firstBlock) throws IOException {
        if (firstBlock != -1) {
            int currentFNode = firstBlock;
            while (currentFNode != -1) {
                int nextBlock = blockList[currentFNode].getNext();

                // Write zeroes, consider block 0 metadata
                long diskOffset = (long) (firstDataBlock + currentFNode) * BLOCK_SIZE;
                disk.seek(diskOffset);
                byte[] zeros = new byte[BLOCK_SIZE];
                disk.write(zeros);

                // Reset the FNode
                blockList[currentFNode].setNext(-1);
                
                // Mark block as free
                freeBlockList[currentFNode] = true;

                
                currentFNode = nextBlock;
            }
        } else {
            throw new UnsupportedOperationException("No blocks to delete.");
        }
    }
}