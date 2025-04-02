import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class tcpccs_client {
    private static final int DEFAULT_PORT = 12345;
    private static volatile boolean running = true;
    private static Map<String, FileTransferInfo> pendingTransfers = new ConcurrentHashMap<>();
    
    // Class to hold file transfer information
    private static class FileTransferInfo {
        String sender;
        String filename;
        long fileSize;
        
        public FileTransferInfo(String sender, String filename, long fileSize) {
            this.sender = sender;
            this.filename = filename;
            this.fileSize = fileSize;
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
                        handleFileTransferNotifications(serverResponse, username);
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
                    }
                }
                
                // Send the message to the server
                out.println(userInput);
                
                // Check if it's a quit command
                if ("/quit".equalsIgnoreCase(userInput)) {
                    running = false;
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
    
    // Process file transfer notifications from server messages
    private static void handleFileTransferNotifications(String message, String username) {
        // Check for file transfer initiation
        if (message.contains("[File transfer initiated from")) {
            // Parse the message to extract details
            // Format: "[File transfer initiated from <sender> to <recipient> <filename> (<size> KB)]"
            try {
                String sender = message.split("from ")[1].split(" to ")[0];
                String recipient = message.split(" to ")[1].split(" ")[0];
                String filename = message.substring(
                    message.indexOf(recipient) + recipient.length() + 1,
                    message.lastIndexOf(" (")
                ).trim();
                String sizeStr = message.substring(
                    message.lastIndexOf("(") + 1,
                    message.lastIndexOf(" KB)")
                ).trim();
                long size = Long.parseLong(sizeStr);
                
                // If this client is the recipient, store the pending transfer
                if (recipient.equals(username)) {
                    pendingTransfers.put(sender, new FileTransferInfo(sender, filename, size));
                    System.out.println("Type '/acceptfile " + sender + "' to accept or '/rejectfile " + sender + "' to reject the file.");
                }
            } catch (Exception e) {
                System.out.println("Error parsing file transfer notification: " + e.getMessage());
            }
        }
        
        // Check for file transfer starting
        if (message.contains("[Starting file transfer between")) {
            // In a real implementation, this would trigger connection establishment
            // For the demo, we'll just acknowledge it
            System.out.println("File transfer starting...");
        }
        
        // Check for file transfer completion
        if (message.contains("[File transfer complete from")) {
            // In a real implementation, this would signal completion of the transfer
            // For the demo, we'll just acknowledge it
            System.out.println("File transfer completed successfully.");
            
            // Parse the message to extract details
            try {
                String sender = message.split("from ")[1].split(" to ")[0];
                String recipient = message.split(" to ")[1].split(" ")[0];
                
                // Clean up pending transfer if this client was involved
                if (sender.equals(username) || recipient.equals(username)) {
                    pendingTransfers.remove(sender);
                }
            } catch (Exception e) {
                System.out.println("Error parsing file transfer completion notification: " + e.getMessage());
            }
        }
    }
    
    // For a real implementation, these methods would be used for actual file transfer
    
    // Method to send a file
    private static void sendFile(String filename, Socket socket) {
        try (FileInputStream fileIn = new FileInputStream(filename);
             OutputStream socketOut = socket.getOutputStream()) {
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                socketOut.write(buffer, 0, bytesRead);
            }
            
            socketOut.flush();
            System.out.println("File sent successfully.");
            
        } catch (IOException e) {
            System.out.println("Error sending file: " + e.getMessage());
        }
    }
    
    // Method to receive a file
    private static void receiveFile(String filename, Socket socket) {
        try (FileOutputStream fileOut = new FileOutputStream(filename);
             InputStream socketIn = socket.getInputStream()) {
            
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = socketIn.read(buffer)) != -1) {
                fileOut.write(buffer, 0, bytesRead);
            }
            
            System.out.println("File received successfully.");
            
        } catch (IOException e) {
            System.out.println("Error receiving file: " + e.getMessage());
        }
    }
}
