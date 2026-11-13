package com.max2idea.android.limbo.sharing;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small, dependency free WebDAV (class 1,2) server that exposes a directory of the Android
 * device to the guest OS running inside qemu.
 *
 * It only listens on the loopback interface. With qemu user networking (slirp) the guest
 * reaches the Android loopback through the special address 10.0.2.2, so no changes to qemu
 * are needed. The folder is served live: every request goes straight to the file system so
 * changes made on Android or in the guest are visible to the other side immediately.
 *
 * This class intentionally has no Android dependencies so it can be tested on a desktop JVM.
 */
public class WebDavServer {

    public interface Logger {
        void log(String message, Throwable error);
    }

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_HEADER_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int SOCKET_TIMEOUT_MS = 5 * 60 * 1000;

    private final File root;
    private final int port;
    private final boolean readOnly;
    private final String password;
    private final Logger logger;

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private volatile boolean running;
    private final List<Socket> openSockets = new ArrayList<Socket>();

    /**
     * @param root     directory to share
     * @param port     loopback tcp port to listen on, 0 picks a free one
     * @param readOnly reject every request that would modify the folder
     * @param password if not empty clients must authenticate with HTTP Basic (any user name)
     */
    public WebDavServer(File root, int port, boolean readOnly, String password, Logger logger) {
        this.root = root;
        this.port = port;
        this.readOnly = readOnly;
        this.password = (password == null || password.length() == 0) ? null : password;
        this.logger = logger;
    }

    public synchronized void start() throws IOException {
        if (running)
            return;
        if (root == null || !root.isDirectory())
            throw new IOException("Shared folder does not exist: " + root);
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        executor = Executors.newCachedThreadPool();
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "LimboWebDav");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(serverSocket);
        synchronized (openSockets) {
            for (Socket s : openSockets)
                closeQuietly(s);
            openSockets.clear();
        }
        if (executor != null)
            executor.shutdownNow();
        serverSocket = null;
        executor = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        ServerSocket s = serverSocket;
        return s != null ? s.getLocalPort() : port;
    }

    // ------------------------------------------------------------------------------------
    // connection handling
    // ------------------------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                synchronized (openSockets) {
                    openSockets.add(socket);
                }
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            serve(socket);
                        } finally {
                            synchronized (openSockets) {
                                openSockets.remove(socket);
                            }
                            closeQuietly(socket);
                        }
                    }
                });
            } catch (SocketException e) {
                if (running)
                    log("accept failed", e);
            } catch (Exception e) {
                if (running)
                    log("accept failed", e);
            }
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE);
            OutputStream out = socket.getOutputStream();
            boolean keepAlive = true;
            while (running && keepAlive) {
                Request req = readRequest(in);
                if (req == null)
                    break;
                Response res = new Response(out, req);
                try {
                    handle(req, res, in);
                } catch (HttpError e) {
                    res.reset();
                    res.sendError(e.status, e.getMessage());
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    log("request failed: " + req.method + " " + req.rawPath, e);
                    if (!res.committed) {
                        res.reset();
                        res.sendError(500, "Internal Server Error");
                    }
                }
                keepAlive = res.keepAlive && !req.closeAfter;
                // drain what the handler did not consume so the next request parses properly
                if (keepAlive && !req.bodyConsumed) {
                    try {
                        req.drainBody(in);
                    } catch (IOException e) {
                        keepAlive = false;
                    }
                }
                out.flush();
            }
        } catch (EOFException e) {
            // client went away
        } catch (IOException e) {
            if (running)
                log("connection error: " + e.getMessage(), null);
        } catch (Exception e) {
            log("connection error", e);
        }
    }

    // ------------------------------------------------------------------------------------
    // request parsing
    // ------------------------------------------------------------------------------------

    private static class Request {
        String method;
        String rawPath;
        String version;
        Map<String, String> headers = new HashMap<String, String>();
        boolean closeAfter;
        boolean chunked;
        long contentLength;
        boolean bodyConsumed;

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        void drainBody(InputStream in) throws IOException {
            if (bodyConsumed)
                return;
            InputStream body = openBody(in);
            byte[] buf = new byte[8192];
            while (body.read(buf) >= 0) {
                // discard
            }
        }

        InputStream openBody(InputStream in) {
            bodyConsumed = true;
            if (chunked)
                return new ChunkedInputStream(in);
            return new LimitedInputStream(in, contentLength);
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        // be tolerant of empty lines between pipelined requests
        while (line != null && line.length() == 0)
            line = readLine(in);
        if (line == null)
            return null;
        String[] parts = line.split(" ");
        if (parts.length < 3)
            throw new HttpError(400, "Bad Request");
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.US);
        req.rawPath = parts[1];
        req.version = parts[2];
        for (int i = 0; i < MAX_HEADERS; i++) {
            String h = readLine(in);
            if (h == null)
                throw new EOFException();
            if (h.length() == 0)
                break;
            int idx = h.indexOf(':');
            if (idx > 0)
                req.headers.put(h.substring(0, idx).trim().toLowerCase(Locale.US), h.substring(idx + 1).trim());
        }
        String te = req.header("transfer-encoding");
        req.chunked = te != null && te.toLowerCase(Locale.US).contains("chunked");
        String cl = req.header("content-length");
        if (cl != null) {
            try {
                req.contentLength = Long.parseLong(cl);
            } catch (NumberFormatException e) {
                throw new HttpError(400, "Bad Content-Length");
            }
        }
        String conn = req.header("connection");
        if (req.version.equals("HTTP/1.0"))
            req.closeAfter = conn == null || !conn.toLowerCase(Locale.US).contains("keep-alive");
        else
            req.closeAfter = conn != null && conn.toLowerCase(Locale.US).contains("close");
        return req;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n')
                break;
            if (c != '\r')
                bos.write(c);
            if (bos.size() > MAX_HEADER_LINE)
                throw new HttpError(431, "Header too large");
        }
        if (!any)
            return null;
        return new String(bos.toByteArray(), "ISO-8859-1");
    }

    private static class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0)
                return -1;
            int r = in.read();
            if (r >= 0)
                remaining--;
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0)
                return -1;
            int r = in.read(b, off, (int) Math.min(len, remaining));
            if (r > 0)
                remaining -= r;
            return r;
        }
    }

    private static class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long chunkRemaining = 0;
        private boolean eof = false;
        private boolean started = false;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        private void nextChunk() throws IOException {
            if (started) {
                readLine(in); // CRLF after previous chunk data
            }
            started = true;
            String size = readLine(in);
            if (size == null)
                throw new EOFException();
            int semi = size.indexOf(';');
            if (semi >= 0)
                size = size.substring(0, semi);
            try {
                chunkRemaining = Long.parseLong(size.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk size");
            }
            if (chunkRemaining == 0) {
                eof = true;
                // trailers
                String t;
                while ((t = readLine(in)) != null && t.length() > 0) {
                    // ignore
                }
            }
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof)
                return -1;
            if (chunkRemaining == 0) {
                nextChunk();
                if (eof)
                    return -1;
            }
            int r = in.read(b, off, (int) Math.min(len, chunkRemaining));
            if (r > 0)
                chunkRemaining -= r;
            return r;
        }
    }

    // ------------------------------------------------------------------------------------
    // response writing
    // ------------------------------------------------------------------------------------

    private static class HttpError extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final SimpleDateFormat newHttpDateFormat() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f;
    }

    private static final SimpleDateFormat newIsoDateFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 207: return "Multi-Status";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 412: return "Precondition Failed";
            case 415: return "Unsupported Media Type";
            case 416: return "Requested Range Not Satisfiable";
            case 423: return "Locked";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 507: return "Insufficient Storage";
            default: return "Status " + status;
        }
    }

    private static class Response {
        final OutputStream out;
        final Request req;
        int status = 200;
        final Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        boolean committed;
        boolean keepAlive = true;

        Response(OutputStream out, Request req) {
            this.out = out;
            this.req = req;
        }

        void reset() {
            if (!committed) {
                headers.clear();
                status = 200;
            }
        }

        void header(String name, String value) {
            headers.put(name, value);
        }

        private void writeHead(long contentLength) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
            sb.append("Date: ").append(newHttpDateFormat().format(new Date())).append("\r\n");
            sb.append("Server: LimboWebDAV\r\n");
            sb.append("DAV: 1, 2\r\n");
            sb.append("MS-Author-Via: DAV\r\n");
            for (Map.Entry<String, String> e : headers.entrySet())
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (contentLength >= 0)
                sb.append("Content-Length: ").append(contentLength).append("\r\n");
            sb.append("Connection: ").append(keepAlive && !req.closeAfter ? "keep-alive" : "close").append("\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes("ISO-8859-1"));
            committed = true;
        }

        void sendBytes(int status, String contentType, byte[] body) throws IOException {
            this.status = status;
            if (contentType != null)
                header("Content-Type", contentType);
            boolean head = req.method.equals("HEAD");
            writeHead(body.length);
            if (!head)
                out.write(body);
        }

        void sendEmpty(int status) throws IOException {
            this.status = status;
            writeHead(0);
        }

        void sendError(int status, String message) throws IOException {
            if (committed) {
                keepAlive = false;
                return;
            }
            if (status == 401)
                header("WWW-Authenticate", "Basic realm=\"Limbo Shared Folder\", charset=\"UTF-8\"");
            String msg = message == null ? statusText(status) : message;
            byte[] body = (msg + "\n").getBytes("UTF-8");
            // a response with an error status may be sent while the request body is unread
            sendBytes(status, "text/plain; charset=utf-8", body);
        }
    }

    // ------------------------------------------------------------------------------------
    // request dispatch
    // ------------------------------------------------------------------------------------

    private void handle(Request req, Response res, InputStream in) throws Exception {
        if (!authorized(req)) {
            req.drainBody(in);
            res.sendError(401, "Authentication required");
            return;
        }

        String method = req.method;
        if (method.equals("OPTIONS")) {
            req.drainBody(in);
            res.header("Allow", readOnly
                    ? "OPTIONS, GET, HEAD, PROPFIND, LOCK, UNLOCK"
                    : "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK");
            res.sendEmpty(200);
            return;
        }

        boolean writes = method.equals("PUT") || method.equals("DELETE") || method.equals("MKCOL")
                || method.equals("COPY") || method.equals("MOVE") || method.equals("PROPPATCH");
        if (writes && readOnly) {
            req.drainBody(in);
            throw new HttpError(403, "Shared folder is read-only");
        }

        File target = resolve(req.rawPath);

        if (req.header("expect") != null && req.header("expect").equalsIgnoreCase("100-continue")
                && (method.equals("PUT") || method.equals("PROPFIND") || method.equals("LOCK"))) {
            res.out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("ISO-8859-1"));
            res.out.flush();
        }

        if (method.equals("GET") || method.equals("HEAD")) {
            req.drainBody(in);
            doGet(req, res, target);
        } else if (method.equals("PROPFIND")) {
            req.drainBody(in);
            doPropfind(req, res, target);
        } else if (method.equals("PROPPATCH")) {
            req.drainBody(in);
            doProppatch(req, res, target);
        } else if (method.equals("PUT")) {
            doPut(req, res, target, in);
        } else if (method.equals("DELETE")) {
            req.drainBody(in);
            doDelete(res, target);
        } else if (method.equals("MKCOL")) {
            req.drainBody(in);
            doMkcol(res, target);
        } else if (method.equals("COPY") || method.equals("MOVE")) {
            req.drainBody(in);
            doCopyMove(req, res, target, method.equals("MOVE"));
        } else if (method.equals("LOCK")) {
            req.drainBody(in);
            doLock(req, res, target);
        } else if (method.equals("UNLOCK")) {
            req.drainBody(in);
            res.sendEmpty(204);
        } else {
            req.drainBody(in);
            res.header("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK");
            throw new HttpError(405, "Method Not Allowed");
        }
    }

    private boolean authorized(Request req) {
        if (password == null)
            return true;
        String auth = req.header("authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6))
            return false;
        try {
            byte[] decoded = base64Decode(auth.substring(6).trim());
            String cred = new String(decoded, "UTF-8");
            int idx = cred.indexOf(':');
            String pass = idx >= 0 ? cred.substring(idx + 1) : cred;
            return constantTimeEquals(pass, password);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x, y;
        try {
            x = a.getBytes("UTF-8");
            y = b.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return false;
        }
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++)
            diff |= x[i] ^ y[i];
        return diff == 0;
    }

    // minimal base64 decoder so this class works on both Android (API 21+) and a desktop JVM
    private static byte[] base64Decode(String s) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=')
                break;
            int v = alphabet.indexOf(c);
            if (v < 0)
                continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((buffer >> bits) & 0xff);
            }
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------------------------
    // path handling
    // ------------------------------------------------------------------------------------

    /**
     * Maps a request target to a file below the shared root. Rejects anything that could
     * escape the root (.., symlinks that point outside, encoded separators).
     */
    private File resolve(String rawPath) throws IOException {
        String path = rawPath;
        int q = path.indexOf('?');
        if (q >= 0)
            path = path.substring(0, q);
        if (path.startsWith("http://") || path.startsWith("https://")) {
            int slash = path.indexOf('/', path.indexOf("//") + 2);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            throw new HttpError(400, "Bad path");
        }
        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0)
            throw new HttpError(400, "Bad path");
        File f = root;
        for (String seg : decoded.split("/")) {
            if (seg.length() == 0 || seg.equals("."))
                continue;
            if (seg.equals(".."))
                throw new HttpError(403, "Forbidden");
            f = new File(f, seg);
        }
        String rootCanon = root.getCanonicalPath();
        String canon = f.getCanonicalPath();
        if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator))
            throw new HttpError(403, "Forbidden");
        return f;
    }

    private String hrefFor(File f, boolean isDir) throws IOException {
        String rootCanon = root.getCanonicalPath();
        String canon = f.getCanonicalPath();
        // use the logical (non symlink resolved) path relative to the root
        String rootPath = root.getAbsolutePath();
        String abs = f.getAbsolutePath();
        String rel;
        if (abs.equals(rootPath))
            rel = "";
        else if (abs.startsWith(rootPath + File.separator))
            rel = abs.substring(rootPath.length());
        else if (canon.startsWith(rootCanon + File.separator))
            rel = canon.substring(rootCanon.length());
        else
            rel = "";
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.split(File.separator.equals("\\") ? "\\\\" : "/")) {
            if (seg.length() == 0)
                continue;
            sb.append('/').append(encodeSegment(seg));
        }
        if (sb.length() == 0 || isDir)
            sb.append('/');
        return sb.toString();
    }

    private static String encodeSegment(String seg) {
        try {
            return URLEncoder.encode(seg, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return seg;
        }
    }

    // ------------------------------------------------------------------------------------
    // methods
    // ------------------------------------------------------------------------------------

    private void doGet(Request req, Response res, File f) throws IOException {
        if (!f.exists())
            throw new HttpError(404, "Not Found");
        if (f.isDirectory()) {
            // a plain browser listing, handy for debugging from the guest
            StringBuilder sb = new StringBuilder("<html><head><meta charset=\"utf-8\"><title>");
            sb.append(escapeXml(f.getName())).append("</title></head><body><ul>");
            File[] children = f.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children);
                for (File c : children) {
                    boolean d = c.isDirectory();
                    sb.append("<li><a href=\"").append(hrefFor(c, d)).append("\">")
                            .append(escapeXml(c.getName())).append(d ? "/" : "").append("</a></li>");
                }
            }
            sb.append("</ul></body></html>");
            res.sendBytes(200, "text/html; charset=utf-8", sb.toString().getBytes("UTF-8"));
            return;
        }
        long length = f.length();
        String type = URLConnection.guessContentTypeFromName(f.getName());
        if (type == null)
            type = "application/octet-stream";
        res.header("Content-Type", type);
        res.header("Last-Modified", newHttpDateFormat().format(new Date(f.lastModified())));
        res.header("Accept-Ranges", "bytes");

        long start = 0, end = length - 1;
        String range = req.header("range");
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) { // suffix range
                    long n = Long.parseLong(spec.substring(1));
                    start = Math.max(0, length - n);
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1)
                        end = Math.min(end, Long.parseLong(spec.substring(dash + 1)));
                }
            } catch (NumberFormatException e) {
                start = 0;
                end = length - 1;
                range = null;
            }
            if (range != null) {
                if (start >= length || start > end) {
                    res.header("Content-Range", "bytes */" + length);
                    throw new HttpError(416, "Range Not Satisfiable");
                }
                res.status = 206;
                res.header("Content-Range", "bytes " + start + "-" + end + "/" + length);
            }
        } else {
            range = null;
        }

        long count = end - start + 1;
        if (length == 0)
            count = 0;
        res.status = range != null ? 206 : 200;
        res.writeHead(count);
        if (req.method.equals("HEAD") || count == 0)
            return;

        FileInputStream fis = new FileInputStream(f);
        try {
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long s = fis.skip(start - skipped);
                    if (s <= 0)
                        break;
                    skipped += s;
                }
            }
            byte[] buf = new byte[BUFFER_SIZE];
            long left = count;
            while (left > 0) {
                int r = fis.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0)
                    break;
                res.out.write(buf, 0, r);
                left -= r;
            }
            if (left > 0)
                res.keepAlive = false; // file shrank while serving
        } finally {
            closeQuietly(fis);
        }
    }

    private void doPut(Request req, Response res, File f, InputStream in) throws IOException {
        if (f.isDirectory()) {
            req.drainBody(in);
            throw new HttpError(405, "Is a collection");
        }
        File parent = f.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            req.drainBody(in);
            throw new HttpError(409, "Parent collection does not exist");
        }
        if (req.header("if-none-match") != null && req.header("if-none-match").trim().equals("*") && f.exists()) {
            req.drainBody(in);
            throw new HttpError(412, "Precondition Failed");
        }
        boolean existed = f.exists();
        InputStream body = req.openBody(in);
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(f);
        } catch (IOException e) {
            req.drainBody(in);
            throw new HttpError(403, "Cannot write: " + e.getMessage());
        }
        try {
            byte[] buf = new byte[BUFFER_SIZE];
            int r;
            while ((r = body.read(buf)) >= 0)
                fos.write(buf, 0, r);
        } catch (IOException e) {
            closeQuietly(fos);
            f.delete();
            throw e;
        } finally {
            closeQuietly(fos);
        }
        res.sendEmpty(existed ? 204 : 201);
    }

    private void doDelete(Response res, File f) throws IOException {
        if (!f.exists())
            throw new HttpError(404, "Not Found");
        if (f.equals(root))
            throw new HttpError(403, "Cannot delete the shared folder itself");
        if (!deleteRecursive(f))
            throw new HttpError(403, "Could not delete");
        res.sendEmpty(204);
    }

    private boolean deleteRecursive(File f) throws IOException {
        // never follow symlinks out of the shared tree
        boolean symlink = !f.getCanonicalPath().equals(new File(f.getParentFile().getCanonicalFile(), f.getName()).getPath());
        if (f.isDirectory() && !symlink) {
            File[] children = f.listFiles();
            if (children != null)
                for (File c : children)
                    if (!deleteRecursive(c))
                        return false;
        }
        return f.delete();
    }

    private void doMkcol(Response res, File f) throws IOException {
        if (f.exists())
            throw new HttpError(405, "Already exists");
        File parent = f.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new HttpError(409, "Parent collection does not exist");
        if (!f.mkdir())
            throw new HttpError(403, "Could not create collection");
        res.sendEmpty(201);
    }

    private void doCopyMove(Request req, Response res, File src, boolean move) throws IOException {
        if (!src.exists())
            throw new HttpError(404, "Not Found");
        String dest = req.header("destination");
        if (dest == null)
            throw new HttpError(400, "Missing Destination");
        File dst = resolve(dest);
        if (dst.equals(root) || src.equals(root))
            throw new HttpError(403, "Forbidden");
        String srcCanon = src.getCanonicalPath();
        String dstCanon = dst.getCanonicalPath();
        if (srcCanon.equals(dstCanon))
            throw new HttpError(403, "Source and destination are the same");
        if (src.isDirectory() && dstCanon.startsWith(srcCanon + File.separator))
            throw new HttpError(403, "Cannot copy a collection into itself");
        File dstParent = dst.getParentFile();
        if (dstParent == null || !dstParent.isDirectory())
            throw new HttpError(409, "Destination parent does not exist");
        String overwrite = req.header("overwrite");
        boolean allowOverwrite = overwrite == null || !overwrite.trim().equalsIgnoreCase("F");
        boolean existed = dst.exists();
        if (existed) {
            if (!allowOverwrite)
                throw new HttpError(412, "Destination exists");
            if (!deleteRecursive(dst))
                throw new HttpError(403, "Could not replace destination");
        }
        if (move) {
            if (!src.renameTo(dst)) {
                copyRecursive(src, dst);
                if (!deleteRecursive(src))
                    throw new HttpError(500, "Copied but could not remove the source");
            }
        } else {
            copyRecursive(src, dst);
        }
        res.sendEmpty(existed ? 204 : 201);
    }

    private void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdir())
                throw new HttpError(403, "Could not create " + dst.getName());
            File[] children = src.listFiles();
            if (children != null)
                for (File c : children)
                    copyRecursive(c, new File(dst, c.getName()));
        } else {
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[BUFFER_SIZE];
                int r;
                while ((r = fis.read(buf)) >= 0)
                    fos.write(buf, 0, r);
            } finally {
                closeQuietly(fis);
                closeQuietly(fos);
            }
            dst.setLastModified(src.lastModified());
        }
    }

    private void doPropfind(Request req, Response res, File f) throws IOException {
        if (!f.exists())
            throw new HttpError(404, "Not Found");
        String depth = req.header("depth");
        boolean children = depth == null || !depth.trim().equals("0");
        if (depth != null && depth.trim().equalsIgnoreCase("infinity"))
            children = true;

        SimpleDateFormat httpDate = newHttpDateFormat();
        SimpleDateFormat isoDate = newIsoDateFormat();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");
        appendResponse(sb, f, httpDate, isoDate);
        if (children && f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) {
                java.util.Arrays.sort(list);
                for (File c : list)
                    appendResponse(sb, c, httpDate, isoDate);
            }
        }
        sb.append("</D:multistatus>\n");
        res.sendBytes(207, "application/xml; charset=utf-8", sb.toString().getBytes("UTF-8"));
    }

    private void appendResponse(StringBuilder sb, File f, SimpleDateFormat httpDate, SimpleDateFormat isoDate) throws IOException {
        boolean dir = f.isDirectory();
        long modified = f.lastModified();
        sb.append("<D:response>");
        sb.append("<D:href>").append(escapeXml(hrefFor(f, dir))).append("</D:href>");
        sb.append("<D:propstat><D:prop>");
        sb.append("<D:displayname>").append(escapeXml(f.equals(root) ? "" : f.getName())).append("</D:displayname>");
        sb.append("<D:getlastmodified>").append(httpDate.format(new Date(modified))).append("</D:getlastmodified>");
        sb.append("<D:creationdate>").append(isoDate.format(new Date(modified))).append("</D:creationdate>");
        if (dir) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            String type = URLConnection.guessContentTypeFromName(f.getName());
            sb.append("<D:resourcetype/>");
            sb.append("<D:getcontentlength>").append(f.length()).append("</D:getcontentlength>");
            sb.append("<D:getcontenttype>").append(escapeXml(type == null ? "application/octet-stream" : type)).append("</D:getcontenttype>");
        }
        sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>");
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>");
        sb.append("</D:response>\n");
    }

    private void doProppatch(Request req, Response res, File f) throws IOException {
        if (!f.exists())
            throw new HttpError(404, "Not Found");
        // Windows sets timestamps this way; we accept and ignore property changes.
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>"
                + escapeXml(hrefFor(f, f.isDirectory())) + "</D:href><D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>\n";
        res.sendBytes(207, "application/xml; charset=utf-8", body.getBytes("UTF-8"));
    }

    /**
     * Locks are not enforced, we only hand out a token because Windows (and Office) refuse
     * to write to a share that does not answer LOCK.
     */
    private void doLock(Request req, Response res, File f) throws IOException {
        String token = "opaquelocktoken:" + UUID.randomUUID();
        res.header("Lock-Token", "<" + token + ">");
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth>"
                + "<D:timeout>Second-3600</D:timeout><D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "</D:activelock></D:lockdiscovery></D:prop>\n";
        res.sendBytes(200, "application/xml; charset=utf-8", body.getBytes("UTF-8"));
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // strip characters that are illegal in XML 1.0
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r')
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    private void log(String message, Throwable t) {
        if (logger != null)
            logger.log(message, t);
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
