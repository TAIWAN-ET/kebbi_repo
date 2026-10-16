package com.example.myapplication.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * 讓電腦不用輸入 IP 就找到 Kebbi：電腦在區網廣播 {@link #PROBE}，這裡回一小段 JSON。
 *
 * <pre>
 * 電腦 → 255.255.255.255:8766  "KEBBI_DISCOVER"
 * Kebbi → 電腦                  {"kebbi": true, "id": "...", "name": "...", "port": 8765}
 * </pre>
 *
 * <p>誰會呼叫它：{@code MainActivity} 和 {@link ScriptServer} 一起開關；電腦端是 {@code pc/kebbi_link.py}。
 *
 * <p>不負責什麼：不收任何指令，只回報自己在哪裡；真正的操作都走 {@link ScriptServer}。
 * 純 Java，PC 上的 FakeKebbi 也用同一份。
 *
 * <p>Android 的 Wi-Fi 晶片在省電時可能丟掉廣播封包，App 要另外拿 MulticastLock。
 */
public class DiscoveryResponder {

    /** 搜尋用的 UDP 埠號，電腦端 kebbi_link.py 用同一個值。 */
    public static final int DEFAULT_PORT = 8766;

    /** 電腦送來的探詢字串。 */
    public static final String PROBE = "KEBBI_DISCOVER";

    private final int port;
    private final String name;
    private final int serverPort;
    /** 每次啟動隨機產生：同一台從好幾個網路介面都回覆時，電腦端靠它去重複。 */
    private final String id = java.util.UUID.randomUUID().toString();
    private volatile DatagramSocket socket;

    /**
     * @param port       監聽的 UDP 埠號
     * @param name       回報給電腦的名稱（顯示用）
     * @param serverPort {@link ScriptServer} 的 TCP 埠號
     */
    public DiscoveryResponder(int port, String name, int serverPort) {
        this.port = port;
        this.name = name;
        this.serverPort = serverPort;
    }

    /**
     * 開始監聽（背景執行緒）。
     *
     * @throws SocketException 埠號被佔用等情況
     */
    public synchronized void start() throws SocketException {
        if (socket != null) {
            return;
        }
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        socket = s;
        Thread thread = new Thread(() -> loop(s), "DiscoveryResponder");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止監聽。 */
    public synchronized void stop() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
    }

    private void loop(DatagramSocket s) {
        byte[] buffer = new byte[256];
        byte[] reply = ("{\"kebbi\": true, \"id\": " + ScriptServer.quote(id)
                + ", \"name\": " + ScriptServer.quote(name)
                + ", \"port\": " + serverPort + "}").getBytes(StandardCharsets.UTF_8);
        while (!s.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (text.trim().startsWith(PROBE)) {
                    s.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                }
            } catch (IOException e) {
                // close() 讓 receive 丟例外屬於正常結束；其他錯誤就繼續等下一個封包
            }
        }
    }
}
