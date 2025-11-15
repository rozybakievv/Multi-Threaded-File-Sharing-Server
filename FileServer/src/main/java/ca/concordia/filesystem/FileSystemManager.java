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

    private static final int BLOCK_SIZE = 128;
    private static final int FENTRY_SIZE = 15;
    private static final int FNODE_SIZE = 4;
    
    private final int fileSysMetadataBlocks;
    private final int firstDataBlock;

    private FEntry[] inodeTable;
    private boolean[] freeBlockList;
    private FNode[] blockList;

    // --- Constructor & File System Initialization --- //
    public FileSystemManager(String filename, int totalSize) throws IOException {
        if (instance == null) {
            File diskFile = new File(filename);
            boolean existingFS = diskFile.exists();

            this.disk = new RandomAccessFile(diskFile, "rw");

            // Calculate metadata size
            int totalMetadataSize = (MAXFILES * FENTRY_SIZE) + (MAXBLOCKS * FNODE_SIZE);
            this.fileSysMetadataBlocks = (int) Math.ceil((double) totalMetadataSize / BLOCK_SIZE);
            this.firstDataBlock = fileSysMetadataBlocks;

            if (existingFS) {
                System.out.println("Loading existing filesystem...");
                loadFileSystemMetadata();
            } else {
                System.out.println("Creating new filesystem...");
                this.disk.setLength(totalSize);

                this.inodeTable = new FEntry[MAXFILES];
                for (int i = 0; i < MAXFILES; i++) {
                    this.inodeTable[i] = null;
                }

                this.blockList = new FNode[MAXBLOCKS];
                for (int i = 0; i < MAXBLOCKS; i++) {
                    this.blockList[i] = new FNode(i);
                }

                this.freeBlockList = new boolean[MAXBLOCKS];
                for (int i = 0; i < MAXBLOCKS; i++) {
                    this.freeBlockList[i] = true;
                }

                writeFileSystemMetadata();
            }

            instance = this;
        } else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
    }

    // --- Methods --- //

    public void createFile(String fileName) throws Exception {
        int indexAvailableNode = -1;

        globalLock.lock();

        try {
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    if (inodeTable[i].getFilename().equals(fileName)) {
                        throw new UnsupportedOperationException("File already exists.");
                    }
                }
            }

            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] == null) {
                    indexAvailableNode = i;
                    break;
                }
            }

            if (indexAvailableNode != -1) {
                inodeTable[indexAvailableNode] = new FEntry(fileName, (short) 0, (short) -1);
                writeFileSystemMetadata();
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

            // Delete existing contents
            if (inodeTable[fileIndex].getFirstBlock() != -1) {
                deleteContents(inodeTable[fileIndex].getFirstBlock());
            }

            // Build content string (skip command and filename)
            StringBuilder contentBuilder = new StringBuilder();
            for (int i = 2; i < contents.length; i++) {
                contentBuilder.append(contents[i]);
                if (i < contents.length - 1) {
                    contentBuilder.append(" ");
                }
            }

            byte[] data = contentBuilder.toString().getBytes(StandardCharsets.UTF_8);
            int blocksNeeded = (int) Math.ceil((double) data.length / BLOCK_SIZE);

            if (blocksNeeded > MAXBLOCKS - firstDataBlock) {
                throw new UnsupportedOperationException("Not enough block space to write.");
            }

            // Count free blocks
            int numberFreeBlocks = 0;
            for (int i = firstDataBlock; i < freeBlockList.length; i++) {
                if (freeBlockList[i]) {
                    numberFreeBlocks++;
                }
            }

            if (numberFreeBlocks < blocksNeeded) {
                throw new UnsupportedOperationException("Not enough block space to write.");
            }

            // Allocate blocks and create linked list
            int[] allocatedBlocks = new int[blocksNeeded];
            int count = 0;
            for (int i = firstDataBlock; i < freeBlockList.length && count < blocksNeeded; i++) {
                if (freeBlockList[i]) {
                    allocatedBlocks[count] = i;
                    freeBlockList[i] = false;
                    count++;
                }
            }

            // Link the FNodes
            for (int i = 0; i < allocatedBlocks.length - 1; i++) {
                blockList[allocatedBlocks[i]].setNext(allocatedBlocks[i + 1]);
            }
            blockList[allocatedBlocks[allocatedBlocks.length - 1]].setNext(-1);

            // Write data to disk blocks
            for (int i = 0; i < allocatedBlocks.length; i++) {
                int blockIdx = allocatedBlocks[i];
                long diskOffset = (long) blockIdx * BLOCK_SIZE;
                disk.seek(diskOffset);

                int startPos = i * BLOCK_SIZE;
                int endPos = Math.min(startPos + BLOCK_SIZE, data.length);
                int bytesToWrite = endPos - startPos;

                byte[] blockData = new byte[BLOCK_SIZE];
                System.arraycopy(data, startPos, blockData, 0, bytesToWrite);
                disk.write(blockData);
            }

            // Update FEntry
            inodeTable[fileIndex].setFilesize((short) data.length);
            inodeTable[fileIndex].setFirstBlock((short) allocatedBlocks[0]);

            // Write updated metadata
            writeFileSystemMetadata();

            System.out.println("Wrote " + data.length + " bytes to file: " + filename);
        } finally {
            globalLock.unlock();
        }
    }

    // No lock - allow threads to read conccurently
    public String readFile(String filename) throws Exception {
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

        FEntry entry = inodeTable[fileIndex];
        if (entry.getFirstBlock() == -1 || entry.getFilesize() == 0) {
            return "";
        }

        // Read data from linked blocks
        byte[] fileData = new byte[entry.getFilesize()];
        int currentBlock = entry.getFirstBlock();
        int bytesRead = 0;

        while (currentBlock != -1 && bytesRead < entry.getFilesize()) {
            long diskOffset = (long) currentBlock * BLOCK_SIZE;
            disk.seek(diskOffset);

            int bytesToRead = Math.min(BLOCK_SIZE, entry.getFilesize() - bytesRead);
            byte[] blockData = new byte[bytesToRead];
            disk.read(blockData);

            System.arraycopy(blockData, 0, fileData, bytesRead, bytesToRead);
            bytesRead += bytesToRead;

            currentBlock = blockList[currentBlock].getNext();
        }

        return new String(fileData, StandardCharsets.UTF_8);
    }

    public void deleteFile(String fileName) throws Exception {
        globalLock.lock();

        try {
            for (int i = 0; i < inodeTable.length; i++) {
                if (inodeTable[i] != null) {
                    if (inodeTable[i].getFilename().equals(fileName)) {
                        if (inodeTable[i].getFirstBlock() != -1) {
                            deleteContents(inodeTable[i].getFirstBlock());
                        }

                        inodeTable[i] = null;
                        writeFileSystemMetadata();

                        System.out.println("Deleted file: " + fileName);
                        return;
                    }
                }
            }

            throw new UnsupportedOperationException("File not found");
        } finally {
            globalLock.unlock();
        }
    }

    // No lock - allow threads to list files conccurently
    public String[] listFiles() {
        // Count number of files
        int count = 0;
        for (int i = 0; i < inodeTable.length; i++) {
            if (inodeTable[i] != null) {
                count++;
            }
        }

        String[] list = new String[count];

        // If more >0 return all file names
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
    
    // --- Private Functions --- //
    
    private void loadFileSystemMetadata() throws IOException {
        disk.seek(0);

        // Read FEntry array
        this.inodeTable = new FEntry[MAXFILES];
        for (int i = 0; i < MAXFILES; i++) {
            byte[] nameBytes = new byte[11];
            disk.read(nameBytes);

            short filesize = disk.readShort();
            short firstBlock = disk.readShort();

            // Check for file names
            String filename = new String(nameBytes, StandardCharsets.UTF_8).trim();
            if (!filename.isEmpty() && filesize >= 0) {
                inodeTable[i] = new FEntry(filename, filesize, firstBlock);
            } else {
                inodeTable[i] = null;
            }
        }

        // Read FNode array
        this.blockList = new FNode[MAXBLOCKS];
        for (int i = 0; i < MAXBLOCKS; i++) {
            int next = disk.readInt();
            blockList[i] = new FNode(i);
            blockList[i].setNext(next);
        }

        // Reset freeblocklist
        this.freeBlockList = new boolean[MAXBLOCKS];
        for (int i = 0; i < MAXBLOCKS; i++) {
            freeBlockList[i] = true;
        }

        // Allocate metadata in freeblocklist
        for (int i = 0; i < firstDataBlock; i++) {
            freeBlockList[i] = false;
        }

        // Update freeblocklist
        for (int i = 0; i < MAXFILES; i++) {
            if (inodeTable[i] != null && inodeTable[i].getFirstBlock() != -1) {
                int currentBlock = inodeTable[i].getFirstBlock();
                while (currentBlock != -1) {
                    freeBlockList[currentBlock] = false;
                    currentBlock = blockList[currentBlock].getNext();
                }
            }
        }

        System.out.println("Filesystem loaded successfully.");
    }
    
    private void writeFileSystemMetadata() throws IOException {
        disk.seek(0);

        // Write FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            if (inodeTable[i] != null) {
                byte[] nameBytes = new byte[11];
                byte[] fileNameByte = inodeTable[i].getFilename().getBytes(StandardCharsets.UTF_8);
                System.arraycopy(fileNameByte, 0, nameBytes, 0, Math.min(fileNameByte.length, 11));
                disk.write(nameBytes);

                disk.writeShort(inodeTable[i].getFilesize());
                disk.writeShort(inodeTable[i].getFirstBlock());
            } else {
                disk.write(new byte[FENTRY_SIZE]);
            }
        }

        // Write FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            disk.writeInt(blockList[i].getNext());
        }
    }
   
    private void deleteContents(int firstBlock) throws IOException {
        if (firstBlock == -1) {
            return;
        }

        int currentFNode = firstBlock;
        while (currentFNode != -1) {
            int nextBlock = blockList[currentFNode].getNext();

            long diskOffset = (long) currentFNode * BLOCK_SIZE;
            disk.seek(diskOffset);
            byte[] zeros = new byte[BLOCK_SIZE];
            disk.write(zeros);

            blockList[currentFNode].setNext(-1);
            freeBlockList[currentFNode] = true;

            currentFNode = nextBlock;
        }
    }
}