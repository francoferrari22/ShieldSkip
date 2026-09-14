package com.shieldskip.nativeapp;
import android.content.*;
import android.net.VpnService;
import android.os.Build;
public class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c, Intent i){ if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Prefs.active(c)){ Intent in=new Intent(c,ShieldVpnService.class); if(Build.VERSION.SDK_INT>=26)c.startForegroundService(in);else c.startService(in); } }
}
