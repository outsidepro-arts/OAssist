package ru.outsideproarts.oassist.vis;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

/** Runs without instrumentation in the test package's own process and UID. */
public class SessionBindProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String permission = "android.permission.BIND_VOICE_INTERACTION";
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        Intent intent = new Intent().setClassName("ru.outsideproarts.oassist",
                "ru.outsideproarts.oassist.vis.OAssistVoiceInteractionSessionService");
        String prefix = "uid=" + Process.myUid() + " permissionResult=" + checkSelfPermission(permission);
        try {
            boolean bound = bindService(intent, connection, BIND_AUTO_CREATE);
            Log.i("SessionBindProbe", prefix + " bindResult=" + bound);
            if (bound) unbindService(connection);
        } catch (SecurityException exception) {
            Log.i("SessionBindProbe", prefix + " SecurityException=" + exception.getMessage());
        } finally {
            finish();
        }
    }
}
