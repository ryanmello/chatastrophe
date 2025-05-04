import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class WebServer {
    public static void main(String[] args) {
        int httpPort = 6789;
        int httpsPort = 8443;

        // Start HTTP server
        new Thread(() -> startHttpServer(httpPort)).start();

        // Optional HTTPS support
        if (args.length == 2) {
            String keystore = args[0];
            String password = args[1];

            System.setProperty("javax.net.ssl.keyStore", keystore);
            System.setProperty("javax.net.ssl.keyStorePassword", password);

            new Thread(() -> startHttpsServer(httpsPort)).start();
        } else if (args.length == 1) {
            System.err.println("⚠️  Only one argument provided. HTTPS disabled.");
        } else {
            System.out.println("HTTPS not enabled (no keystore provided).");
        }
    }

    private static void startHttpServer(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("HTTP server listening on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new HttpRequestHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("HTTP server error: " + e.getMessage());
        }
    }

    private static void startHttpsServer(int port) {
        try {
            SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket sslServerSocket = (SSLServerSocket) factory.createServerSocket(port);
            System.out.println("HTTPS server listening on port " + port);
            while (true) {
                Socket clientSocket = sslServerSocket.accept();
                new Thread(new HttpRequestHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("HTTPS server error: " + e.getMessage());
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
                    if (b[len - 4] == '\r' && b[len - 3] == '\n') break;
                }
                prev = curr;
            }

            String headers = headerBuffer.toString();
            System.out.println("=== Incoming Headers ===\n" + headers);

            String[] lines = headers.split("\r\n");
            if (lines.length == 0) {
                send400(out);
                return;
            }

            String[] requestLineParts = lines[0].split(" ");
            if (requestLineParts.length < 3) {
                send400(out);
                return;
            }

            String method = requestLineParts[0];
            String rawPath = requestLineParts[1];
            String path = URLDecoder.decode(rawPath, "UTF-8");

            if (method.equals("POST")) {
                int contentLength = -1;
                for (String line : lines) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(line.split(":")[1].trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (contentLength <= 0) {
                    send400(out);
                    return;
                }

                byte[] bodyBytes = is.readNBytes(contentLength);
                String body = new String(bodyBytes);
                System.out.println("=== POST Body ===\n" + body);

                String responseBody = "<html><body><h1>POST Received</h1><pre>" + body + "</pre></body></html>";
                out.writeBytes("HTTP/1.1 200 OK\r\n");
                out.writeBytes("Content-Type: text/html\r\n");
                out.writeBytes("Content-Length: " + responseBody.length() + "\r\n");
                out.writeBytes("\r\n");
                out.writeBytes(responseBody);
                return;
            }

            if (method.equals("GET")) {
                File baseDir = new File(".").getCanonicalFile();
                File requestedFile = new File(baseDir, path).getCanonicalFile();

                if (!requestedFile.getPath().startsWith(baseDir.getPath())) {
                    send403(out);
                    return;
                }

                if (!requestedFile.exists() || requestedFile.isDirectory()) {
                    send404(out);
                    return;
                }

                byte[] fileData = Files.readAllBytes(requestedFile.toPath());
                String contentType = contentType(requestedFile.getName());

                out.writeBytes("HTTP/1.1 200 OK\r\n");
                out.writeBytes("Content-Type: " + contentType + "\r\n");
                out.writeBytes("Content-Length: " + fileData.length + "\r\n");
                out.writeBytes("\r\n");
                out.write(fileData);
                return;
            }

            send405(out);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void send404(DataOutputStream out) throws IOException {
        sendSimpleHtml(out, "404 Not Found", "404 Not Found");
    }

    private void send405(DataOutputStream out) throws IOException {
        sendSimpleHtml(out, "405 Method Not Allowed", "405 Method Not Allowed");
    }

    private void send400(DataOutputStream out) throws IOException {
        sendSimpleHtml(out, "400 Bad Request", "400 Bad Request");
    }

    private void send403(DataOutputStream out) throws IOException {
        sendSimpleHtml(out, "403 Forbidden", "403 Forbidden");
    }

    private void sendSimpleHtml(DataOutputStream out, String status, String message) throws IOException {
        String body = "<html><body><h1>" + message + "</h1></body></html>";
        out.writeBytes("HTTP/1.1 " + status + "\r\n");
        out.writeBytes("Content-Type: text/html\r\n");
        out.writeBytes("Content-Length: " + body.length() + "\r\n");
        out.writeBytes("\r\n");
        out.writeBytes(body);
    }

    private String contentType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html";
        if (filename.endsWith(".txt")) return "text/plain";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".js")) return "application/javascript";
        return "application/octet-stream";
    }
}
