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
        // Initialize the FileSystemManager
        FileSystemManager fsManager = new FileSystemManager(fileSystemName,
                10*128 );
        FileServer.fsManager = fsManager;
        this.port = port;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(12345)) {
            System.out.println("Server started. Listening on port 12345...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Handling client: " + clientSocket);

                // Create a new thread object
                ClientThread clientSock = new ClientThread(clientSocket);

                // Start thread client
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

        // Constructor
        public ClientThread(Socket socket)
        {
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
                        String[] parts = line.split(" ");
                        String command = parts[0].toUpperCase();

                        switch (command) {
                            case "CREATE":
                                try {
                                    fsManager.createFile(parts[1]);
                                    writer.println("SUCCESS: File '" + parts[1] + "' created.");
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: cannot create file.");
                                    writer.flush();
                                }
                                break;
                            
                            case "WRITE":
                                try {
                                    fsManager.writeFile(command, parts);
                                } catch (Exception e) {
                                    // TODO: handle exception
                                }
                                break;
                            
                            case "DELETE":
                                try {
                                    fsManager.deleteFile(parts[1]);
                                    writer.println("SUCCESS: File '" + parts[1] + "' deleted.");
                                    writer.flush();
                                } catch (Exception e) {
                                    writer.println("ERROR: cannot delete file.");
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
                                // Indicate end of list for client side reader
                                writer.println("END OF LIST");
                                writer.flush();
                                break;

                            //TODO: Implement other commands READ, WRITE, DELETE
                            case "QUIT":
                                writer.println("SUCCESS: Disconnecting.");
                                writer.flush();
                                return;
                            default:
                                writer.println("ERROR: Unknown command.");
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
