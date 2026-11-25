package de.wallpanel.cam;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** TCP-interleaved RTSP on 0.0.0.0:8554, path /wallpanel only. */
final class RtspServer {
    static final int PORT = 8554;
    private static final String TAG = "WallpanelCam";
    private static final int MAX_CLIENTS = 4;
    private static final String PUBLIC = "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER";

    private final Rtp.Packetizer packetizer = new Rtp.Packetizer(0x57414C4C, 1200);
    private final int frameRate;

    RtspServer(int frameRate) {
        this.frameRate = Prefs.fps(frameRate);
    }
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final Object params = new Object();
    private final AtomicBoolean running = new AtomicBoolean();

    private ServerSocket server;
    private Thread acceptThread;
    private byte[] sps;
    private byte[] pps;
    private String sdp;
    private long lastTs = -1;

    void onCodecConfig(byte[] data) {
        Rtp.Sets sets = packetizer.absorb(data, sps, pps);
        store(sets.sps, sets.pps);
    }

    void onAccessUnit(byte[] data, long presentationTimeUs) {
        if (!running.get() && sps == null) {
            Rtp.Sets peek = packetizer.absorb(data, sps, pps);
            store(peek.sps, peek.pps);
        }
        if (!running.get()) {
            Rtp.Sets absorbed = packetizer.packetizeAccessUnit(data, 0, sps, pps);
            store(absorbed.sps, absorbed.pps);
            return;
        }
        long ts = presentationTimeUs * 90L / 1000L;
        if (ts <= lastTs) {
            ts = lastTs + (Rtp.CLOCK / frameRate);
        }
        lastTs = ts;
        Rtp.Sets sets = packetizer.packetizeAccessUnit(data, ts, sps, pps);
        store(sets.sps, sets.pps);
        if (sets.packets.isEmpty()) {
            return;
        }
        byte[][] rtp = new byte[sets.packets.size()][];
        for (int i = 0; i < sets.packets.size(); i++) {
            rtp[i] = sets.packets.get(i).bytes;
        }
        for (Client client : clients) {
            if (!client.playing.get()) {
                continue;
            }
            client.offer(interleave(client.channel, rtp));
        }
    }

    boolean awaitParameterSets(long timeoutMs) {
        synchronized (params) {
            long end = System.currentTimeMillis() + timeoutMs;
            while ((sps == null || pps == null) && System.currentTimeMillis() < end) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    params.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return sps != null && pps != null;
        }
    }

    void start() throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("0.0.0.0", PORT));
        server = socket;
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "wallpanel-rtsp");
        acceptThread.start();
        Log.i(TAG, "rtsp listen " + PORT);
    }

    void stop() {
        running.set(false);
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (params) {
            params.notifyAll();
        }
        Log.i(TAG, "rtsp closed");
    }

    private void store(byte[] nextSps, byte[] nextPps) {
        synchronized (params) {
            if (nextSps != null) {
                sps = nextSps;
            }
            if (nextPps != null) {
                pps = nextPps;
            }
            if (sps != null && pps != null) {
                sdp = Rtp.sdp(sps, pps);
                params.notifyAll();
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket socket = server;
            if (socket == null) {
                return;
            }
            try {
                Socket client = socket.accept();
                if (clients.size() >= MAX_CLIENTS) {
                    client.close();
                    continue;
                }
                client.setTcpNoDelay(true);
                client.setSoTimeout(120_000);
                Client session = new Client(client);
                clients.add(session);
                session.start();
            } catch (SocketException e) {
                if (running.get()) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
            }
        }
    }

    private String awaitSdp() {
        synchronized (params) {
            long end = System.currentTimeMillis() + 5000;
            while (sdp == null && running.get()) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    params.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return sdp != null ? sdp : Rtp.sdp(sps, pps);
        }
    }

    private static byte[][] interleave(int channel, byte[][] rtp) {
        byte[][] out = new byte[rtp.length][];
        for (int i = 0; i < rtp.length; i++) {
            byte[] packet = rtp[i];
            byte[] framed = new byte[4 + packet.length];
            framed[0] = '$';
            framed[1] = (byte) channel;
            framed[2] = (byte) ((packet.length >> 8) & 0xFF);
            framed[3] = (byte) (packet.length & 0xFF);
            System.arraycopy(packet, 0, framed, 4, packet.length);
            out[i] = framed;
        }
        return out;
    }

    private final class Client {
        private final Socket socket;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private final ArrayBlockingQueue<byte[][]> outbound = new ArrayBlockingQueue<>(8);
        private final AtomicBoolean playing = new AtomicBoolean();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final String session = Long.toHexString(System.nanoTime());
        private int channel;
        private boolean setup;
        private Thread reader;
        private Thread writer;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void start() {
            reader = new Thread(this::readLoop, "wallpanel-rtsp-read");
            writer = new Thread(this::writeLoop, "wallpanel-rtsp-write");
            writer.start();
            reader.start();
        }

        void offer(byte[][] au) {
            if (!playing.get()) {
                return;
            }
            if (!outbound.offer(au)) {
                outbound.poll();
                outbound.offer(au);
            }
        }

        void close() {
            if (!alive.compareAndSet(true, false)) {
                return;
            }
            playing.set(false);
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void writeLoop() {
            try {
                while (alive.get()) {
                    byte[][] au = outbound.poll(200, TimeUnit.MILLISECONDS);
                    if (au == null) {
                        continue;
                    }
                    synchronized (writeLock) {
                        for (byte[] packet : au) {
                            out.write(packet);
                        }
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                close();
            }
        }

        private void readLoop() {
            try {
                InputStream in = socket.getInputStream();
                while (alive.get()) {
                    int first = in.read();
                    if (first < 0) {
                        break;
                    }
                    if (first == '$') {
                        int ch = readByte(in);
                        int len = (readByte(in) << 8) | readByte(in);
                        if (len < 0 || len > 1_000_000) {
                            break;
                        }
                        skipFully(in, len);
                        continue;
                    }
                    String head = readHeaders(in, first);
                    String[] lines = head.split("\r\n");
                    if (lines.length == 0 || lines[0].isEmpty()) {
                        continue;
                    }
                    String[] request = lines[0].split(" ");
                    if (request.length < 2) {
                        writeResponse(response(400, "Bad Request", null, null));
                        continue;
                    }
                    String method = request[0].toUpperCase(Locale.ROOT);
                    String uri = request[1];
                    String cseq = header(lines, "cseq");
                    String transport = header(lines, "transport");
                    String sessionHeader = header(lines, "session");
                    int length = contentLength(lines);
                    if (length > 0) {
                        if (length > 65536) {
                            break;
                        }
                        skipFully(in, length);
                    }
                    handle(method, uri, cseq, transport, sessionHeader);
                }
            } catch (EOFException ignored) {
            } catch (IOException e) {
                if (alive.get()) {
                    Log.w(TAG, "client: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void handle(String method, String uri, String cseq, String transport, String sessionHeader)
                throws IOException {
            switch (method) {
                case "OPTIONS" -> writeResponse(response(200, "OK", cseq, "Public: " + PUBLIC + "\r\n"));
                case "DESCRIBE" -> describe(uri, cseq);
                case "SETUP" -> setup(uri, cseq, transport);
                case "PLAY" -> play(uri, cseq, sessionHeader);
                case "TEARDOWN" -> {
                    writeResponse(response(200, "OK", cseq, sessionLine()));
                    close();
                }
                case "GET_PARAMETER" -> {
                    if (sessionHeader != null && setup && !sessionMatches(sessionHeader)) {
                        writeResponse(response(454, "Session Not Found", cseq, null));
                    } else {
                        writeResponse(response(200, "OK", cseq, setup ? sessionLine() : null));
                    }
                }
                default -> writeResponse(response(405, "Method Not Allowed", cseq, "Public: " + PUBLIC + "\r\n"));
            }
        }

        private void describe(String uri, String cseq) throws IOException {
            if (!wallpanel(uri)) {
                writeResponse(response(404, "Not Found", cseq, null));
                return;
            }
            String body = awaitSdp();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String extra = "Content-Type: application/sdp\r\n"
                    + "Content-Base: " + baseUrl() + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n";
            byte[] head = response(200, "OK", cseq, extra).getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                out.write(head);
                out.write(bytes);
                out.flush();
            }
            Log.i(TAG, "describe");
        }

        private void setup(String uri, String cseq, String transport) throws IOException {
            if (!wallpanel(uri)) {
                writeResponse(response(404, "Not Found", cseq, null));
                return;
            }
            if (udpTransport(transport)) {
                writeResponse(response(461, "Unsupported Transport", cseq, null));
                Log.i(TAG, "setup udp rejected");
                return;
            }
            channel = interleavedChannel(transport);
            setup = true;
            String extra = "Transport: RTP/AVP/TCP;unicast;interleaved=" + channel + "-" + (channel + 1) + "\r\n"
                    + "Session: " + session + ";timeout=60\r\n";
            writeResponse(response(200, "OK", cseq, extra));
            Log.i(TAG, "setup tcp channel " + channel);
        }

        private void play(String uri, String cseq, String sessionHeader) throws IOException {
            if (!wallpanel(uri)) {
                writeResponse(response(404, "Not Found", cseq, null));
                return;
            }
            if (!setup) {
                writeResponse(response(455, "Method Not Valid in This State", cseq, null));
                return;
            }
            if (sessionHeader != null && !sessionMatches(sessionHeader)) {
                writeResponse(response(454, "Session Not Found", cseq, null));
                return;
            }
            String extra = "Session: " + session + "\r\n"
                    + "Range: npt=0.000-\r\n"
                    + "RTP-Info: url=" + baseUrl() + "trackID=0\r\n";
            writeResponse(response(200, "OK", cseq, extra));
            playing.set(true);
            Log.i(TAG, "play");
        }

        private boolean sessionMatches(String header) {
            String value = header.split(";")[0].trim();
            return session.equals(value);
        }

        private String sessionLine() {
            return "Session: " + session + "\r\n";
        }

        private String baseUrl() {
            String host = socket.getLocalAddress() == null ? null : socket.getLocalAddress().getHostAddress();
            if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) {
                host = "127.0.0.1";
            }
            return "rtsp://" + host + ":" + PORT + "/wallpanel/";
        }

        private void writeResponse(String payload) throws IOException {
            synchronized (writeLock) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    static boolean wallpanel(String uri) {
        String path = uri == null ? "" : uri;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return "/wallpanel".equals(path) || path.startsWith("/wallpanel/");
    }

    static boolean udpTransport(String transport) {
        if (transport == null || transport.isEmpty()) {
            return true;
        }
        String value = transport.toLowerCase(Locale.ROOT);
        if (value.contains("client_port") || value.contains("udp")) {
            return true;
        }
        return !value.contains("tcp") && !value.contains("interleaved");
    }

    static int interleavedChannel(String transport) {
        if (transport == null) {
            return 0;
        }
        int index = transport.toLowerCase(Locale.ROOT).indexOf("interleaved=");
        if (index < 0) {
            return 0;
        }
        int start = index + "interleaved=".length();
        int end = start;
        while (end < transport.length() && Character.isDigit(transport.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(transport.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String response(int code, String reason, String cseq, String extra) {
        StringBuilder out = new StringBuilder();
        out.append("RTSP/1.0 ").append(code).append(' ').append(reason).append("\r\n");
        if (cseq != null) {
            out.append("CSeq: ").append(cseq).append("\r\n");
        }
        out.append("Server: wallpanel-cam\r\n");
        if (extra != null && !extra.isEmpty()) {
            out.append(extra);
            if (!extra.endsWith("\r\n")) {
                out.append("\r\n");
            }
        }
        out.append("\r\n");
        return out.toString();
    }

    private static String header(String[] lines, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static int contentLength(String[] lines) {
        String value = header(lines, "content-length");
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readHeaders(InputStream in, int first) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        acc.write(first);
        while (acc.size() < 16384) {
            int b = readByte(in);
            acc.write(b);
            if (b != '\n' || acc.size() < 4) {
                continue;
            }
            byte[] all = acc.toByteArray();
            int n = all.length;
            if (all[n - 4] == '\r' && all[n - 3] == '\n' && all[n - 2] == '\r' && all[n - 1] == '\n') {
                return acc.toString(StandardCharsets.UTF_8);
            }
        }
        throw new IOException("headers");
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static void skipFully(InputStream in, int length) throws IOException {
        byte[] tmp = new byte[Math.min(length, 2048)];
        int left = length;
        while (left > 0) {
            int n = in.read(tmp, 0, Math.min(tmp.length, left));
            if (n < 0) {
                throw new EOFException();
            }
            left -= n;
        }
    }
}
