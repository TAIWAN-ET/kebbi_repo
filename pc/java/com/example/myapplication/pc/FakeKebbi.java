package com.example.myapplication.pc;

import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.RobotCommand;
import com.example.myapplication.net.DiscoveryResponder;
import com.example.myapplication.net.ScriptServer;
import com.example.myapplication.robot.DanceScriptPlayer;

import java.io.IOException;
import java.util.Locale;

/**
 * 在電腦上假裝成 Kebbi：跑和 App 完全相同的 ScriptServer + DanceScriptPlayer，
 * 只是馬達指令和換臉改成印在終端機上。
 *
 * <p>用途：沒有機器人時，驗證 send_script.py → Wi-Fi → 播放時間軸整條路是通的。
 *
 * <pre>
 * java -cp pc/build/classes com.example.myapplication.pc.FakeKebbi [port]
 * python send_script.py 127.0.0.1 out/xxx/dance_script.json --play
 * python kebbi_link.py find        # 自動搜尋也會找到它
 * </pre>
 *
 * <p>/motor-test 回傳的是假的馬達（固定 {@link #FAKE_MOTOR_DELAY_MS} 反應延遲 + 照速度轉），只用來測流程。
 */
public final class FakeKebbi {

    /** 假馬達：下指令後多久才開始動。 */
    private static final long FAKE_MOTOR_DELAY_MS = 80L;

    private FakeKebbi() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : ScriptServer.DEFAULT_PORT;
        final long[] playStartNanos = {0L};

        DanceScriptPlayer.MotorSink sink = cmd -> {
            long ms = (System.nanoTime() - playStartNanos[0]) / 1_000_000L;
            System.out.println(String.format(Locale.US, "  [%6dms] %s", ms, cmd.describe()));
            return true;
        };
        DanceScriptPlayer.FaceSink faceSink = new DanceScriptPlayer.FaceSink() {
            @Override
            public boolean show(String face) {
                long ms = (System.nanoTime() - playStartNanos[0]) / 1_000_000L;
                System.out.println(String.format(Locale.US, "  [%6dms] FACE %s", ms,
                        face.isEmpty() ? "(default)" : face));
                return true;
            }

            @Override
            public void reset() {
                System.out.println("  FACE reset");
            }
        };
        DanceScriptPlayer player = new DanceScriptPlayer(sink, faceSink);
        DanceScriptPlayer.Listener listener = new DanceScriptPlayer.Listener() {
            @Override
            public void onStep(int index, int total, DanceScript.Step step) {
            }

            @Override
            public void onFinished(boolean completed, String message) {
                System.out.println(message);
            }
        };

        final DanceScript[] loaded = {null};
        ScriptServer server = new ScriptServer(port, new ScriptServer.Handler() {
            private String play(long startAtMs) {
                if (loaded[0] == null) {
                    if (startAtMs != ScriptServer.START_NOW) {
                        throw new IllegalStateException("No dance script loaded");
                    }
                    return "No dance script loaded";
                }
                long playerStart = startAtMs == ScriptServer.START_NOW
                        ? DanceScriptPlayer.START_NOW : startAtMs;
                if (!player.play(loaded[0], true, listener, playerStart)) {
                    if (player.isPlaying() && startAtMs == ScriptServer.START_NOW) {
                        return "Already playing; stop first";
                    }
                    throw new IllegalStateException(player.isPlaying() ? "Already playing; stop first"
                            : "Script not started: start time out of range");
                }
                // 印出來的時間 = 影片時間（和 dance_script.json 的 t 一樣）
                playStartNanos[0] = player.getTimelineZeroMs() * 1_000_000L;
                return "Playing script: " + loaded[0].describe();
            }

            @Override
            public String onScript(DanceScript script, boolean play, long startAtMs) {
                loaded[0] = script;
                String message = "Received " + script.describe();
                System.out.println(message);
                return play ? message + "\n" + play(startAtMs) : message;
            }

            @Override
            public String onPlay(long startAtMs) {
                return play(startAtMs);
            }

            @Override
            public String onMotorTest(int motorId, float degree, float speedDegPerSec) {
                float speed = Math.max(1f, speedDegPerSec);
                long expectedMs = (long) (Math.abs(degree) / speed * 1000f);
                return String.format(Locale.US,
                        "{\"ok\": true, \"fake\": true, \"motor\": %d, \"targetDeg\": %.1f, \"speed\": %.1f,"
                                + " \"startDeg\": 0.0, \"commandMs\": 1, \"firstMoveMs\": %d,"
                                + " \"reachMs\": %d, \"expectedMs\": %d, \"samples\": []}",
                        motorId, degree, speed, FAKE_MOTOR_DELAY_MS,
                        FAKE_MOTOR_DELAY_MS + expectedMs, expectedMs);
            }

            @Override
            public String onStop() {
                player.stop();
                return "Stopped";
            }

            @Override
            public String onHome() {
                player.stop();
                for (int motorId : new int[]{1, 2, 4, 6, 8, 10}) {
                    sink.send(new RobotCommand(motorId, 0f, 45f));
                }
                return "Homing";
            }

            @Override
            public String statusJson() {
                long now = ScriptServer.nowMs();
                long zero = player.getTimelineZeroMs();
                boolean hasTimeline = zero != DanceScriptPlayer.NO_TIMELINE;
                return "{\"ok\": true, \"robotReady\": true, \"playing\": " + player.isPlaying()
                        + ", \"kebbiMs\": " + now
                        + ", \"timelineZeroMs\": " + (hasTimeline ? String.valueOf(zero) : "null")
                        + ", \"positionMs\": " + (hasTimeline ? String.valueOf(now - zero) : "null")
                        + ", \"script\": " + (loaded[0] == null ? "null"
                        : ScriptServer.quote(loaded[0].describe())) + "}";
            }
        });
        server.start();
        try {
            new DiscoveryResponder(DiscoveryResponder.DEFAULT_PORT, "FakeKebbi", server.getPort()).start();
        } catch (IOException e) {
            System.out.println("Discovery disabled: " + e.getMessage());
        }
        System.out.println("FakeKebbi listening on port " + server.getPort()
                + "  (IPs: " + ScriptServer.localIpv4Addresses() + ")");
        Thread.currentThread().join();
    }
}
