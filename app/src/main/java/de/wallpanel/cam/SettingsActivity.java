package de.wallpanel.cam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

/** MQTT broker form. Opened from the camera notification, not from the launcher. */
public final class SettingsActivity extends Activity {
    private EditText host;
    private EditText port;
    private EditText user;
    private EditText password;
    private RadioGroup fps;
    private TextView status;
    private TextView error;
    private CamService service;

    private final CamService.Listener listener = (statusText, errorText) -> runOnUiThread(() -> {
        status.setText(statusText);
        error.setText(errorText);
        error.setVisibility(errorText == null || errorText.isEmpty() ? View.GONE : View.VISIBLE);
    });

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CamService.LocalBinder) binder).service();
            service.addListener(listener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        user = findViewById(R.id.user);
        password = findViewById(R.id.password);
        fps = findViewById(R.id.fps);
        status = findViewById(R.id.status);
        error = findViewById(R.id.error);
        fill(Prefs.load(this));
        findViewById(R.id.save).setOnClickListener(v -> saveAndReconnect());
    }

    @Override
    protected void onStart() {
        super.onStart();
        startForegroundService(new Intent(this, CamService.class));
        bindService(new Intent(this, CamService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.removeListener(listener);
            service = null;
        }
        unbindService(connection);
        super.onStop();
    }

    private void fill(Prefs.Config config) {
        host.setText(config.host);
        port.setText(Integer.toString(config.port));
        user.setText(config.user);
        password.setText(config.password);
        fps.check(fpsId(config.fps));
    }

    private static int fpsId(int value) {
        if (value == 5) {
            return R.id.fps_5;
        }
        if (value == 10) {
            return R.id.fps_10;
        }
        if (value == 15) {
            return R.id.fps_15;
        }
        return R.id.fps_20;
    }

    private int selectedFps() {
        int id = fps.getCheckedRadioButtonId();
        if (id == R.id.fps_5) {
            return 5;
        }
        if (id == R.id.fps_10) {
            return 10;
        }
        if (id == R.id.fps_15) {
            return 15;
        }
        return 20;
    }

    private void saveAndReconnect() {
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            error.setText("Port ist keine Zahl");
            error.setVisibility(View.VISIBLE);
            return;
        }
        if (portNumber < 1 || portNumber > 65535) {
            error.setText("Port muss zwischen 1 und 65535 liegen");
            error.setVisibility(View.VISIBLE);
            return;
        }
        String hostText = host.getText().toString().trim();
        if (hostText.isEmpty()) {
            error.setText("Host fehlt");
            error.setVisibility(View.VISIBLE);
            return;
        }
        Prefs.save(this, new Prefs.Config(
                hostText,
                portNumber,
                user.getText().toString(),
                password.getText().toString(),
                selectedFps()));
        Intent serviceIntent = new Intent(this, CamService.class);
        serviceIntent.setAction(CamService.ACTION_RECONNECT);
        startForegroundService(serviceIntent);
    }
}
