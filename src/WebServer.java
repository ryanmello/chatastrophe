import java.io.*;
import java.net.*;

public class WebServer {
    public static void main(String[] args) {
        int port = 6789;

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("HTTP Server running on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                HttpRequestHandler handler = new HttpRequestHandler(clientSocket);
                new Thread(handler).start();
            }

        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

class HttpRequestHandler implements Runnable {
    private final Socket socket;

    public HttpRequestHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(os)
        ) {
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int prev = 0, curr;

            while ((curr = is.read()) != -1) {
                headerBuffer.write(curr);

                int len = headerBuffer.size();
                if (prev == '\r' && curr == '\n' && len >= 4) {
                    byte[] b = headerBuffer.toByteArray();
                    if (b[len - 4] == '\r' && b[len - 3] == '\n') {
                        break;
                    }
                }
                prev = curr;
            }

            String headers = headerBuffer.toString();
            System.out.println("=== Incoming HTTP Request ===");
            System.out.println(headers);
            System.out.println("=== End Request ===");

            // Respond with a basic 200 OK
            out.writeBytes("HTTP/1.1 200 OK\r\n");
            out.writeBytes("Content-Type: text/plain\r\n");
            out.writeBytes("Content-Length: 13\r\n");
            out.writeBytes("\r\n");
            out.writeBytes("Hello, World!");

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
