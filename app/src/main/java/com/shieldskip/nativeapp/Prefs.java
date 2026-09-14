package com.shieldskip.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    static final String NAME = "shieldskip";
    static SharedPreferences p(Context c){ return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    static boolean active(Context c){ return p(c).getBoolean("active", false); }
    static void active(Context c, boolean v){ p(c).edit().putBoolean("active",v).apply(); }
    static long pausedUntil(Context c){ return p(c).getLong("pausedUntil",0); }
    static void pausedUntil(Context c,long v){ p(c).edit().putLong("pausedUntil",v).apply(); }
    static Set<String> apps(Context c){ return new HashSet<>(p(c).getStringSet("apps", new HashSet<>())); }
    static void apps(Context c, Set<String> s){ p(c).edit().putStringSet("apps", new HashSet<>(s)).apply(); }
    static long blocked(Context c){ return p(c).getLong("blocked",0); }
    static void blocked(Context c,long v){ p(c).edit().putLong("blocked",v).apply(); }
    static long bytes(Context c){ return p(c).getLong("bytes",0); }
    static void bytes(Context c,long v){ p(c).edit().putLong("bytes",v).apply(); }
    static long started(Context c){ return p(c).getLong("started",System.currentTimeMillis()); }
    static void started(Context c,long v){ p(c).edit().putLong("started",v).apply(); }

    static boolean aggressive(Context c){ return p(c).getBoolean("aggressive", true); }
    static void aggressive(Context c, boolean v){ p(c).edit().putBoolean("aggressive",v).apply(); }
    static boolean autoClose(Context c){ return p(c).getBoolean("autoClose", true); }
    static void autoClose(Context c, boolean v){ p(c).edit().putBoolean("autoClose",v).apply(); }
    static boolean turbo(Context c){ return p(c).getBoolean("turbo", true); }
    static void turbo(Context c, boolean v){ p(c).edit().putBoolean("turbo",v).apply(); }
    static boolean fastScan(Context c){ return p(c).getBoolean("fastScan", true); }
    static void fastScan(Context c, boolean v){ p(c).edit().putBoolean("fastScan",v).apply(); }
    static String theme(Context c){ return p(c).getString("theme", "system"); }
    static void theme(Context c, String v){ p(c).edit().putString("theme",v).apply(); }
    static String accent(Context c){ return p(c).getString("accent", "emerald"); }
    static void accent(Context c, String v){ p(c).edit().putString("accent",v).apply(); }
}
