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
            this.transferPort = -1;
        }
    }
    
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        
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
                Socket clientSocket = serverSocket.accept();
                
                Thread currentThread = Thread.currentThread();
                String threadName = currentThread.getName();
                
                System.out.println("New connection, thread name is " + threadName + ", ip is: " +
                                  clientSocket.getInetAddress().getHostAddress() +
                                  ", port: " + clientSocket.getPort());
                
                ClientHandler clientHandler = new ClientHandler(clientSocket, clients.size());
                
                System.out.println("Adding to list of sockets as " + clients.size());
                clients.add(clientHandler);
                
                Thread thread = new Thread(clientHandler);
                thread.start();
            }
        } catch (IOException e) {
            System.out.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            fileTransferPool.shutdownNow();
        }
    }
    
    public static void broadcast(String message, ClientHandler excludeClient) {
        synchronized(clients) {
            for (ClientHandler client : clients) {
                if (client != excludeClient) {
                    client.sendMessage(message);
                }
            }
        }
    }
    
    public static boolean registerUsername(String username, ClientHandler client) {
        synchronized(usernameMap) {
            if (usernameMap.containsKey(username)) {
                return false; // Username already taken
            }
            usernameMap.put(username, client);
            return true;
        }
    }
    
    public static ClientHandler getClientByUsername(String username) {
        synchronized(usernameMap) {
            return usernameMap.get(username);
        }
    }
    
    public static void removeClient(ClientHandler client) {
        clients.remove(client);
        
        if (client.getUsername() != null) {
            usernameMap.remove(client.getUsername());
        }
        
        System.out.println("Client removed. Remaining clients: " + clients.size());
    }
    
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
    
    public static void registerFileTransfer(String sender, String recipient, String filename, 
                                          long fileSize, String checksum) {
        String key = sender + "->" + recipient;
        pendingTransfers.put(key, new FileTransferRequest(sender, recipient, filename, fileSize, checksum));
    }
    
    public static FileTransferRequest getFileTransfer(String sender, String recipient) {
        String key = sender + "->" + recipient;
        return pendingTransfers.get(key);
    }
    
    public static void removeFileTransfer(String sender, String recipient) {
        String key = sender + "->" + recipient;
        FileTransferRequest request = pendingTransfers.remove(key);
        
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
    
    public static void startFileTransfer(FileTransferRequest request) {
        try {
            int transferPort = findAvailablePort(DEFAULT_PORT + 1);
            request.transferPort = transferPort;
            
            System.out.println("Creating file transfer server socket on port " + transferPort);
            ServerSocket transferSocket = new ServerSocket(transferPort);
            fileTransferSockets.put(transferPort, transferSocket);
            
            String startMessage = "[Starting file transfer from " + request.sender + 
                                " to " + request.recipient + " port:" + transferPort + "]";
            System.out.println(startMessage);
            broadcast(startMessage, null);
            
            fileTransferPool.submit(() -> {
                try {
                    transferSocket.setSoTimeout(60000);
                    
                    Socket senderSocket = null;
                    Socket recipientSocket = null;
                    
                    try {
                        System.out.println("Waiting for sender (" + request.sender + ") to connect...");
                        senderSocket = transferSocket.accept();
                        System.out.println("Sender connected from: " + senderSocket.getInetAddress());
                        
                        System.out.println("Waiting for recipient (" + request.recipient + ") to connect...");
                        recipientSocket = transferSocket.accept();
                        System.out.println("Recipient connected from: " + recipientSocket.getInetAddress());
                        
                        senderSocket.setSoTimeout(300000);
                        recipientSocket.setSoTimeout(300000);
                        
                        System.out.println("Both parties connected. Starting data transfer...");
                        transferData(senderSocket, recipientSocket, request);
                        
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout waiting for connections: " + e.getMessage());
                        throw e;
                    } finally {
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
    
    private static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) { }
        }
        throw new IllegalStateException("No available port found");
    }

    private static void transferData(Socket senderSocket, Socket recipientSocket, FileTransferRequest request) throws IOException {
        try (
            BufferedInputStream senderIn = new BufferedInputStream(senderSocket.getInputStream());
            BufferedOutputStream senderOut = new BufferedOutputStream(senderSocket.getOutputStream());
            BufferedInputStream recipientIn = new BufferedInputStream(recipientSocket.getInputStream());
            BufferedOutputStream recipientOut = new BufferedOutputStream(recipientSocket.getOutputStream())
        ) {
            DataInputStream senderDataIn = new DataInputStream(senderIn);
            DataOutputStream senderDataOut = new DataOutputStream(senderOut);
            DataInputStream recipientDataIn = new DataInputStream(recipientIn);
            DataOutputStream recipientDataOut = new DataOutputStream(recipientOut);
            
            String filename = senderDataIn.readUTF();
            long fileSize = senderDataIn.readLong();
            String checksum = senderDataIn.readUTF();
            
            recipientDataOut.writeUTF(filename);
            recipientDataOut.writeLong(fileSize);
            recipientDataOut.writeUTF(checksum);
            recipientDataOut.flush();
            
            String response = recipientDataIn.readUTF();
            senderDataOut.writeUTF(response);
            
            if (!"READY".equals(response)) {
                throw new IOException("Recipient not ready: " + response);
            }
            
            long startPosition = recipientDataIn.readLong();
            senderDataOut.writeLong(startPosition);
            senderDataOut.flush();
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = startPosition;
            long lastProgressLog = 0;
            
            while ((bytesRead = senderIn.read(buffer)) != -1) {
                recipientOut.write(buffer, 0, bytesRead);
                recipientOut.flush();
                
                totalBytesRead += bytesRead;
                
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
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new PrintWriter(socket.getOutputStream(), true);
        } catch (IOException e) {
            System.out.println("Error creating streams: " + e.getMessage());
        }
    }
    
    @Override
    public void run() {
        try {
            username = reader.readLine();
            
            if (username == null || username.trim().isEmpty() || !tcpcss_server.registerUsername(username, this)) {
                sendMessage("Username is invalid or already taken. Disconnecting.");
                socket.close();
                return;
            }
            
            String joinMessage = "[" + username + "] has joined the chat.";
            System.out.println(joinMessage);
            tcpcss_server.broadcast(joinMessage, this);
            
            String inputLine;
            while (running && (inputLine = reader.readLine()) != null) {
                if (inputLine.startsWith("/")) {
                    handleCommand(inputLine);
                } else {
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
            try {
                running = false;
                socket.close();
            } catch (IOException e) {
                System.out.println("Error closing socket: " + e.getMessage());
            }
            
            tcpcss_server.removeClient(this);
            
            if (username != null) {
                String leaveMessage = "[" + username + "] has left the chat.";
                System.out.println(leaveMessage);
                tcpcss_server.broadcast(leaveMessage, null);
            }
        }
    }
    
    private void handleCommand(String command) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();
        
        switch(cmd) {
            case "/who":
                System.out.println("[" + username + "] requested online users list.");
                String userList = tcpcss_server.getOnlineUsers();
                sendMessage(userList);
                System.out.println(userList);
                break;
                
            case "/quit":
                try {
                    running = false;
                    socket.close();
                } catch (IOException e) {
                    System.out.println("Error closing socket: " + e.getMessage());
                }
                break;
                
            case "/sendfile":
                if (parts.length < 3) {
                    sendMessage("Usage: /sendfile <recipient> <filename> [<filesize> <checksum>]");
                    return;
                }
                
                String recipient = parts[1];
                String filename = parts[2];
                
                ClientHandler targetClient = tcpcss_server.getClientByUsername(recipient);
                if (targetClient == null) {
                    sendMessage("User " + recipient + " is not online.");
                    return;
                }
                
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
                
                tcpcss_server.registerFileTransfer(username, recipient, filename, fileSize, checksum);
                
                String fileTransferRequest = "[File transfer initiated from " + username +
                                           " to " + recipient + " " + filename + " (" + fileSize + " KB)" +
                                           (checksum.isEmpty() ? "" : " checksum:" + checksum) + "]";
                System.out.println(fileTransferRequest);
                tcpcss_server.broadcast(fileTransferRequest, null);
                break;
                
            case "/acceptfile":
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
                
                String acceptMessage = "[File transfer accepted by " + username + 
                                     " from " + sender + " File: " + transfer.filename + 
                                     " (" + transfer.fileSize + " KB)]";
                System.out.println(acceptMessage);
                tcpcss_server.broadcast(acceptMessage, null);
                
                tcpcss_server.startFileTransfer(transfer);
                break;
                
            case "/rejectfile":
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
                
                String rejectMessage = "[File transfer rejected by " + username + " from " + rejectSender + "]";
                System.out.println(rejectMessage);
                tcpcss_server.broadcast(rejectMessage, null);
                
                tcpcss_server.removeFileTransfer(rejectSender, username);
                break;
                
            case "/canceltransfer":
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
                sendMessage("Unknown command: " + cmd);
                break;
        }
    }
    
    public void sendMessage(String message) {
        writer.println(message);
    }
    
    public String getUsername() {
        return username;
    }
}
