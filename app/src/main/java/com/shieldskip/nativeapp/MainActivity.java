package com.shieldskip.nativeapp;

import android.Manifest; import android.app.*; import android.content.*; import android.content.pm.*; import android.net.VpnService; import android.os.*; import android.provider.Settings; import android.webkit.*; import android.view.*; import org.json.*; import java.util.*;

public class MainActivity extends Activity {
    static final int VPN_REQ=1001, NOTIF_REQ=1002; WebView web; Handler handler=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(android.graphics.Color.rgb(15,20,28)); getWindow().setNavigationBarColor(android.graphics.Color.rgb(15,20,28));
        web=new WebView(this); web.setBackgroundColor(android.graphics.Color.rgb(15,20,28)); web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true); web.setOverScrollMode(View.OVER_SCROLL_NEVER); web.addJavascriptInterface(new Bridge(),"Android"); web.loadUrl("file:///android_asset/index.html");
        setContentView(web); if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIF_REQ); }
    void requestVpn(){ Intent i=VpnService.prepare(this); if(i!=null) startActivityForResult(i,VPN_REQ); else startVpn(); }
    void startVpn(){ Intent i=new Intent(this,ShieldVpnService.class); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); refresh(); }
    void stopVpn(){ stopService(new Intent(this,ShieldVpnService.class)); Prefs.active(this,false); refresh(); }
    @Override protected void onActivityResult(int r,int c,Intent d){ super.onActivityResult(r,c,d); if(r==VPN_REQ && c==RESULT_OK) startVpn(); }
    void refresh(){ if(web!=null) web.evaluateJavascript("window.nativeRefresh && window.nativeRefresh("+getStateJson()+")",null); }
    String getStateJson(){ try{ JSONObject o=new JSONObject(); o.put("active",Prefs.active(this)); o.put("pausedUntil",Prefs.pausedUntil(this)); o.put("blocked",Prefs.blocked(this)); o.put("bytes",Prefs.bytes(this)); o.put("apps",Prefs.apps(this).size()); return o.toString(); }catch(Exception e){return "{}";} }

    public final class Bridge {
        @JavascriptInterface public void toggle(){ if(Prefs.active(MainActivity.this)) stopVpn(); else requestVpn(); }
        @JavascriptInterface public void pause15(){ Prefs.pausedUntil(MainActivity.this,System.currentTimeMillis()+15*60*1000L); stopVpn(); AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE); Intent i=new Intent(MainActivity.this,ResumeReceiver.class); PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,77,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT); am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Prefs.pausedUntil(MainActivity.this),pi); refresh(); }
        @JavascriptInterface public String state(){ return getStateJson(); }
        @JavascriptInterface public String apps(){ try{ JSONArray a=new JSONArray(); PackageManager pm=getPackageManager(); List<ApplicationInfo> list=pm.getInstalledApplications(PackageManager.GET_META_DATA); list.sort((x,y)->pm.getApplicationLabel(x).toString().compareToIgnoreCase(pm.getApplicationLabel(y).toString())); Set<String> chosen=Prefs.apps(MainActivity.this); for(ApplicationInfo ai:list){ if(ai.packageName.equals(getPackageName())) continue; Intent launch=pm.getLaunchIntentForPackage(ai.packageName); if(launch==null) continue; JSONObject o=new JSONObject(); o.put("label",pm.getApplicationLabel(ai).toString()); o.put("pkg",ai.packageName); o.put("selected",chosen.contains(ai.packageName)); a.put(o); } return a.toString(); }catch(Exception e){return "[]";} }
        @JavascriptInterface public void setApps(String json){ try{ JSONArray a=new JSONArray(json); Set<String> s=new HashSet<>(); for(int i=0;i<a.length();i++) s.add(a.getString(i)); Prefs.apps(MainActivity.this,s); if(Prefs.active(MainActivity.this)){ stopVpn(); requestVpn(); } }catch(Exception ignored){} }
        @JavascriptInterface public void openAndroidVpnSettings(){ try{ startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }catch(Exception ignored){} }
        @JavascriptInterface public void reset(){ Prefs.blocked(MainActivity.this,0); Prefs.bytes(MainActivity.this,0); refresh(); }
    }
}
