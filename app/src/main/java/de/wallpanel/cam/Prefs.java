package de.wallpanel.cam;

import android.content.Context;
import android.content.SharedPreferences;

/** MQTT broker and stream frame rate. The password is entered on the device and is not stored here. */
public final class Prefs {
    private static final String FILE = "cam";
    public static final String DEFAULT_HOST = "";
    public static final int DEFAULT_PORT = 1883;
    public static final String DEFAULT_USER = "";
    public static final String DEFAULT_PASS = "";
    public static final int DEFAULT_FPS = 20;

    private Prefs() {}

    public static int fps(int value) {
        return value == 5 || value == 10 || value == 15 || value == 20 ? value : DEFAULT_FPS;
    }

    public static final class Config {
        public final String host;
        public final int port;
        public final String user;
        public final String password;
        public final int fps;

        public Config(String host, int port, String user, String password, int fps) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.fps = Prefs.fps(fps);
        }
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Config load(Context context) {
        SharedPreferences p = sp(context);
        return new Config(
                p.getString("host", DEFAULT_HOST),
                p.getInt("port", DEFAULT_PORT),
                p.getString("user", DEFAULT_USER),
                p.getString("pass", DEFAULT_PASS),
                p.getInt("fps", DEFAULT_FPS));
    }

    public static void save(Context context, Config config) {
        sp(context).edit()
                .putString("host", config.host.trim())
                .putInt("port", config.port)
                .putString("user", config.user.trim())
                .putString("pass", config.password)
                .putInt("fps", config.fps)
                .commit();
    }
}
