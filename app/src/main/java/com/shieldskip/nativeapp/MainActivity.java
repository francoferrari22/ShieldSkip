package com.shieldskip.nativeapp;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.util.*;

public class MainActivity extends Activity {
    public static final String ACTION_REFRESH = "com.shieldskip.nativeapp.REFRESH";
    WebView web;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(15,20,28));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(15,20,28));
        web = new WebView(this);
        web.setBackgroundColor(android.graphics.Color.rgb(15,20,28));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        registerReceiver(receiver, new IntentFilter(ACTION_REFRESH), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = getPackageName() + "/" + SmartSkipAccessibilityService.class.getName();
        for (String s : enabled.split(":")) if (s.equalsIgnoreCase(me)) return true;
        return false;
    }

    void openAccessibilitySettings() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
    }

    void toggle() {
        if (!accessibilityEnabled()) {
            openAccessibilitySettings();
            return;
        }
        Prefs.active(this, !Prefs.active(this));
        refresh();
    }

    void refresh() {
        if (web != null) web.evaluateJavascript("window.nativeRefresh && window.nativeRefresh(" + getStateJson() + ")", null);
    }

    String getStateJson() {
        try {
            JSONObject o = new JSONObject();
            boolean enabled = accessibilityEnabled();
            o.put("active", enabled && Prefs.active(this));
            o.put("enabled", enabled);
            o.put("blocked", Prefs.blocked(this));
            o.put("bytes", Prefs.bytes(this));
            o.put("apps", Prefs.apps(this).size());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    public final class Bridge {
        @JavascriptInterface public void toggle() { MainActivity.this.toggle(); }
        @JavascriptInterface public void pause15() {
            Prefs.active(MainActivity.this, false);
            Prefs.pausedUntil(MainActivity.this, System.currentTimeMillis() + 15 * 60 * 1000L);
            android.app.AlarmManager am = (android.app.AlarmManager)getSystemService(ALARM_SERVICE);
            Intent i = new Intent(MainActivity.this, ResumeReceiver.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(MainActivity.this, 77, i, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, Prefs.pausedUntil(MainActivity.this), pi);
            refresh();
        }
        @JavascriptInterface public void openAccessibilitySettings() { MainActivity.this.openAccessibilitySettings(); }
        @JavascriptInterface public String state() { return getStateJson(); }
        @JavascriptInterface public String apps() {
            try {
                JSONArray a = new JSONArray();
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> list = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                list.sort((x,y)->pm.getApplicationLabel(x).toString().compareToIgnoreCase(pm.getApplicationLabel(y).toString()));
                Set<String> chosen = Prefs.apps(MainActivity.this);
                for (ApplicationInfo ai : list) {
                    if (ai.packageName.equals(getPackageName())) continue;
                    Intent launch = pm.getLaunchIntentForPackage(ai.packageName);
                    if (launch == null) continue;
                    JSONObject o = new JSONObject();
                    o.put("label", pm.getApplicationLabel(ai).toString());
                    o.put("pkg", ai.packageName);
                    o.put("selected", chosen.contains(ai.packageName));
                    a.put(o);
                }
                return a.toString();
            } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public void setApps(String json) {
            try {
                JSONArray a = new JSONArray(json);
                Set<String> s = new HashSet<>();
                for (int i=0;i<a.length();i++) s.add(a.getString(i));
                Prefs.apps(MainActivity.this, s);
                refresh();
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void reset() { Prefs.blocked(MainActivity.this,0); Prefs.bytes(MainActivity.this,0); refresh(); }
    }
}
