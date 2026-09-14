package com.shieldskip.nativeapp;
import android.content.*;
public class ResumeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Prefs.pausedUntil(c) > 0 && System.currentTimeMillis() >= Prefs.pausedUntil(c)) {
            Prefs.pausedUntil(c, 0);
            Prefs.active(c, true);
            c.sendBroadcast(new Intent(MainActivity.ACTION_REFRESH).setPackage(c.getPackageName()));
        }
    }
}
