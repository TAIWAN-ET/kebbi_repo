package com.example.myapplication.net;

import com.example.myapplication.io.DanceScriptIO;
import com.example.myapplication.model.DanceScript;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Kebbi 端的極簡 HTTP 接收器：讓電腦透過 Wi-Fi 送腳本、下播放 / 停止指令，不用插 USB。
 *
 * <p>API（全部回 JSON）：
 * <pre>
 * GET  /status              目前狀態
 * POST /script[?play=1]     body 為 dance_script.json；play=1 收到就播
 * POST /play                播放目前載入的腳本
 * POST /stop                停止
 * POST /home                歸位
 * </pre>
 *
 * <p>誰會呼叫它：{@code MainActivity} 建立並開關；電腦端用 {@code pc/send_script.py} 呼叫。
 *
 * <p>不負責什麼：不播放、不存檔（交給 {@link Handler}），不做身分驗證。
 * <b>同一個區網內任何人都能讓機器人動</b>，所以只在需要時才打開（UI 上的開關）。
 *
 * <p>純 Java（java.net），不依賴 Android，也不需要額外函式庫；PC 上可以直接測。
 * 一次只處理一個連線 —— 指令很少、很小，不需要並行。
 */
public class ScriptServer {

    /** 預設埠號，電腦端 send_script.py 用同一個值。 */
    public static final int DEFAULT_PORT = 8765;

    /** 腳本大小上限：3 分鐘的舞約 200KB，8MB 已經很寬鬆，避免被塞爆記憶體。 */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    /** 單一連線讀取逾時（毫秒）。 */
    private static final int SOCKET_TIMEOUT_MS = 10_000;

    /** 實際執行各指令的一方（MainActivity 實作）。回傳值是給電腦看的訊息。 */
    public interface Handler {
        String onScript(DanceScript script, boolean play) throws Exception;

        String onPlay() throws Exception;

        String onStop() throws Exception;

        String onHome() throws Exception;

        /** @return 狀態 JSON 物件字串，例如 {"playing":false} */
        String statusJson();
    }

    private final int port;
    private final Handler handler;
    private volatile ServerSocket serverSocket;
    private volatile Thread thread;

    /**
     * @param port    監聽埠號（0 表示由系統挑一個，測試用）
     * @param handler 指令處理者
     */
    public ScriptServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    /**
     * 開始監聽（背景執行緒）。
     *
     * @throws IOException 埠號被佔用等情況
     */
    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            return;
        }
        ServerSocket socket = new ServerSocket(port);
        serverSocket = socket;
        thread = new Thread(() -> acceptLoop(socket), "ScriptServer");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止監聽。 */
    public synchronized void stop() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        thread = null;
    }

    /** @return 是否正在監聽 */
    public boolean isRunning() {
        return serverSocket != null;
    }

    /** @return 實際監聽的埠號；未啟動時回傳建構時的值 */
    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : port;
    }

    /**
     * 列出本機的 IPv4 位址（排除 loopback），顯示在畫面上讓使用者知道電腦要連哪裡。
     *
     * @return IP 字串列表；取不到時回傳空列表
     */
    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return result;
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                handle(client);
            } catch (IOException e) {
                // close() 會讓 accept 丟例外，屬於正常結束；單一連線出錯則繼續服務下一個
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();

        // Step1：請求行，例如 "POST /script?play=1 HTTP/1.1"
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            respond(out, 400, error("bad request line"));
            return;
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        // Step2：標頭，只需要 Content-Length
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    respond(out, 400, error("bad Content-Length"));
                    return;
                }
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            respond(out, 413, error("body too large (max " + MAX_BODY_BYTES + " bytes)"));
            return;
        }

        // Step3：本文
        byte[] body = readBody(in, contentLength);
        if (body == null) {
            respond(out, 400, error("body shorter than Content-Length"));
            return;
        }

        // Step4：路由
        try {
            if ("GET".equals(method) && "/status".equals(path)) {
                respond(out, 200, handler.statusJson());
            } else if ("POST".equals(method) && "/script".equals(path)) {
                DanceScript script;
                try {
                    script = DanceScriptIO.fromJsonString(new String(body, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    respond(out, 400, error("invalid dance script: " + e.getMessage()));
                    return;
                }
                if (script.steps.isEmpty()) {
                    respond(out, 400, error("dance script has no steps"));
                    return;
                }
                respond(out, 200, ok(handler.onScript(script, hasFlag(query, "play"))));
            } else if ("POST".equals(method) && "/play".equals(path)) {
                respond(out, 200, ok(handler.onPlay()));
            } else if ("POST".equals(method) && "/stop".equals(path)) {
                respond(out, 200, ok(handler.onStop()));
            } else if ("POST".equals(method) && "/home".equals(path)) {
                respond(out, 200, ok(handler.onHome()));
            } else {
                respond(out, 404, error("unknown endpoint: " + method + " " + path));
            }
        } catch (Exception e) {
            respond(out, 500, error(String.valueOf(e.getMessage())));
        }
    }

    /** query 裡有 name=1 / name=true / 單獨的 name 都算開啟。 */
    private static boolean hasFlag(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.equals(name) || pair.equals(name + "=1") || pair.equals(name + "=true")) {
                return true;
            }
        }
        return false;
    }

    /** 讀一行（到 \n 為止，去掉 \r）。HTTP 標頭是 ASCII。 */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) {
                throw new IOException("header line too long");
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    /** @return 讀滿 length bytes 的內容；連線提早結束回傳 null */
    private static byte[] readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(length, 0));
        byte[] chunk = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
            if (n == -1) {
                return null;
            }
            buffer.write(chunk, 0, n);
            remaining -= n;
        }
        return buffer.toByteArray();
    }

    private static void respond(OutputStream out, int status, String json) throws IOException {
        byte[] body = (json + "\n").getBytes(StandardCharsets.UTF_8);
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            case 404: reason = "Not Found"; break;
            case 413: reason = "Payload Too Large"; break;
            default: reason = "Internal Server Error"; break;
        }
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String ok(String message) {
        return "{\"ok\": true, \"message\": " + quote(message) + "}";
    }

    private static String error(String message) {
        return "{\"ok\": false, \"message\": " + quote(message) + "}";
    }

    /** JSON 字串跳脫（statusJson 的實作者也可以用）。 */
    public static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 0x20) {
                sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
