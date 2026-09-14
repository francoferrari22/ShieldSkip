package com.shieldskip.nativeapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.*;
import java.util.concurrent.Executor;

/**
 * SmartSkip v4 engine.
 *
 * No VPN and no traffic interception. It combines:
 *  1) accessibility-node detection;
 *  2) screenshot OCR for ads that expose no accessibility text;
 *  3) fast-forward / playback controls when the ad player exposes them;
 *  4) a short Play Store return guard after an ad CTA is detected, so an
 *     accidental "Descargar ahora" / "Más info" cannot strand the user in Play Store.
 *
 * Important: Android does not provide a universal API to change another
 * application's private video playback rate. We only activate controls that
 * the target application exposes to Accessibility or that OCR can locate.
 */
public class SmartSkipAccessibilityService extends AccessibilityService {
    private static final String PLAY_STORE = "com.android.vending";

    private final Handler handler = new Handler();
    private final Executor mainExecutor = command -> handler.post(command);
    private TextRecognizer textRecognizer;

    private long lastActionAt = 0L;
    private String lastKey = "";
    private long lastScreenshotAt = 0L;
    private long lastAdDetectedAt = 0L;
    private long storeGuardUntil = 0L;
    private String lastAdPackage = "";
    private boolean ocrBusy = false;
    private int storeBacks = 0;

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
        "dismiss ad", "dismiss advertisement", "dismiss advert"
    };

    private static final String[] AD_MARKERS = {
        "advertisement", "advertising", "sponsored", "patrocinado", "publicidad", "anuncio", "anuncios",
        "ad ·", "ads", "advert", "publicidad pagada", "contenido patrocinado", "descargar ahora", "más info",
        "mas info", "learn more", "install now", "download now"
    };

    private static final String[] SPEED_MENU = {
        "playback speed", "velocidad de reproducción", "velocidad de reproduccion", "reproduction speed", "speed"
    };

    private static final String[] FORWARD = {
        "seek forward", "forward 10 seconds", "forward 15 seconds", "forward 30 seconds",
        "adelantar 10 segundos", "adelantar 15 segundos", "adelantar 30 segundos", "avanzar 10 segundos"
    };

    private static final String[] FAST_IDS = {
        "fast_forward", "fastforward", "forward", "seek_forward", "skip_forward", "advance",
        "ffwd", "ad_skip", "skip_button", "next_ad", "continue_ad"
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

        if (Build.VERSION.SDK_INT >= 30) {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }

        Prefs.active(this, true);
        handler.removeCallbacks(scanner);
        handler.post(scanner);
        sendState();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!Prefs.active(this) || event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();

        // If an ad CTA managed to launch Google Play, immediately return to the
        // protected app. The guard is only armed after an ad was detected.
        if (PLAY_STORE.equals(pkg)) {
            if (System.currentTimeMillis() < storeGuardUntil && !lastAdPackage.isEmpty()) {
                returnFromPlayStore();
            }
            return;
        }

        if (pkg.equals(getPackageName()) || !isProtectedPackage(pkg)) return;
        scanCurrentWindow();
    }

    private void scanCurrentWindow() {
        String pkg = getCurrentPackage();
        if (PLAY_STORE.equals(pkg)) {
            if (System.currentTimeMillis() < storeGuardUntil && !lastAdPackage.isEmpty()) returnFromPlayStore();
            return;
        }
        if (!isProtectedPackage(pkg) || pkg.equals(getPackageName())) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            maybeScreenshotScan(pkg, false);
            return;
        }

        try {
            List<AccessibilityNodeInfo> nodes = collect(root);
            boolean adContext = hasAdContext(nodes);
            if (adContext) markAd(pkg);

            AccessibilityNodeInfo target = null;

            // 1) Explicit skip has absolute priority.
            target = bestTextAction(nodes, SKIP);

            // 2) Explicit ad close / dismiss.
            if (target == null && Prefs.autoClose(this)) target = bestTextAction(nodes, CLOSE);

            // 3) Controls whose id/class/content-description indicate fast-forward/skip.
            if (target == null && adContext && Prefs.aggressive(this)) target = bestFastForward(nodes);

            // 4) Close X / dismiss icon, but only in ad context.
            if (target == null && adContext && Prefs.autoClose(this)) target = bestCloseIcon(nodes);

            // 5) Exposed playback speed menu and highest available speed.
            if (target == null && adContext && Prefs.turbo(this)) {
                AccessibilityNodeInfo speedChoice = bestSpeedChoice(nodes);
                if (speedChoice != null) target = speedChoice;
                else if (System.currentTimeMillis() - lastActionAt > 1200L) {
                    AccessibilityNodeInfo menu = bestTextAction(nodes, SPEED_MENU);
                    if (menu != null) target = menu;
                }
            }

            // 6) Last-resort seek-forward control.
            if (target == null && adContext && Prefs.turbo(this) && Prefs.aggressive(this)) {
                target = bestTextAction(nodes, FORWARD);
            }

            if (target != null) performSmartClick(target);

            // 7) OCR is the fallback for custom/canvas ads whose text is visible
            // to the user but absent from the accessibility tree.
            maybeScreenshotScan(pkg, adContext);
            recycleExcept(nodes, target);
        } finally {
            try { root.recycle(); } catch (Exception ignored) {}
        }
    }

    private String getCurrentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        String p = root.getPackageName().toString();
        try { root.recycle(); } catch (Exception ignored) {}
        return p;
    }

    private void markAd(String pkg) {
        lastAdDetectedAt = System.currentTimeMillis();
        lastAdPackage = pkg;
        storeGuardUntil = lastAdDetectedAt + 15000L;
        storeBacks = 0;
    }

    private void returnFromPlayStore() {
        if (storeBacks > 3) return;
        storeBacks++;
        performGlobalAction(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> {
            if (PLAY_STORE.equals(getCurrentPackage()) && System.currentTimeMillis() < storeGuardUntil) {
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        }, 180L);
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
            String cls = safe(n.getClassName()).toLowerCase(Locale.ROOT);
            if (id.contains("ad_") || id.startsWith("ad") || id.contains("advert") || id.contains("sponsor") ||
                    cls.contains("adview") || cls.contains("nativead") || cls.contains("rewardedad")) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo bestTextAction(List<AccessibilityNodeInfo> nodes, String[] phrases) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : nodes) {
            String text = nodeText(n);
            if (!matches(text, phrases) || !isSafeActionTarget(n)) continue;
            // Never choose ad CTAs that intentionally open an external store.
            if (isStoreCta(text)) continue;
            int score = text.length() < 80 ? 10 : 2;
            if (n.isClickable()) score += 4;
            if (n.isVisibleToUser()) score += 5;
            if (score > bestScore) { best = n; bestScore = score; }
        }
        return best;
    }

    private boolean isStoreCta(String text) {
        return text.contains("descargar ahora") || text.contains("download now") || text.contains("install now") ||
                text.equals("más info") || text.equals("mas info") || text.contains("learn more");
    }

    private AccessibilityNodeInfo bestFastForward(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int scoreBest = -1;
        for (AccessibilityNodeInfo n : nodes) {
            if (!isSafeActionTarget(n)) continue;
            String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String cls = safe(n.getClassName()).toLowerCase(Locale.ROOT);
            String text = nodeText(n);
            boolean match = matches(text, FORWARD);
            for (String k : FAST_IDS) if (id.contains(k) || cls.contains(k) || text.contains(k.replace('_',' '))) match = true;
            if (!match) continue;
            Rect r = new Rect(); n.getBoundsInScreen(r);
            int score = 8 + (n.isClickable() ? 4 : 0) + (r.top < getResources().getDisplayMetrics().heightPixels * .30f ? 3 : 0);
            if (score > scoreBest) { best = n; scoreBest = score; }
        }
        return best;
    }

    private AccessibilityNodeInfo bestCloseIcon(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo n : nodes) {
            String text = nodeText(n);
            String id = safe(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            String cls = safe(n.getClassName()).toLowerCase(Locale.ROOT);
            boolean closeLike = text.equals("x") || text.equals("×") || text.contains("close") || text.contains("cerrar") ||
                    text.contains("dismiss") || id.contains("close") || id.contains("dismiss") || id.contains("cancel") ||
                    id.contains("ad_close") || id.endsWith("_x") || cls.contains("close");
            if (!closeLike || isStoreCta(text) || !isSafeActionTarget(n) || !n.isVisibleToUser()) continue;
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
        String[] values = {"20x","16x","12x","10x","8x","6x","4x","3x","2x","1.5x"};
        for (String v : values) if (s.contains(v)) {
            try { return (int)Float.parseFloat(v.substring(0,v.length()-1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private boolean matches(String s, String[] phrases) {
        for (String p : phrases) if (s.equals(p) || s.contains(p)) return true;
        return false;
    }

    private boolean isSafeActionTarget(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser()) return false;
        if (n.isClickable() || n.isFocusable()) return true;
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList())
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) return true;
        return false;
    }

    private void performSmartClick(AccessibilityNodeInfo n) {
        long now = System.currentTimeMillis();
        String key = buildKey(n);
        if (now - lastActionAt < 450L && key.equals(lastKey)) return;
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
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 55);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        }
        return false;
    }

    private void maybeScreenshotScan(String pkg, boolean adContext) {
        if (Build.VERSION.SDK_INT < 30 || textRecognizer == null || ocrBusy) return;
        long now = System.currentTimeMillis();
        if (now - lastScreenshotAt < 1100L) return;
        if (!adContext && now - lastAdDetectedAt > 5000L) return;
        lastScreenshotAt = now;
        ocrBusy = true;
        takeScreenshot(0, mainExecutor, new TakeScreenshotCallback() {
            @Override public void onFailure(int errorCode) { ocrBusy = false; }
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap copy = null;
                HardwareBuffer hb = null;
                try {
                    hb = result.getHardwareBuffer();
                    Bitmap raw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (raw != null) copy = raw.copy(Bitmap.Config.ARGB_8888, false);
                    if (raw != null) raw.recycle();
                } catch (Throwable ignored) {
                } finally {
                    try { if (hb != null) hb.close(); } catch (Exception ignored) {}
                }
                if (copy == null) { ocrBusy = false; return; }
                final Bitmap finalCopy = copy;
                com.google.mlkit.vision.common.InputImage image = com.google.mlkit.vision.common.InputImage.fromBitmap(finalCopy, 0);
                textRecognizer.process(image)
                        .addOnSuccessListener(text -> {
                            try { handleOcr(pkg, text); } finally { try { finalCopy.recycle(); } catch (Exception ignored) {} ocrBusy = false; }
                        })
                        .addOnFailureListener(e -> { try { finalCopy.recycle(); } catch (Exception ignored) {} ocrBusy = false; });
            }
        });
    }

    private void handleOcr(String pkg, Text text) {
        String all = text.getText() == null ? "" : text.getText().toLowerCase(Locale.ROOT);
        boolean ad = containsAny(all, AD_MARKERS) || all.contains("anuncios") || all.contains("anuncio");
        if (ad) markAd(pkg);

        // OCR can see text drawn by a video/canvas even when Accessibility cannot.
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText() == null ? "" : line.getText().trim().toLowerCase(Locale.ROOT);
                Rect box = line.getBoundingBox();
                if (box == null) continue;

                if (containsAny(s, SKIP) || s.equals("skip") || s.equals("omitir") || s.equals("saltar")) {
                    tapRect(box); return;
                }

                if (ad && (s.equals("x") || s.equals("×") || s.contains("cerrar") || s.contains("close") || s.contains("dismiss"))) {
                    int w = getResources().getDisplayMetrics().widthPixels;
                    if (box.centerX() > w * .55f || box.top < getResources().getDisplayMetrics().heightPixels * .35f) {
                        tapRect(box); return;
                    }
                }

                // Some rewarded/interstitial players expose a visible >> control only
                // in the rendered frame. OCR may recognize it as >> or > >.
                if (ad && (s.contains(">>") || s.equals("> >") || s.equals(">> "))) {
                    tapRect(box); return;
                }
            }
        }
    }

    private boolean containsAny(String s, String[] phrases) {
        for (String p : phrases) if (s.contains(p)) return true;
        return false;
    }

    private void tapRect(Rect r) {
        if (r == null || r.width() <= 0 || r.height() <= 0) return;
        long now = System.currentTimeMillis();
        String key = "ocr|" + r.toShortString();
        if (now - lastActionAt < 450L && key.equals(lastKey)) return;
        Path path = new Path(); path.moveTo(r.centerX(), r.centerY());
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 55);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        if (dispatchGesture(gesture, null, null)) {
            lastActionAt = now; lastKey = key;
            Prefs.blocked(this, Prefs.blocked(this) + 1);
            Prefs.bytes(this, Prefs.bytes(this) + 2048);
            sendState();
        }
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

    @Override public void onDestroy() {
        handler.removeCallbacks(scanner);
        if (textRecognizer != null) {
            try { textRecognizer.close(); } catch (Exception ignored) {}
            textRecognizer = null;
        }
        Prefs.active(this, false);
        sendState();
        super.onDestroy();
    }
}
