package com.shieldskip.nativeapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import java.util.*;

/**
 * SmartSkip engine. It uses Accessibility only: no VPN and no traffic interception.
 * Strategy: explicit skip -> close/dismiss -> playback-speed controls -> seek/forward.
 */
public class SmartSkipAccessibilityService extends AccessibilityService {
    private long lastActionAt = 0L;
    private String lastKey = "";
    private long turboMenuAt = 0L;
    private final Handler handler = new Handler();
    private final Runnable scanner = new Runnable() {
        @Override public void run() {
            if (Prefs.active(SmartSkipAccessibilityService.this)) scanCurrentWindow();
            handler.postDelayed(this, Prefs.fastScan(SmartSkipAccessibilityService.this) ? 260L : 700L);
        }
    };

    private static final String[] SKIP = {
        "skip ad", "skip ads", "skip advertisement", "skip advert", "skip video ad", "skip this ad",
        "saltar anuncio", "saltar anuncios", "omitir anuncio", "omitir anuncios",
        "saltar publicidad", "omitir publicidad", "saltar anuncio ahora", "skip this advertisement"
    };
    private static final String[] CLOSE = {
        "close ad", "close advertisement", "close advert", "cerrar anuncio", "cerrar publicidad",
        "dismiss ad", "dismiss advertisement", "dismiss advert", "dismiss advertisement"
    };
    private static final String[] AD_MARKERS = {
        "advertisement", "advertising", "sponsored", "patrocinado", "publicidad", "anuncio", "anuncios",
        "ad ·", "ads", "advert", "publicidad pagada", "contenido patrocinado"
    };
    private static final String[] SPEED_MENU = {
        "playback speed", "velocidad de reproducción", "velocidad de reproduccion", "reproduction speed"
    };
    private static final String[] FORWARD = {
        "seek forward", "forward 10 seconds", "forward 15 seconds", "forward 30 seconds",
        "adelantar 10 segundos", "adelantar 15 segundos", "adelantar 30 segundos", "avanzar 10 segundos"
    };

    @Override public void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                    AccessibilityEvent.TYPE_VIEW_CLICKED |
                    AccessibilityEvent.TYPE_VIEW_FOCUSED |
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 50;
            setServiceInfo(info);
        }
        Prefs.active(this, true);
        handler.removeCallbacks(scanner);
        handler.post(scanner);
        sendState();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!Prefs.active(this) || event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (pkg.equals(getPackageName()) || !isProtectedPackage(pkg)) return;
        scanCurrentWindow();
    }

    private void scanCurrentWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = collect(root);
            boolean adContext = hasAdContext(nodes);
            AccessibilityNodeInfo target = null;

            // 1) Highest confidence: explicit skip.
            target = bestTextAction(nodes, SKIP);
            if (target == null && Prefs.autoClose(this)) target = bestTextAction(nodes, CLOSE);

            // 2) X / close icon with an advertisement context.
            if (target == null && adContext && Prefs.autoClose(this)) target = bestCloseIcon(nodes, root);

            // 3) If no skip/close exists, try an exposed playback-speed menu.
            if (target == null && adContext && Prefs.turbo(this)) {
                AccessibilityNodeInfo speedChoice = bestSpeedChoice(nodes);
                if (speedChoice != null) target = speedChoice;
                else if (System.currentTimeMillis() - turboMenuAt > 1800L) {
                    AccessibilityNodeInfo menu = bestTextAction(nodes, SPEED_MENU);
                    if (menu != null) { turboMenuAt = System.currentTimeMillis(); target = menu; }
                }
            }

            // 4) Last-resort player control: a visible seek-forward button, only in ad context.
            if (target == null && adContext && Prefs.turbo(this) && Prefs.aggressive(this)) {
                target = bestTextAction(nodes, FORWARD);
            }

            if (target != null) performSmartClick(target);
            recycleExcept(nodes, target);
        } finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
    }

    private boolean isProtectedPackage(String pkg) {
        Set<String> apps = Prefs.apps(this);
        return apps.isEmpty() || apps.contains(pkg);
    }

    private List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            out.add(n);
            for (int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.addLast(c);
            }
        }
        return out;
    }

    private boolean hasAdContext(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if (matches(nodeText(n), AD_MARKERS)) return true;
            String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            if (id.contains("ad_") || id.startsWith("ad") || id.contains("advert") || id.contains("sponsor")) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo bestTextAction(List<AccessibilityNodeInfo> nodes, String[] phrases) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : nodes) {
            String text = nodeText(n);
            if (!matches(text, phrases) || !isSafeActionTarget(n)) continue;
            int score = text.length() < 80 ? 10 : 2;
            if (n.isClickable()) score += 4;
            if (n.isVisibleToUser()) score += 5;
            if (score > bestScore) { best = n; bestScore = score; }
        }
        return best;
    }

    private AccessibilityNodeInfo bestCloseIcon(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : nodes) {
            String text = nodeText(n);
            String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String cls = safe(n.getClassName()).toLowerCase(Locale.ROOT);
            boolean closeLike = text.equals("x") || text.equals("×") || text.contains("close") || text.contains("cerrar") ||
                    text.contains("dismiss") || id.contains("close") || id.contains("dismiss") || id.contains("cancel") ||
                    id.contains("ad_close") || id.endsWith("_x") || cls.contains("close");
            if (!closeLike || !isSafeActionTarget(n) || !n.isVisibleToUser()) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            boolean small = r.width() > 0 && r.height() > 0 && r.width() <= w * .30f && r.height() <= h * .20f;
            boolean edge = r.centerX() > w * .55f || r.top < h * .45f;
            if (!small || !edge) continue;
            int score = 8 + (r.centerX() > w*.65f ? 5 : 0) + (r.top < h*.30f ? 4 : 0);
            if (score > bestScore) { best = n; bestScore = score; }
        }
        return best;
    }

    private AccessibilityNodeInfo bestSpeedChoice(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestX = 0;
        for (AccessibilityNodeInfo n : nodes) {
            String s = nodeText(n).replace(" ", "").toLowerCase(Locale.ROOT);
            int x = speedValue(s);
            if (x > bestX && x <= 20 && isSafeActionTarget(n) && n.isVisibleToUser()) { best = n; bestX = x; }
        }
        return best;
    }

    private int speedValue(String s) {
        String[] values = {"20x","16x","12x","10x","8x","6x","4x","3x","2x"};
        for (String v : values) if (s.contains(v)) return Integer.parseInt(v.substring(0,v.length()-1));
        return 0;
    }

    private boolean matches(String s, String[] phrases) {
        for (String p : phrases) if (s.equals(p) || s.contains(p)) return true;
        return false;
    }

    private boolean isSafeActionTarget(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (!n.isVisibleToUser()) return false;
        if (n.isClickable() || n.isFocusable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList())
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        return false;
    }

    private void performSmartClick(AccessibilityNodeInfo n) {
        long now = System.currentTimeMillis();
        String key = buildKey(n);
        if (now - lastActionAt < 650L && key.equals(lastKey)) return;
        boolean ok = clickNode(n);
        if (ok) {
            lastActionAt = now; lastKey = key;
            Prefs.blocked(this, Prefs.blocked(this) + 1);
            Prefs.bytes(this, Prefs.bytes(this) + 2048);
            sendState();
        }
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo p = n.getParent();
        if (p != null) {
            boolean ok = p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            try { p.recycle(); } catch (Exception ignored) {}
            if (ok) return true;
        }
        Rect r = new Rect(); n.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) {
            Path path = new Path(); path.moveTo(r.centerX(), r.centerY());
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 70);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private String nodeText(AccessibilityNodeInfo n) {
        String text = safe(n.getText()).toLowerCase(Locale.ROOT);
        String desc = safe(n.getContentDescription()).toLowerCase(Locale.ROOT);
        return (text + " " + desc).trim();
    }

    private String buildKey(AccessibilityNodeInfo n) {
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return safe(n.getViewIdResourceName()) + "|" + nodeText(n) + "|" + r.toShortString();
    }

    private void recycleExcept(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo keep) {
        for (AccessibilityNodeInfo n : nodes) {
            if (n == keep) continue;
            try { n.recycle(); } catch (Exception ignored) {}
        }
    }

    private String safe(CharSequence s) { return s == null ? "" : s.toString(); }

    private void sendState() {
        Intent i = new Intent(MainActivity.ACTION_REFRESH); i.setPackage(getPackageName()); sendBroadcast(i);
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() { handler.removeCallbacks(scanner); Prefs.active(this, false); sendState(); super.onDestroy(); }
}
