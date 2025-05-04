import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class WebServer {
    public static void main(String[] args){
        new Thread(() -> startHTTPServer(6789)).start();

        if(args.length == 2){
            System.setProperty("javax.net.ssl.keyStore", args[0]);
            System.setProperty("javax.net.ssl.keyStorePassword", args[1]);

            new Thread(() -> startHTTPSServer(8443)).start();
        }
    }

    private static void startHTTPServer(int port){
        try(ServerSocket serverSocket = new ServerSocket(port)){
            System.out.println("HTTP listening on port: " + port);

            while(true){
                Socket clientSocket = serverSocket.accept();
                new Thread(new HTTPHandler(clientSocket)).start();
            }
        } catch(IOException e){
            System.err.println(e.getMessage());
        }
    }

    private static void startHTTPSServer(int port){
        try {
            SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket sslServerSocket = (SSLServerSocket) factory.createServerSocket(port);
            System.out.println("HTTPS listening on port: " + port);
            while(true){
                Socket clientSocket = sslServerSocket.accept();
                new Thread(new HTTPHandler(clientSocket)).start();
            }
        } catch(IOException e){
            System.err.println(e.getMessage());
        }
    }
}

class HTTPHandler implements Runnable {
    private Socket socket;

    public HTTPHandler(Socket socket){
        this.socket = socket;
    }

    @Override
    public void run(){
        try (
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(os)
        ) {
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int prev = 0, curr;

            while((curr = is.read()) != -1){
                headerBuffer.write(curr);
                int len = headerBuffer.size();
                if(prev == '\r' && curr == '\n' && len >= 4){
                    byte[] b = headerBuffer.toByteArray();
                    if (b[len - 4] == '\r' && b[len - 3] == '\n') break;
                }
                prev = curr;
            }

            String headers = headerBuffer.toString();
            System.out.println("Headers \n" + headers);
            String[] lines = headers.split("\r\n");
            
            if(lines.length == 0){
                error400(out);
                return;
            }

            String[] requestLineParts = lines[0].split(" ");
            if(requestLineParts.length < 3){
                error400(out);
                return;
            }

            String method = requestLineParts[0];
            String path = URLDecoder.decode(requestLineParts[1], "UTF-8");

            if(method.equals("POST")){
                int contentLength = -1;
                for(String line : lines) {
                    if(line.toLowerCase().startsWith("content-length:")){
                        try {
                            contentLength = Integer.parseInt(line.split(":")[1].trim());
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if(contentLength <= 0){
                    error400(out);
                    return;
                }

                byte[] bodyBytes = is.readNBytes(contentLength);
                String body = new String(bodyBytes);
                System.out.println("POST Body: \n" + body);

                String responseBody = "POST:" + body;
                out.writeBytes("HTTP 200 OK\r\n");
                out.writeBytes("Content-Type: text/html\r\n");
                out.writeBytes("Content-Length: " + responseBody.length() + "\r\n");
                out.writeBytes("\r\n");
                out.writeBytes(responseBody);
                return;
            }

            if(method.equals("GET")){
                File baseDirectory = new File(".").getCanonicalFile();
                File requestedFile = new File(baseDirectory, path).getCanonicalFile();

                if(!requestedFile.getPath().startsWith(baseDirectory.getPath())){
                    error403(out);
                    return;
                }

                if(!requestedFile.exists() || requestedFile.isDirectory()){
                    error404(out);
                    return;
                }

                byte[] data = Files.readAllBytes(requestedFile.toPath());

                out.writeBytes("HTTP/1.1 200 OK \r\n");
                out.writeBytes("Content-Type: " + contentType(requestedFile.getName()) + "\r\n");
                out.writeBytes("Content-Length: " + data.length + "\r\n");
                out.writeBytes("\r\n");
                out.write(data);
                return;
            }

            error405(out);

        } catch(IOException e){
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored){}
        }
    }

    private void error404(DataOutputStream out) throws IOException {
        sendMessage(out, "404 Not Found", "404 Not Found");
    }

    private void error405(DataOutputStream out) throws IOException {
        sendMessage(out, "405 Method Not Allowed", "405 Method Not Allowed");
    }

    private void error400(DataOutputStream out) throws IOException {
        sendMessage(out, "400 Bad Request", "400 Bad Request");
    }

    private void error403(DataOutputStream out) throws IOException {
        sendMessage(out, "403 Forbidden", "403 Forbidden");
    }

    private void sendMessage(DataOutputStream out, String status, String message) throws IOException {
        out.writeBytes("HTTP/1.1 " + status + "\r\n");
        out.writeBytes("Content-Type: text/html\r\n");
        out.writeBytes("Content-Length: " + message.length() + "\r\n");
        out.writeBytes("\r\n");
        out.writeBytes(message);
    }

    private String contentType(String fileName){
        if(fileName.endsWith(".html") || fileName.endsWith(".html")) return "text/html";
        if(fileName.endsWith(".jpeg") || fileName.endsWith(".jpg")) return "image/jpeg";
        if(fileName.endsWith(".png")) return "image/png";
        if(fileName.endsWith(".txt")) return "text/plain";
        if(fileName.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
