package com.shieldskip.nativeapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

public class SmartSkipAccessibilityService extends AccessibilityService {
    private static final long CLICK_COOLDOWN_MS = 1200L;
    private long lastClickAt = 0L;
    private String lastKey = "";

    private static final String[] SKIP_PHRASES = {
        "skip ad", "skip ads", "skip advertisement", "skip advert",
        "saltar anuncio", "saltar anuncios", "omitir anuncio", "omitir anuncios",
        "saltar publicidad", "omitir publicidad", "saltar anuncio ahora",
        "skip", "omitir", "saltar"
    };

    private static final String[] CLOSE_PHRASES = {
        "close ad", "close advertisement", "cerrar anuncio", "cerrar publicidad"
    };

    @Override public void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                    AccessibilityEvent.TYPE_VIEW_CLICKED |
                    AccessibilityEvent.TYPE_VIEW_FOCUSED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        Prefs.active(this, true);
        sendState();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!Prefs.active(this)) return;
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        if (!isProtectedPackage(pkg)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo target = findSkipTarget(root);
            if (target != null) {
                String key = pkg + "|" + safe(target.getText()) + "|" + safe(target.getContentDescription());
                long now = System.currentTimeMillis();
                if (now - lastClickAt >= CLICK_COOLDOWN_MS && !key.equals(lastKey)) {
                    boolean ok = clickNode(target);
                    if (ok) {
                        lastClickAt = now;
                        lastKey = key;
                        Prefs.blocked(this, Prefs.blocked(this) + 1);
                        Prefs.bytes(this, Prefs.bytes(this) + 1024);
                        sendState();
                    }
                }
            }
        } finally {
            root.recycle();
        }
    }

    private boolean isProtectedPackage(String pkg) {
        Set<String> apps = Prefs.apps(this);
        return apps.isEmpty() || apps.contains(pkg);
    }

    private AccessibilityNodeInfo findSkipTarget(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String text = safe(n.getText()).toLowerCase(Locale.ROOT);
            String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
            String joined = (text + " " + desc).trim();

            if (matches(joined, SKIP_PHRASES) || matches(joined, CLOSE_PHRASES)) {
                if (isSafeActionTarget(n)) return n;
                AccessibilityNodeInfo p = n.getParent();
                if (p != null && isSafeActionTarget(p)) return p;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return null;
    }

    private boolean matches(String s, String[] phrases) {
        for (String p : phrases) {
            if (s.equals(p) || s.contains(p)) return true;
        }
        return false;
    }

    private boolean isSafeActionTarget(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isClickable() || n.isFocusable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo p = n.getParent();
        if (p != null) {
            boolean ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            p.recycle();
            if (ok) return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(r.centerX(), r.centerY());
            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80);
            android.accessibilityservice.GestureDescription gesture =
                    new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private String safe(CharSequence s) { return s == null ? "" : s.toString(); }

    private void sendState() {
        Intent i = new Intent(MainActivity.ACTION_REFRESH);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        Prefs.active(this, false);
        sendState();
        super.onDestroy();
    }
}
