package ca.concordia.server;
import ca.concordia.filesystem.FileSystemManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class FileServer {

    private static FileSystemManager fsManager;
    private int port;
    
    public FileServer(int port, String fileSystemName, int totalSize) throws IOException{
        FileSystemManager fsManager = new FileSystemManager(fileSystemName, totalSize);
        FileServer.fsManager = fsManager;
        this.port = port;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started. Listening on port " + port + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Handling client: " + clientSocket);

                ClientThread clientSock = new ClientThread(clientSocket);
                new Thread(clientSock).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not start server on port " + port);
        }
    }

    // ClientThread class
    private static class ClientThread implements Runnable {
        private final Socket clientSocket;

        public ClientThread(Socket socket) {
            this.clientSocket = socket;
        }

        public void run() {
            try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
                    
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("Received from client: " + line);
                        String[] parts = line.split(" ", 3); // Max 3 parts for WRITE command
                        String command = parts[0].toUpperCase();

                        switch (command) {
                            case "CREATE":
                                if (parts.length < 2) {
                                    writer.println("ERROR: Missing filename.");
                                    writer.flush();
                                    break;
                                }
                                try {
                                    fsManager.createFile(parts[1]);
                                    writer.println("SUCCESS: File '" + parts[1] + "' created.");
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: " + e.getMessage());
                                    writer.flush();
                                }
                                break;
                            
                            case "WRITE":
                                if (parts.length < 2) {
                                    writer.println("ERROR: Missing filename.");
                                    writer.flush();
                                    break;
                                }
                                try {
                                    // Parse the full command line for content
                                    String[] fullParts = line.split(" ");
                                    if (fullParts.length < 3) {
                                        writer.println("ERROR: No content provided.");
                                        writer.flush();
                                        break;
                                    }
                                    fsManager.writeFile(fullParts[1], fullParts);
                                    writer.println("SUCCESS: File '" + fullParts[1] + "' written.");
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: " + e.getMessage());
                                    writer.flush();
                                }
                                break;
                            
                            case "READ":
                                if (parts.length < 2) {
                                    writer.println("ERROR: Missing filename.");
                                    writer.flush();
                                    break;
                                }
                                try {
                                    String content = fsManager.readFile(parts[1]);
                                    if (content.isEmpty()) {
                                        writer.println("SUCCESS: File '" + parts[1] + "' is empty.");
                                    } else {
                                        writer.println("SUCCESS: " + content);
                                    }
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: " + e.getMessage());
                                    writer.flush();
                                }
                                break;
                            
                            case "DELETE":
                                if (parts.length < 2) {
                                    writer.println("ERROR: Missing filename.");
                                    writer.flush();
                                    break;
                                }
                                try {
                                    fsManager.deleteFile(parts[1]);
                                    writer.println("SUCCESS: File '" + parts[1] + "' deleted.");
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: " + e.getMessage());
                                    writer.flush();
                                }
                                break;

                            case "LIST":
                                String[] list = fsManager.listFiles();
                                if (list.length == 0) {
                                    writer.println("No files exist.");
                                } else {
                                    for (int i = 0; i < list.length; i++) {
                                        writer.println(list[i]);
                                    }
                                }
                                writer.println("END OF LIST");
                                writer.flush();
                                break;

                            case "QUIT":
                                writer.println("SUCCESS: Disconnecting.");
                                writer.flush();
                                return;
                                
                            default:
                                writer.println("ERROR: Unknown command. Available commands: CREATE, WRITE, READ, DELETE, LIST, QUIT");
                                writer.flush();
                                break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        clientSocket.close();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
        }
    }
}