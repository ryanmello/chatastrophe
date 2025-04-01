import java.io.*;
import java.net.*;
import java.util.*;

public class tcpcss_server {
    private static final int DEFAULT_PORT = 12345;
    private static final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

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

            while (true) {
                // Accept new client connections
                Socket clientSocket = serverSocket.accept();

                // Get current thread information
                Thread currentThread = Thread.currentThread();
                String threadName = currentThread.getName();

                // Display connection information
                System.out.println("New connection accepted by thread: " + threadName + ", ip is: " +
                        clientSocket.getInetAddress().getHostAddress() +
                        ", port: " + clientSocket.getPort());

                // Create a new client handler
                ClientHandler clientHandler = new ClientHandler(clientSocket, clients.size());

                // Add client to the list
                System.out.println("Adding to list of sockets as " + clients.size());
                clients.add(clientHandler);

                // Start a new thread for this client
                Thread clientThread = new Thread(clientHandler, "ClientHandler-" + clients.size());
                System.out.println("Starting new client handler thread: " + clientThread.getName());
                clientThread.start();
            }
        } catch (IOException e) {
            System.out.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void removeClient(ClientHandler client) {
        clients.remove(client);
        System.out.println("Client removed. Remaining clients: " + clients.size());
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private int clientID;

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
            // For now, we're just keeping the connection open
            // In a real chat app, we would read messages here

            String inputLine;
            while ((inputLine = reader.readLine()) != null) {
                // Just echo back for testing connection
                writer.println("Server received: " + inputLine);
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

            // Remove this client from the list
            tcpcss_server.removeClient(this);
        }
    }
}