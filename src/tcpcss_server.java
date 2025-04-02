import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class tcpcss_server {
    private static final int DEFAULT_PORT = 12345;
    private static final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, ClientHandler> usernameMap = Collections.synchronizedMap(new HashMap<>());
    static final Map<String, FileTransferRequest> pendingTransfers = new ConcurrentHashMap<>();
    private static final ExecutorService fileTransferPool = Executors.newCachedThreadPool();
    private static final Map<Integer, ServerSocket> fileTransferSockets = new ConcurrentHashMap<>();
    
    // Class to hold file transfer information
    public static class FileTransferRequest {
        String sender;
        String recipient;
        String filename;
        long fileSize;
        String checksum;
        int transferPort;
        
        public FileTransferRequest(String sender, String recipient, String filename, long fileSize, String checksum) {
            this.sender = sender;
            this.recipient = recipient;
            this.filename = filename;
            this.fileSize = fileSize;
            this.checksum = checksum;
            this.transferPort = -1; // Will be assigned when transfer is started
        }
    }
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
        // Parse command line arguments for custom port
        if (args.length == 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port number. Using default port " + DEFAULT_PORT);
            }
        }
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Listener on port " + port);
            System.out.println("Waiting for connections...");
            
            // Register shutdown hook to close all resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                for (ServerSocket ss : fileTransferSockets.values()) {
                    try {
                        ss.close();
                    } catch (IOException e) {
                        System.out.println("Error closing file transfer socket: " + e.getMessage());
                    }
                }
                fileTransferPool.shutdownNow();
            }));
            
            while (true) {
                // Accept new client connections
                Socket clientSocket = serverSocket.accept();
                
                // Get current thread information
                Thread currentThread = Thread.currentThread();
                String threadName = currentThread.getName();
                
                // Display connection information
                System.out.println("New connection, thread name is " + threadName + ", ip is: " +
                                  clientSocket.getInetAddress().getHostAddress() +
                                  ", port: " + clientSocket.getPort());
                
                // Create a new client handler
                ClientHandler clientHandler = new ClientHandler(clientSocket, clients.size());
                
                // Add client to the list
                System.out.println("Adding to list of sockets as " + clients.size());
                clients.add(clientHandler);
                
                // Start a new thread for this client
                Thread thread = new Thread(clientHandler);
                thread.start();
            }
        } catch (IOException e) {
            System.out.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Ensure executor is shut down
            fileTransferPool.shutdownNow();
        }
    }
    
    // Broadcast a message to all clients except the sender (or all if excludeClient is null)
    public static void broadcast(String message, ClientHandler excludeClient) {
        synchronized(clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient) {
                    client.sendMessage(message);
                }
            }
        }
    }
    
    // Register a username
    public static boolean registerUsername(String username, ClientHandler client) {
        synchronized(usernameMap) {
            if (usernameMap.containsKey(username)) {
                return false; // Username already taken
            }
            usernameMap.put(username, client);
            return true;
        }
    }
    
    // Get a client by username
    public static ClientHandler getClientByUsername(String username) {
        synchronized(usernameMap) {
            return usernameMap.get(username);
        }
    }
    
    // Remove a client from the list and map
    public static void removeClient(ClientHandler client) {
        clients.remove(client);
        
        if (client.getUsername() != null) {
            usernameMap.remove(client.getUsername());
        }
        
        System.out.println("Client removed. Remaining clients: " + clients.size());
    }
    
    // Get a list of all online users
    public static String getOnlineUsers() {
        StringBuilder userList = new StringBuilder("[Online users: ");
        
        synchronized(usernameMap) {
            Iterator<String> iterator = usernameMap.keySet().iterator();
            while (iterator.hasNext()) {
                userList.append(iterator.next());
                if (iterator.hasNext()) {
                    userList.append(", ");
                }
            }
        }
        
        userList.append("]");
        return userList.toString();
    }
    
    // Register a file transfer request
    public static void registerFileTransfer(String sender, String recipient, String filename, 
                                          long fileSize, String checksum) {
        String key = sender + "->" + recipient;
        pendingTransfers.put(key, new FileTransferRequest(sender, recipient, filename, fileSize, checksum));
    }
    
    // Get a pending file transfer
    public static FileTransferRequest getFileTransfer(String sender, String recipient) {
        String key = sender + "->" + recipient;
        return pendingTransfers.get(key);
    }
    
    // Remove a pending file transfer
    public static void removeFileTransfer(String sender, String recipient) {
        String key = sender + "->" + recipient;
        FileTransferRequest request = pendingTransfers.remove(key);
        
        // Close any associated server socket
        if (request != null && request.transferPort > 0) {
            ServerSocket ss = fileTransferSockets.remove(request.transferPort);
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException e) {
                    System.out.println("Error closing transfer socket: " + e.getMessage());
                }
            }
        }
    }
    
    // Start a file transfer session 
    public static void startFileTransfer(FileTransferRequest request) {
        try {
            // Assign a port for this transfer
            int transferPort = findAvailablePort(DEFAULT_PORT + 1);
            request.transferPort = transferPort;
            
            // Create a server socket for this transfer
            ServerSocket transferSocket = new ServerSocket(transferPort);
            fileTransferSockets.put(transferPort, transferSocket);
            
            // Broadcast the start of file transfer with the port information
            String startMessage = "[Starting file transfer from " + request.sender + 
                                " to " + request.recipient + " port:" + transferPort + "]";
            System.out.println(startMessage);
            broadcast(startMessage, null);
            
            // Start a thread to handle this file transfer
            fileTransferPool.submit(() -> {
                try {
                    // Set a timeout for the transfer to start
                    transferSocket.setSoTimeout(60000); // 60 seconds timeout
                    
                    // Wait for both parties to connect
                    Socket senderSocket = null;
                    Socket recipientSocket = null;
                    
                    try {
                        System.out.println("Waiting for sender to connect...");
                        senderSocket = transferSocket.accept();
                        System.out.println("Sender connected from: " + senderSocket.getInetAddress());
                        
                        System.out.println("Waiting for recipient to connect...");
                        recipientSocket = transferSocket.accept();
                        System.out.println("Recipient connected from: " + recipientSocket.getInetAddress());
                        
                        // Set timeouts for the transfer
                        senderSocket.setSoTimeout(300000); // 5 minutes for transfer
                        recipientSocket.setSoTimeout(300000);
                        
                        // Start data transfer
                        transferData(senderSocket, recipientSocket, request);
                        
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout waiting for connections: " + e.getMessage());
                        throw e;
                    } finally {
                        // Clean up resources
                        if (senderSocket != null) {
                            try { senderSocket.close(); } catch (IOException e) {}
                        }
                        if (recipientSocket != null) {
                            try { recipientSocket.close(); } catch (IOException e) {}
                        }
                        try { transferSocket.close(); } catch (IOException e) {}
                        fileTransferSockets.remove(transferPort);
                    }
                    
                } catch (Exception e) {
                    String errorMessage = "[File transfer failed from " + request.sender +
                                        " to " + request.recipient + ": " + e.getMessage() + "]";
                    System.out.println(errorMessage);
                    broadcast(errorMessage, null);
                } finally {
                    removeFileTransfer(request.sender, request.recipient);
                }
            });
            
        } catch (IOException e) {
            String errorMessage = "[File transfer failed from " + request.sender +
                                " to " + request.recipient + ": " + e.getMessage() + "]";
            System.out.println(errorMessage);
            broadcast(errorMessage, null);
            removeFileTransfer(request.sender, request.recipient);
        }
    }
    
    // Find an available port
    private static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port is in use, try the next one
            }
        }
        throw new IllegalStateException("No available port found");
    }

    private static void transferData(Socket senderSocket, Socket recipientSocket, 
                                     FileTransferRequest request) throws IOException {
        // This method creates a direct connection between sender and recipient
        // Server monitors and logs the progress but data flows directly
        
        try (
            BufferedInputStream senderIn = new BufferedInputStream(senderSocket.getInputStream());
            BufferedOutputStream senderOut = new BufferedOutputStream(senderSocket.getOutputStream());
            BufferedInputStream recipientIn = new BufferedInputStream(recipientSocket.getInputStream());
            BufferedOutputStream recipientOut = new BufferedOutputStream(recipientSocket.getOutputStream())
        ) {
            // Set up data streams
            DataInputStream senderDataIn = new DataInputStream(senderIn);
            DataOutputStream senderDataOut = new DataOutputStream(senderOut);
            DataInputStream recipientDataIn = new DataInputStream(recipientIn);
            DataOutputStream recipientDataOut = new DataOutputStream(recipientOut);
            
            // Forward file metadata from sender to recipient
            String filename = senderDataIn.readUTF();
            long fileSize = senderDataIn.readLong();
            String checksum = senderDataIn.readUTF();
            
            recipientDataOut.writeUTF(filename);
            recipientDataOut.writeLong(fileSize);
            recipientDataOut.writeUTF(checksum);
            recipientDataOut.flush();
            
            // Get recipient's response and start position
            String response = recipientDataIn.readUTF();
            senderDataOut.writeUTF(response);
            
            if (!"READY".equals(response)) {
                throw new IOException("Recipient not ready: " + response);
            }
            
            // Forward resume position
            long startPosition = recipientDataIn.readLong();
            senderDataOut.writeLong(startPosition);
            senderDataOut.flush();
            
            // Start transferring data
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = startPosition;
            long lastProgressLog = 0;
            
            while ((bytesRead = senderIn.read(buffer)) != -1) {
                recipientOut.write(buffer, 0, bytesRead);
                recipientOut.flush();
                
                totalBytesRead += bytesRead;
                
                // Log progress every 5% or at least every 5MB
                long progressThreshold = Math.max(fileSize / 20, 5 * 1024 * 1024);
                if (totalBytesRead - lastProgressLog > progressThreshold) {
                    int progressPercent = (int)((totalBytesRead * 100) / fileSize);
                    System.out.println("Transfer progress: " + request.sender + " to " + 
                                     request.recipient + " - " + progressPercent + "% complete (" + 
                                     (totalBytesRead / 1024 / 1024) + "/" + 
                                     (fileSize / 1024 / 1024) + " MB)");
                    lastProgressLog = totalBytesRead;
                }
            }
            
            // Forward final confirmation
            response = recipientDataIn.readUTF();
            senderDataOut.writeUTF(response);
            senderDataOut.flush();
            
            if (!"SUCCESS".equals(response)) {
                throw new IOException("Transfer failed: " + response);
            }
            
            System.out.println("File transfer completed successfully: " + 
                              request.sender + " to " + request.recipient + 
                              " " + filename + " (" + fileSize + " bytes)");
        }
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private int clientID;
    private String username;
    private volatile boolean running = true;
    
    public ClientHandler(Socket socket, int id) {
        this.socket = socket;
        this.clientID = id;
        
        try {
            // Set up input and output streams for communication
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
            System.out.println("Error creating streams: " + e.getMessage());
        }
    }
    
    @Override
    public void run() {
        try {
            // Get username from the first message
            username = reader.readLine();
            
            // Check if username is valid and not already taken
            if (username == null || username.trim().isEmpty() || !tcpcss_server.registerUsername(username, this)) {
                sendMessage("Username is invalid or already taken. Disconnecting.");
                socket.close();
                return;
            }
            
            // Announce new user
            String joinMessage = "[" + username + "] has joined the chat.";
            System.out.println(joinMessage);
            tcpcss_server.broadcast(joinMessage, this);
            
            // Process messages
            String inputLine;
            while (running && (inputLine = reader.readLine()) != null) {
                // Check if it's a command
                if (inputLine.startsWith("/")) {
                    handleCommand(inputLine);
                } else {
                    // Regular chat message
                    String formattedMessage = "[" + username + "] " + inputLine;
                    System.out.println(formattedMessage);
                    tcpcss_server.broadcast(formattedMessage, this);
                }
            }
            
        } catch (IOException e) {
            if (running) {
                System.out.println("Client disconnected: " + e.getMessage());
            }
        } finally {
            // Clean up resources when client disconnects
            try {
                running = false;
                socket.close();
            } catch (IOException e) {
                System.out.println("Error closing socket: " + e.getMessage());
            }
            
            // Remove this client from the list and notify others
            tcpcss_server.removeClient(this);
            
            if (username != null) {
                String leaveMessage = "[" + username + "] has left the chat.";
                System.out.println(leaveMessage);
                tcpcss_server.broadcast(leaveMessage, null);
            }
        }
    }
    
    // Handle chat commands
    private void handleCommand(String command) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();
        
        switch(cmd) {
            case "/who":
                // List all online users
                System.out.println("[" + username + "] requested online users list.");
                String userList = tcpcss_server.getOnlineUsers();
                sendMessage(userList);
                System.out.println(userList);
                break;
                
            case "/quit":
                // Client wants to disconnect
                try {
                    running = false;
                    socket.close();
                } catch (IOException e) {
                    System.out.println("Error closing socket: " + e.getMessage());
                }
                break;
                
            case "/sendfile":
                // Handle file transfer request
                if (parts.length < 3) {
                    sendMessage("Usage: /sendfile <recipient> <filename> [<filesize> <checksum>]");
                    return;
                }
                
                String recipient = parts[1];
                String filename = parts[2];
                
                // Check if recipient exists
                ClientHandler targetClient = tcpcss_server.getClientByUsername(recipient);
                if (targetClient == null) {
                    sendMessage("User " + recipient + " is not online.");
                    return;
                }
                
                // Parse file size and checksum if provided
                long fileSize = 0;
                String checksum = "";
                
                if (parts.length >= 4) {
                    try {
                        fileSize = Long.parseLong(parts[3]);
                    } catch (NumberFormatException e) {
                        sendMessage("Invalid file size format. Must be a number in KB.");
                        return;
                    }
                }
                
                if (parts.length >= 5) {
                    checksum = parts[4];
                }
                
                // Register the file transfer request
                tcpcss_server.registerFileTransfer(username, recipient, filename, fileSize, checksum);
                
                // Notify all users about the file transfer request
                String fileTransferRequest = "[File transfer initiated from " + username +
                                           " to " + recipient + " " + filename + " (" + fileSize + " KB)" +
                                           (checksum.isEmpty() ? "" : " checksum:" + checksum) + "]";
                System.out.println(fileTransferRequest);
                tcpcss_server.broadcast(fileTransferRequest, null);
                break;
                
            case "/acceptfile":
                // Accept a file transfer
                if (parts.length < 2) {
                    sendMessage("Usage: /acceptfile <sender>");
                    return;
                }
                
                String sender = parts[1];
                tcpcss_server.FileTransferRequest transfer = tcpcss_server.getFileTransfer(sender, username);
                
                if (transfer == null) {
                    sendMessage("No pending file transfer from " + sender);
                    return;
                }
                
                // Notify the acceptance
                String acceptMessage = "[File transfer accepted by " + username + 
                                     " from " + sender + " File: " + transfer.filename + 
                                     " (" + transfer.fileSize + " KB)]";
                System.out.println(acceptMessage);
                tcpcss_server.broadcast(acceptMessage, null);
                
                // Start the file transfer
                tcpcss_server.startFileTransfer(transfer);
                break;
                
            case "/rejectfile":
                // Reject a file transfer
                if (parts.length < 2) {
                    sendMessage("Usage: /rejectfile <sender>");
                    return;
                }
                
                String rejectSender = parts[1];
                tcpcss_server.FileTransferRequest rejectTransfer = tcpcss_server.getFileTransfer(rejectSender, username);
                
                if (rejectTransfer == null) {
                    sendMessage("No pending file transfer from " + rejectSender);
                    return;
                }
                
                // Notify the rejection
                String rejectMessage = "[File transfer rejected by " + username + " from " + rejectSender + "]";
                System.out.println(rejectMessage);
                tcpcss_server.broadcast(rejectMessage, null);
                
                // Remove the transfer request
                tcpcss_server.removeFileTransfer(rejectSender, username);
                break;
                
            case "/canceltransfer":
                // Cancel any in-progress transfers
                boolean foundAny = false;
                
                for (Map.Entry<String, tcpcss_server.FileTransferRequest> entry : 
                     new HashMap<>(tcpcss_server.pendingTransfers).entrySet()) {
                    String key = entry.getKey();
                    tcpcss_server.FileTransferRequest req = entry.getValue();
                    
                    if (username.equals(req.sender) || username.equals(req.recipient)) {
                        foundAny = true;
                        String cancelMessage = "[File transfer cancelled by " + username + 
                                             " between " + req.sender + " and " + req.recipient + "]";
                        System.out.println(cancelMessage);
                        tcpcss_server.broadcast(cancelMessage, null);
                        
                        tcpcss_server.removeFileTransfer(req.sender, req.recipient);
                    }
                }
                
                if (!foundAny) {
                    sendMessage("No active transfers to cancel.");
                }
                break;
                
            default:
                // Unknown command
                sendMessage("Unknown command: " + cmd);
                break;
        }
    }
    
    // Send a message to this client
    public void sendMessage(String message) {
        writer.println(message);
    }
    
    // Get the username of this client
    public String getUsername() {
        return username;
    }
}
