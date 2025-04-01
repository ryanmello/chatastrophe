import java.io.*;
import java.net.*;
import java.util.*;

public class tcpcss_server {
    private static final int DEFAULT_PORT = 12345;
    private static final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, ClientHandler> usernameMap = Collections.synchronizedMap(new HashMap<>());
    
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
        }
    }
    
    // Broadcast a message to all clients except the sender
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
}

class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private int clientID;
    private String username;
    
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
            while ((inputLine = reader.readLine()) != null) {
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
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            // Clean up resources when client disconnects
            try {
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
        String[] parts = command.split("\\s+", 2);
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
                    socket.close();
                } catch (IOException e) {
                    System.out.println("Error closing socket: " + e.getMessage());
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
