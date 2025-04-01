import java.io.*;
import java.net.*;

public class tcpccs_client {
    private static final int DEFAULT_PORT = 12345;
    private static volatile boolean running = true;
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java tcpccs_client <server_hostname> <username>");
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
}
