import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.*;

public class tcpccs_client {
    private static final int DEFAULT_PORT = 12345;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java tcpccs <server_hostname>");
            return;
        }

        String hostname = args[0];

        try (
                Socket socket = new Socket(hostname, DEFAULT_PORT);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedReader stdIn = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("Connected to server at " + hostname + ":" + DEFAULT_PORT);
            System.out.println("Type messages to send to the server (type 'exit' to quit):");

            // Start a separate thread to read server responses
            Thread serverListener = new Thread(() -> {
                try {
                    String serverResponse;
                    while ((serverResponse = in.readLine()) != null) {
                        System.out.println("Server: " + serverResponse);
                    }
                } catch (IOException e) {
                    System.out.println("Lost connection to server: " + e.getMessage());
                }
            });
            serverListener.start();

            // Main thread reads user input and sends to server
            String userInput;
            while ((userInput = stdIn.readLine()) != null) {
                // Send the message to the server
                out.println(userInput);

                // Exit the loop if the user types 'exit'
                if ("exit".equalsIgnoreCase(userInput)) {
                    break;
                }
            }

            System.out.println("Disconnecting from server...");

        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + hostname);
        } catch (IOException e) {
            System.out.println("I/O Error: " + e.getMessage());
        }
    }
}
