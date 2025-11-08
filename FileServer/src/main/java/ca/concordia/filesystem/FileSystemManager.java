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
public void writeFile(String filename, byte[] contents) throws Exception {
        globalLock.lock();
        try {
            int feIdx = findFEntryIndex(filename);
            if (feIdx < 0) {
                throw new IllegalStateException("ERROR: file " + filename + " does not exist");
            }
            if (contents == null) contents = new byte[0];

            // How many blocks are needed
            int needBlocks = (contents.length + BLOCKSIZE - 1) / BLOCKSIZE;

            // Edge case: writing empty content -> free old chain, set empty
            if (needBlocks == 0) {
                short oldHead = fentries[feIdx].getFirstBlock();
                fentries[feIdx].setFirstBlock((short) -1);
                fentries[feIdx].setFilesize((short) 0);
                persistFEntries();
                if (oldHead >= 0) {
                    freeChain(oldHead);
                    persistFNodes();
                }
                return;
            }

            // 1) Check space: count free fnodes (blockIndex < 0)
            int freeCount = 0;
            for (int i = 0; i < MAXBLOCKS; i++) {
                if (fnodes[i].getBlockIndex() < 0) freeCount++;
            }
            if (freeCount < needBlocks) {
                throw new IllegalStateException("ERROR: file too large");
            }

            // 2) Reserve nodes (do NOT touch old chain yet)
            int[] chain = new int[needBlocks];
            int got = 0;
            for (int i = 0; i < MAXBLOCKS && got < needBlocks; i++) {
                if (fnodes[i].getBlockIndex() < 0) {
                    chain[got++] = i;
                    // mark as used in-memory (blockIndex = itself, next = -1)
                    fnodes[i].setBlockIndex(i);
                    fnodes[i].setNext(-1);
                }
            }
            // Link the chain
            for (int i = 0; i + 1 < needBlocks; i++) {
                fnodes[chain[i]].setNext(chain[i + 1]);
            }

            // 3) Write payload to reserved blocks
            int written = 0;
            for (int idx = 0; idx < needBlocks; idx++) {
                int block = chain[idx];
                int len = Math.min(BLOCKSIZE, contents.length - written);
                disk.seek(blockOffset(block));
                disk.write(contents, written, len);
                // pad remainder with zeros (keeps blocks deterministic)
                for (int p = len; p < BLOCKSIZE; p++) disk.writeByte(0);
                written += len;
            }

            // 4) Persist FNode table with new chain
            persistFNodes();

            // 5) Atomically swap file's head to the new chain
            short oldHead = fentries[feIdx].getFirstBlock();
            fentries[feIdx].setFirstBlock((short) chain[0]);
            // filesize stored in short per spec; clamp to short range if ever needed
            fentries[feIdx].setFilesize((short) Math.min(contents.length, Short.MAX_VALUE));
            persistFEntries();

            // 6) Free old chain (zero + mark free)
            if (oldHead >= 0) {
                freeChain(oldHead);
                persistFNodes();
            }
        } finally {
            globalLock.unlock();
        }
    }

    // Collect exactly `filesize` bytes following the FNode chain
    public byte[] readFile(String filename) throws Exception {
        globalLock.lock();
        try {
            int feIdx = findFEntryIndex(filename);
            if (feIdx < 0) {
                throw new IllegalStateException("ERROR: file " + filename + " does not exist");
            }
            FEntry fe = fentries[feIdx];
            int total = fe.getFilesize() & 0xFFFF; // unsigned short → int
            if (total == 0 || fe.getFirstBlock() < 0) {
                return new byte[0];
            }

            byte[] out = new byte[total];
            int filled = 0;
            int node = fe.getFirstBlock();

            while (node >= 0 && filled < total) {
                int toRead = Math.min(BLOCKSIZE, total - filled);
                disk.seek(blockOffset(node));
                disk.readFully(out, filled, toRead);
                filled += toRead;
                node = fnodes[node].getNext();
            }
            return out;
        } finally {
            globalLock.unlock();
        }
    }

    // ---------------- helpers (used by CREATE/READ/WRITE) ----------------

    private int findFEntryIndex(String name) {
        for (int i = 0; i < MAXFILES; i++) {
            if (!fentries[i].isEmpty() && fentries[i].getFilename().equals(name)) return i;
        }
        return -1;
    }

    private int firstEmptyFEntry() {
        for (int i = 0; i < MAXFILES; i++) {
            if (fentries[i].isEmpty()) return i;
        }
        return -1;
    }

    /** Free a whole chain: zero each block, mark node free, unlink. */
    private void freeChain(short head) throws IOException {
        int node = head;
        while (node >= 0) {
            int next = fnodes[node].getNext();
            zeroBlock(node);
            fnodes[node].setBlockIndex(-1);
            fnodes[node].setNext(-1);
            node = next;
        }
    }
}

    // TODO: Add readFile, writeFile and other required methods,

