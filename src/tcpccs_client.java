import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class tcpccs_client {
    private static final int DEFAULT_PORT = 12345;
    private static volatile boolean running = true;
    private static Map<String, FileTransferInfo> pendingTransfers = new ConcurrentHashMap<>();
    private static Map<String, Future<?>> activeTransfers = new ConcurrentHashMap<>();
    private static ExecutorService transferExecutor = Executors.newCachedThreadPool();

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
                    // Check if file exists before sending command
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
            transferExecutor.shutdownNow();
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
        for (Map.Entry<String, Future<?>> entry : activeTransfers.entrySet()) {
            System.out.println("Cancelling transfer: " + entry.getKey());
            entry.getValue().cancel(true);
        }
        activeTransfers.clear();
    }
    
    // Process file transfer notifications from server messages
    private static void handleFileTransferNotifications(String message, String username, String serverHost) {
        // Check for file transfer initiation
        if (message.contains("[File transfer initiated from")) {
            // Parse the message to extract details
            // Format: "[File transfer initiated from <sender> to <recipient> <filename> (<size> KB) checksum:<checksum> port:<port>]"
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
                Future<?> transferTask = transferExecutor.submit(() -> {
                    try {
                        sendFile(finalFilename, finalServerHost, finalTransferPort, finalRecipient);
                    } catch (Exception e) {
                        System.out.println("Error during file transfer: " + e.getMessage());
                    } finally {
                        activeTransfers.remove(transferKey);
                    }
                });
                
                activeTransfers.put(transferKey, transferTask);
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
                    Future<?> transferTask = transferExecutor.submit(() -> {
                        try {
                            receiveFile(info.filename, finalServerHost, finalTransferPort, finalSender);
                        } catch (Exception e) {
                            System.out.println("Error during file reception: " + e.getMessage());
                        } finally {
                            pendingTransfers.remove(sender);
                            activeTransfers.remove(transferKey);
                        }
                    });
                    
                    activeTransfers.put(transferKey, transferTask);
                }
            }
        }
        
        // Check for file transfer completion
        if (message.contains("[File transfer complete from")) {
            // In a real implementation, this would signal completion of the transfer
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
        File file = new File(filename);
        if (!file.exists()) {
            System.out.println("Error: File " + filename + " no longer exists.");
            return;
        }
        
        long fileSize = file.length();
        int bufferSize = 8192;
        
        try (
            Socket socket = new Socket(hostname, port);
            FileInputStream fileIn = new FileInputStream(file);
            BufferedOutputStream socketOut = new BufferedOutputStream(socket.getOutputStream());
            BufferedInputStream socketIn = new BufferedInputStream(socket.getInputStream())
        ) {
            // Send file metadata
            DataOutputStream metadataOut = new DataOutputStream(socketOut);
            metadataOut.writeUTF(filename);
            metadataOut.writeLong(fileSize);
            metadataOut.writeUTF(calculateChecksum(file));
            metadataOut.flush();
            
            // Check if receiver is ready
            DataInputStream metadataIn = new DataInputStream(socketIn);
            String response = metadataIn.readUTF();
            if (!"READY".equals(response)) {
                System.out.println("Receiver not ready: " + response);
                return;
            }
            
            // Check if this is a resume operation
            long startPosition = metadataIn.readLong();
            if (startPosition > 0) {
                System.out.println("Resuming transfer from position " + startPosition);
                fileIn.skip(startPosition);
            }
            
            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            long totalBytesRead = startPosition;
            long lastProgressUpdate = 0;
            
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                socketOut.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                // Update progress every 2% or at least every 1MB
                long progressThreshold = Math.max(fileSize / 50, 1024 * 1024);
                if (totalBytesRead - lastProgressUpdate > progressThreshold) {
                    int progressPercent = (int)((totalBytesRead * 100) / fileSize);
                    System.out.println("Sending to " + recipient + ": " + progressPercent + "% complete (" + 
                        (totalBytesRead / 1024) + "/" + (fileSize / 1024) + " KB)");
                    lastProgressUpdate = totalBytesRead;
                    socketOut.flush(); // Flush periodically to ensure data is sent
                }
                
                // Check if we should stop (e.g., due to cancellation)
                if (Thread.currentThread().isInterrupted()) {
                    System.out.println("Transfer cancelled by user.");
                    return;
                }
            }
            
            socketOut.flush();
            
            // Check final confirmation
            response = metadataIn.readUTF();
            if (!"SUCCESS".equals(response)) {
                System.out.println("Transfer failed: " + response);
            } else {
                System.out.println("File sent successfully to " + recipient + ".");
            }
            
        } catch (IOException e) {
            System.out.println("Error sending file: " + e.getMessage());
        }
    }
    
    // Method to receive a file with progress reporting
    private static void receiveFile(String filename, String hostname, int port, String sender) {
        try (Socket socket = new Socket(hostname, port);
             BufferedInputStream socketIn = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream socketOut = new BufferedOutputStream(socket.getOutputStream())) {
            
            // Receive file metadata
            DataInputStream metadataIn = new DataInputStream(socketIn);
            String remoteFilename = metadataIn.readUTF();
            long fileSize = metadataIn.readLong();
            String expectedChecksum = metadataIn.readUTF();
            
            // Adjust filename if needed (e.g., if file already exists)
            File file = new File(filename);
            if (file.exists()) {
                // Create a filename with (1), (2), etc. appended
                String baseName = filename;
                String extension = "";
                int dotIndex = filename.lastIndexOf('.');
                if (dotIndex > 0) {
                    baseName = filename.substring(0, dotIndex);
                    extension = filename.substring(dotIndex);
                }
                
                int counter = 1;
                while (file.exists()) {
                    filename = baseName + "(" + counter + ")" + extension;
                    file = new File(filename);
                    counter++;
                }
            }
            
            // Check if we can resume a previous transfer
            long startPosition = 0;
            File partFile = new File(filename + ".part");
            if (partFile.exists()) {
                startPosition = partFile.length();
                System.out.println("Found partial download, will try to resume from " + startPosition + " bytes");
            }
            
            DataOutputStream metadataOut = new DataOutputStream(socketOut);
            metadataOut.writeUTF("READY");
            metadataOut.writeLong(startPosition);
            metadataOut.flush();
            
            // Open file for writing (append mode if resuming)
            try (FileOutputStream fileOut = new FileOutputStream(partFile, startPosition > 0)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = startPosition;
                long lastProgressUpdate = 0;
                long progressThreshold = Math.max(fileSize / 50, 1024 * 1024);
                
                while (totalBytesRead < fileSize && 
                       (bytesRead = socketIn.read(buffer, 0, (int)Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
                    fileOut.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    // Update progress every 2% or at least every 1MB
                    if (totalBytesRead - lastProgressUpdate > progressThreshold) {
                        int progressPercent = (int)((totalBytesRead * 100) / fileSize);
                        System.out.println("Receiving from " + sender + ": " + progressPercent + "% complete (" + 
                            (totalBytesRead / 1024) + "/" + (fileSize / 1024) + " KB)");
                        lastProgressUpdate = totalBytesRead;
                    }
                    
                    // Check if we should stop (e.g., due to cancellation)
                    if (Thread.currentThread().isInterrupted()) {
                        System.out.println("Transfer cancelled by user.");
                        metadataOut.writeUTF("CANCELLED");
                        metadataOut.flush();
                        return;
                    }
                }
            }
            
            // Verify file integrity with checksum
            String actualChecksum = calculateChecksum(partFile);
            if (!expectedChecksum.equals(actualChecksum)) {
                System.out.println("Warning: Checksum verification failed. File may be corrupted.");
                metadataOut.writeUTF("CHECKSUM_FAILED");
                metadataOut.flush();
                return;
            }
            
            // Rename the .part file to the final filename
            if (!partFile.renameTo(new File(filename))) {
                System.out.println("Warning: Could not rename temporary file.");
                metadataOut.writeUTF("RENAME_FAILED");
                metadataOut.flush();
                return;
            }
            
            metadataOut.writeUTF("SUCCESS");
            metadataOut.flush();
            
            System.out.println("File received successfully from " + sender + ". Saved as: " + filename);
            System.out.println("Checksum verified: " + actualChecksum);
            
        } catch (IOException e) {
            System.out.println("Error receiving file: " + e.getMessage());
        }
    }
}
