package com.example.myapplication.pc;

import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.RobotCommand;
import com.example.myapplication.net.ScriptServer;
import com.example.myapplication.robot.DanceScriptPlayer;

import java.io.IOException;
import java.util.Locale;

/**
 * 在電腦上假裝成 Kebbi：跑和 App 完全相同的 ScriptServer + DanceScriptPlayer，
 * 只是馬達指令改成印在終端機上。
 *
 * <p>用途：沒有機器人時，驗證 send_script.py → Wi-Fi → 播放時間軸整條路是通的。
 *
 * <pre>
 * java -cp pc/build/classes com.example.myapplication.pc.FakeKebbi [port]
 * python send_script.py 127.0.0.1 out/xxx/dance_script.json --play
 * </pre>
 */
public final class FakeKebbi {

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
        DanceScriptPlayer player = new DanceScriptPlayer(sink);
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
            private String play() {
                if (loaded[0] == null) {
                    return "No dance script loaded";
                }
                playStartNanos[0] = System.nanoTime();
                return player.play(loaded[0], true, listener)
                        ? "Playing script: " + loaded[0].describe()
                        : "Already playing; stop first";
            }

            @Override
            public String onScript(DanceScript script, boolean play) {
                loaded[0] = script;
                String message = "Received " + script.describe();
                System.out.println(message);
                return play ? message + "\n" + play() : message;
            }

            @Override
            public String onPlay() {
                return play();
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
                return "{\"ok\": true, \"robotReady\": true, \"playing\": " + player.isPlaying()
                        + ", \"script\": " + (loaded[0] == null ? "null"
                        : ScriptServer.quote(loaded[0].describe())) + "}";
            }
        });
        server.start();
        System.out.println("FakeKebbi listening on port " + server.getPort()
                + "  (IPs: " + ScriptServer.localIpv4Addresses() + ")");
        Thread.currentThread().join();
    }
}
