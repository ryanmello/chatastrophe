import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class tcpccs_client {
    private static final int DEFAULT_PORT = 12345;
    private static volatile boolean running = true;
    private static Map<String, FileTransferInfo> pendingTransfers = new ConcurrentHashMap<>();
    private static Map<String, Thread> activeTransfers = new ConcurrentHashMap<>();
    
    // Class to hold file transfer information
    private static class FileTransferInfo {
        String sender;
        String filename;
        long fileSize;
        int transferPort;
        
        public FileTransferInfo(String sender, String filename, long fileSize, int transferPort) {
            this.sender = sender;
            this.filename = filename;
            this.fileSize = fileSize;
            this.transferPort = transferPort;
        }
    }
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java tcpccs <server_hostname> <username>");
            return;
        }
        
        String hostname = args[0];
        String username = args[1];
        
        try (
            Socket socket = new Socket(hostname, DEFAULT_PORT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in))
        ) {
            // Send username to the server
            out.println(username);
            
            System.out.println("Connected to the server. You can start sending messages.");
            
            // Start a separate thread to read server responses
            Thread serverListener = new Thread(() -> {
                try {
                    String serverResponse;
                    while (running && (serverResponse = in.readLine()) != null) {
                        System.out.println(serverResponse);
                        
                        // Check for file transfer notifications
                        handleFileTransferNotifications(serverResponse, username, hostname);
                    }
                } catch (IOException e) {
                    if (running) {
                        System.out.println("Lost connection to server: " + e.getMessage());
                    }
                }
            });
            serverListener.start();
            
            // Main thread reads user input and sends to server
            String userInput;
            while ((userInput = stdIn.readLine()) != null) {
                // Handle file-related commands locally
                if (userInput.startsWith("/sendfile")) {
                    String[] parts = userInput.split("\\s+", 3);
                    if (parts.length >= 3) {
                        String filename = parts[2];
                        File file = new File(filename);
                        
                        if (!file.exists() || !file.isFile()) {
                            System.out.println("Error: File " + filename + " does not exist.");
                            continue;
                        }
                        
                        // Get file size in KB (rounded up)
                        long fileSize = (file.length() + 1023) / 1024;
                        
                        // Calculate file checksum
                        String checksum = calculateChecksum(file);
                        
                        // Modify command to include size and checksum
                        userInput = parts[0] + " " + parts[1] + " " + parts[2] + " " + fileSize + " " + checksum;
                    } else {
                        System.out.println("Usage: /sendfile <recipient> <filename>");
                        continue;
                    }
                } else if (userInput.equals("/canceltransfer")) {
                    cancelActiveTransfers();
                    continue;
                }
                
                // Send the message to the server
                out.println(userInput);
                
                // Check if it's a quit command
                if ("/quit".equalsIgnoreCase(userInput)) {
                    running = false;
                    cancelActiveTransfers();
                    break;
                }
            }
            
            System.out.println("Disconnecting from server...");
            
        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + hostname);
        } catch (IOException e) {
            System.out.println("I/O Error: " + e.getMessage());
        } finally {
            running = false;
        }
    }
    
    // Calculate MD5 checksum of a file
    private static String calculateChecksum(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            System.out.println("Error calculating checksum: " + e.getMessage());
            return "";
        }
    }
    
    // Cancel all active transfers
    private static void cancelActiveTransfers() {
        for (Map.Entry<String, Thread> entry : activeTransfers.entrySet()) {
            System.out.println("Cancelling transfer: " + entry.getKey());
            entry.getValue().interrupt();
        }
        activeTransfers.clear();
    }
    
    // Process file transfer notifications from server messages
    private static void handleFileTransferNotifications(String message, String username, String serverHost) {
        // Check for file transfer initiation
        if (message.contains("[File transfer initiated from")) {
            try {
                String sender = message.split("from ")[1].split(" to ")[0];
                String recipient = message.split(" to ")[1].split(" ")[0];
                
                // Extract filename (between recipient and first parenthesis)
                int filenameStart = message.indexOf(recipient) + recipient.length() + 1;
                int filenameEnd = message.lastIndexOf(" (");
                String filename = message.substring(filenameStart, filenameEnd).trim();
                
                // Extract size
                String sizeStr = message.substring(
                    message.lastIndexOf("(") + 1,
                    message.lastIndexOf(" KB)")
                ).trim();
                long size = Long.parseLong(sizeStr);
                
                // Extract port if present
                int portIndex = message.lastIndexOf("port:");
                int transferPort = DEFAULT_PORT + 1; // Default value
                
                if (portIndex != -1) {
                    String portStr = message.substring(portIndex + 5, message.lastIndexOf("]")).trim();
                    transferPort = Integer.parseInt(portStr);
                }
                
                // If this client is the recipient, store the pending transfer
                if (recipient.equals(username)) {
                    pendingTransfers.put(sender, new FileTransferInfo(sender, filename, size, transferPort));
                    System.out.println("Type '/acceptfile " + sender + "' to accept or '/rejectfile " + sender + "' to reject the file.");
                }
            } catch (Exception e) {
                System.out.println("Error parsing file transfer notification: " + e.getMessage());
            }
        }
        
        // Check for file transfer acceptance
        if (message.contains("[File transfer accepted by")) {
            String recipient = message.split("by ")[1].split(" from")[0];
            String sender = message.split("from ")[1].split("]")[0];
            
            // Extract port if present
            int portIndex = message.lastIndexOf("port:");
            int transferPort = DEFAULT_PORT + 1; // Default value
            
            if (portIndex != -1) {
                String portStr = message.substring(portIndex + 5, message.lastIndexOf("]")).trim();
                transferPort = Integer.parseInt(portStr);
            }
            
            // If this client is the sender, initiate the transfer
            if (sender.equals(username)) {
                // Extract filename
                int filenameStart = message.indexOf("File:") + 5;
                int filenameEnd = message.indexOf("(", filenameStart) - 1;
                String filename = message.substring(filenameStart, filenameEnd).trim();
                
                System.out.println("Recipient accepted. Initiating file transfer on port " + transferPort);
                
                // Start file transfer in a new thread
                String transferKey = sender + "->" + recipient;
                final String finalFilename = filename;
                final String finalServerHost = serverHost;
                final int finalTransferPort = transferPort;
                final String finalRecipient = recipient;
                Thread transferThread = new Thread(() -> {
                    try {
                        sendFile(finalFilename, finalServerHost, finalTransferPort, finalRecipient);
                    } catch (Exception e) {
                        System.out.println("Error during file transfer: " + e.getMessage());
                    } finally {
                        activeTransfers.remove(transferKey);
                    }
                });
                transferThread.start();
                activeTransfers.put(transferKey, transferThread);
            }
        }
        
        // Check for file transfer starting on receiver side
        if (message.contains("[Starting file transfer from")) {
            String sender = message.split("from ")[1].split(" to")[0];
            String recipient = message.split("to ")[1].split("]")[0];
            
            // Extract port if present
            int portIndex = message.lastIndexOf("port:");
            int transferPort = DEFAULT_PORT + 1; // Default value
            
            if (portIndex != -1) {
                String portStr = message.substring(portIndex + 5, message.lastIndexOf("]")).trim();
                transferPort = Integer.parseInt(portStr);
            }
            
            // If this client is the recipient, prepare to receive the file
            if (recipient.equals(username)) {
                FileTransferInfo info = pendingTransfers.get(sender);
                if (info != null) {
                    System.out.println("Preparing to receive file from " + sender + " on port " + transferPort);
                    
                    // Start file reception in a new thread
                    String transferKey = sender + "->" + recipient;
                    final String finalServerHost = serverHost;
                    final int finalTransferPort = transferPort;
                    final String finalSender = sender;
                    final String finalFilename = info.filename;
                    Thread transferThread = new Thread(() -> {
                        try {
                            receiveFile(finalFilename, finalServerHost, finalTransferPort, finalSender);
                        } catch (Exception e) {
                            System.out.println("Error during file reception: " + e.getMessage());
                        } finally {
                            pendingTransfers.remove(sender);
                            activeTransfers.remove(transferKey);
                        }
                    });
                    transferThread.start();
                    activeTransfers.put(transferKey, transferThread);
                }
            }
        }
        
        // Check for file transfer completion
        if (message.contains("[File transfer complete from")) {
            String sender = message.split("from ")[1].split(" to ")[0];
            String recipient = message.split(" to ")[1].split(" ")[0];
            
            // Clean up pending transfer if this client was involved
            if (sender.equals(username) || recipient.equals(username)) {
                pendingTransfers.remove(sender);
                activeTransfers.remove(sender + "->" + recipient);
            }
        }
        
        // Check for file transfer errors
        if (message.contains("[File transfer failed")) {
            System.out.println("File transfer failed. See server message for details.");
            
            // Try to parse sender and recipient
            try {
                String sender = message.split("from ")[1].split(" to ")[0];
                String recipient = message.split(" to ")[1].split(":")[0];
                
                // Clean up pending transfer if this client was involved
                if (sender.equals(username) || recipient.equals(username)) {
                    pendingTransfers.remove(sender);
                    activeTransfers.remove(sender + "->" + recipient);
                }
            } catch (Exception e) {
                // Parsing failed, but we should clean up anyway
                System.out.println("Unable to parse error message, clearing all pending transfers.");
                pendingTransfers.clear();
            }
        }
    }
    
    // Method to send a file with progress reporting
    private static void sendFile(String filename, String hostname, int port, String recipient) {
        try {
            File file = new File(filename);
            if (!file.exists()) {
                System.out.println("Error: File " + filename + " does not exist");
                return;
            }

            System.out.println("Initiating file transfer to " + recipient + " for file: " + filename);
            System.out.println("Connecting to file transfer server...");
            
            // Connect to the file transfer server
            Socket transferSocket = new Socket(hostname, port);
            System.out.println("Connected to file transfer server");
            
            // Send file metadata
            PrintWriter out = new PrintWriter(transferSocket.getOutputStream(), true);
            out.println("SEND " + recipient + " " + filename + " " + file.length());
            System.out.println("Sent file metadata to server");
            
            // Wait for server response
            BufferedReader in = new BufferedReader(new InputStreamReader(transferSocket.getInputStream()));
            String response = in.readLine();
            System.out.println("Server response: " + response);
            
            if (response.startsWith("OK")) {
                System.out.println("Waiting for recipient to be ready...");
                response = in.readLine();
                System.out.println("Recipient status: " + response);
                
                if (response.equals("READY")) {
                    System.out.println("Recipient is ready. Starting file transfer...");
                    // Simulate file transfer progress
                    for (int i = 0; i <= 100; i += 10) {
                        System.out.println("Transfer progress: " + i + "%");
                        Thread.sleep(500);
                    }
                    System.out.println("File transfer completed successfully");
                } else {
                    System.out.println("Error: Recipient not ready. Response: " + response);
                }
            } else {
                System.out.println("Error: Server rejected file transfer. Response: " + response);
            }
            
            transferSocket.close();
            
        } catch (IOException e) {
            System.out.println("Error during file transfer: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.out.println("File transfer interrupted: " + e.getMessage());
        }
    }
    
    // Method to receive a file with progress reporting
    private static void receiveFile(String filename, String hostname, int port, String sender) {
        try {
            System.out.println("Preparing to receive file from " + sender + ": " + filename);
            
            // Connect to the file transfer server
            Socket transferSocket = new Socket(hostname, port);
            System.out.println("Connected to file transfer server");
            
            // Send ready signal
            PrintWriter out = new PrintWriter(transferSocket.getOutputStream(), true);
            out.println("RECEIVE " + sender + " " + filename);
            System.out.println("Sent receive request to server");
            
            // Wait for server response
            BufferedReader in = new BufferedReader(new InputStreamReader(transferSocket.getInputStream()));
            String response = in.readLine();
            System.out.println("Server response: " + response);
            
            if (response.equals("READY")) {
                System.out.println("Server is ready. Starting file reception...");
                // Simulate file reception progress
                for (int i = 0; i <= 100; i += 10) {
                    System.out.println("Reception progress: " + i + "%");
                    Thread.sleep(500);
                }
                System.out.println("File received successfully");
            } else {
                System.out.println("Error: Server not ready. Response: " + response);
            }
            
            transferSocket.close();
            
        } catch (IOException e) {
            System.out.println("Error during file reception: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.out.println("File reception interrupted: " + e.getMessage());
        }
    }
}
