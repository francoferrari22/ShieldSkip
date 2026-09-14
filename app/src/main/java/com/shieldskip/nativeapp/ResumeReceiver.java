package com.shieldskip.nativeapp;
import android.content.*; import android.os.*;
public class ResumeReceiver extends BroadcastReceiver { @Override public void onReceive(Context c, Intent i){ if(Prefs.pausedUntil(c)>0 && System.currentTimeMillis()>=Prefs.pausedUntil(c)){ Prefs.pausedUntil(c,0); Intent s=new Intent(c,ShieldVpnService.class); if(Build.VERSION.SDK_INT>=26)c.startForegroundService(s); else c.startService(s); } } }
