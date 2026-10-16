package com.qingtiao.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local, event-driven detection with a finite launch retry budget. */
public final class SkipService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int MAX_NODES = 700;
    private static final long[] LAUNCH_PROBES = {180, 600, 1600, 3500, 6500, 10000};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ClickGuard clickGuard = new ClickGuard();
    private final Map<String, Long> pointFiredAt = new HashMap<>();
    private final List<Runnable> pageTasks = new ArrayList<>();
    private final Map<String, List<Rule>> rulesByPackage = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();
    private final Set<String> systemPackages = new HashSet<>();
    private SharedPreferences prefs;
    private PowerManager power;
    private KeyguardManager keyguard;
    private boolean enabled;
    private boolean broadSkip;
    private boolean genericClose;
    private String currentPackage = "";
    private String currentActivity = "";
    private int currentWindow = -1;
    private int generation;
    private long burstUntil;
    private long lastScanAt = -10000;
    private long pendingScanAt = Long.MAX_VALUE;
    private int emptyRootRetries;
    private int actionSequence;
    private int pendingCount;
    private boolean destroyed;

    private final Runnable scanTask = () -> {
        pendingScanAt = Long.MAX_VALUE;
        scanActiveWindow();
    };
    private final Runnable flushTask = this::flushCount;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        prefs = RuleStore.prefs(this);
        power = (PowerManager) getSystemService(POWER_SERVICE);
        keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Collections.addAll(systemPackages, getPackageName(), "android", "com.android.systemui",
                "com.android.settings", "com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.android.packageinstaller",
                "com.google.android.packageinstaller");
        ResolveInfo launcher = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (launcher != null && launcher.activityInfo != null) {
            systemPackages.add(launcher.activityInfo.packageName);
        }
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) systemPackages.add(ime.substring(0, ime.indexOf('/')));
        loadOptions();
        prefs.registerOnSharedPreferenceChangeListener(this);
        if (!prefs.getBoolean(RuleStore.KEY_SERVICE_SEEN, false)) {
            prefs.edit().putBoolean(RuleStore.KEY_SERVICE_SEEN, true).apply();
        }
        requestScan(120);
    }

    private void loadOptions() {
        enabled = prefs.getBoolean(RuleStore.KEY_ENABLED, true);
        broadSkip = prefs.getBoolean(RuleStore.KEY_BROAD_SKIP, true);
        genericClose = prefs.getBoolean(RuleStore.KEY_GENERIC_CLOSE, false);
        excluded.clear();
        String raw = prefs.getString(RuleStore.KEY_EXCLUDED, "");
        if (raw != null) Collections.addAll(excluded, raw.split("[,，\\s]+"));
        rulesByPackage.clear();
        for (Rule rule : RuleStore.load(prefs)) {
            if (rule.enabled) rulesByPackage.computeIfAbsent(rule.packageName,
                    key -> new ArrayList<>()).add(rule);
        }
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (RuleStore.KEY_ENABLED.equals(key) || RuleStore.KEY_BROAD_SKIP.equals(key)
                || RuleStore.KEY_GENERIC_CLOSE.equals(key) || RuleStore.KEY_EXCLUDED.equals(key)
                || RuleStore.KEY_RULES.equals(key)) {
            loadOptions();
            cancelWork();
            if (enabled) requestScan(0);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event == null || destroyed) return;
        if (!canRun()) { cancelWork(); return; }
        String pkg = string(event.getPackageName());
        int type = event.getEventType();
        if (!pkg.isEmpty() && blocked(pkg)) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                cancelWork();
                currentPackage = pkg;
                currentActivity = "";
                currentWindow = event.getWindowId();
                clickGuard.clearTargets();
                flushCount();
            }
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.isEmpty()) {
            String activity = string(event.getClassName());
            boolean changed = !pkg.equals(currentPackage) || event.getWindowId() != currentWindow
                    || !activity.equals(currentActivity);
            if (changed) {
                enterWindow(pkg, activity, event.getWindowId());
            }
            rememberPage(pkg, activity);
            if (changed) schedulePointRules();
        }
        // WINDOWS_CHANGED can legitimately omit packageName: read the active root in the scan.
        requestScan(0);
    }

    private boolean canRun() {
        return enabled && !destroyed && (power == null || power.isInteractive())
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private boolean blocked(String pkg) {
        return pkg.isEmpty() || systemPackages.contains(pkg) || excluded.contains(pkg);
    }

    private void enterWindow(String pkg, String activity, int windowId) {
        boolean differentWindow = !pkg.equals(currentPackage) || windowId != currentWindow;
        cancelWork();
        if (differentWindow) clickGuard.clearTargets();
        currentPackage = pkg;
        currentActivity = activity;
        currentWindow = windowId;
        emptyRootRetries = 0;
        burstUntil = SystemClock.uptimeMillis() + LAUNCH_PROBES[LAUNCH_PROBES.length - 1];
        int token = generation;
        for (long delay : LAUNCH_PROBES) {
            Runnable probe = () -> {
                if (token == generation && canRun()) requestScan(0);
            };
            pageTasks.add(probe);
            handler.postDelayed(probe, delay);
        }
    }

    private void rememberPage(String pkg, String activity) {
        if (!pkg.equals(prefs.getString(RuleStore.KEY_LAST_PACKAGE, ""))
                || !activity.equals(prefs.getString(RuleStore.KEY_LAST_CLASS, ""))) {
            prefs.edit().putString(RuleStore.KEY_LAST_PACKAGE, pkg)
                    .putString(RuleStore.KEY_LAST_CLASS, activity).apply();
        }
    }

    private void requestScan(long delay) {
        if (!canRun()) return;
        long now = SystemClock.uptimeMillis();
        long minInterval = now <= burstUntil ? 180 : 450;
        long due = Math.max(now + Math.max(0, delay), lastScanAt + minInterval);
        if (due >= pendingScanAt) return;
        handler.removeCallbacks(scanTask);
        pendingScanAt = due;
        handler.postAtTime(scanTask, due);
    }

    private void scanActiveWindow() {
        if (!canRun()) { cancelWork(); return; }
        lastScanAt = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = null;
        Candidate candidate = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) {
                if (emptyRootRetries++ < 2) requestScan(180);
                return;
            }
            emptyRootRetries = 0;
            String pkg = root.getPackageName().toString();
            if (blocked(pkg)) {
                cancelWork();
                currentPackage = pkg;
                currentActivity = "";
                currentWindow = root.getWindowId();
                flushCount();
                return;
            }
            if (!pkg.equals(currentPackage) || root.getWindowId() != currentWindow) {
                enterWindow(pkg, "", root.getWindowId());
            }
            WindowFrame frame = frame(root);
            if (frame.bounds.isEmpty()) return;
            candidate = findCandidate(root, frame.bounds, pkg);
            if (candidate == null) return;
            String targetKey = pkg + "|" + root.getWindowId() + "|" + candidate.key;
            long wait = clickGuard.waitMillis(targetKey, SystemClock.uptimeMillis());
            if (wait < 0) return;
            if (wait > 0) { requestScan(wait); return; }
            click(candidate, pkg, root.getWindowId(), frame, targetKey);
        } catch (IllegalStateException | SecurityException staleWindow) {
            // The remote hierarchy can disappear between any two accessibility calls.
            if (emptyRootRetries++ < 2) requestScan(180);
        } finally {
            if (candidate != null) recycle(candidate.node);
            recycle(root);
        }
    }

    private Candidate findCandidate(AccessibilityNodeInfo root, Rect window, String pkg) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        Candidate bestSpecific = null;
        Candidate bestBroad = null;
        Candidate bestClose = null;
        Candidate bestCustom = null;
        boolean adContext = false;
        boolean rewardContext = false;
        List<Rule> appRules = rulesByPackage.getOrDefault(pkg, Collections.emptyList());
        int enqueued = 1;
        long start = SystemClock.uptimeMillis();
        try {
            while (!queue.isEmpty() && SystemClock.uptimeMillis() - start <= 60) {
                AccessibilityNodeInfo node = queue.removeFirst();
                try {
                    // A container's flags do not reliably describe all of its descendants.
                    // Visit later children first: ad overlays are commonly appended last.
                    for (int i = node.getChildCount() - 1; i >= 0 && enqueued < MAX_NODES; i--) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) { queue.addLast(child); enqueued++; }
                    }
                    if (!node.isVisibleToUser()) continue;
                    String text = string(node.getText());
                    String description = string(node.getContentDescription());
                    String id = node.getViewIdResourceName();
                    adContext |= SkipClassifier.mentionsAd(text) || SkipClassifier.mentionsAd(description);
                    rewardContext |= SkipClassifier.mentionsReward(text) || SkipClassifier.mentionsReward(description);
                    if (!node.isEnabled() || node.isEditable()) continue;
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!smallTarget(bounds, window)) continue;
                    boolean topRight = bounds.centerX() > window.left + window.width() * .58f
                            && bounds.centerY() < window.top + window.height() * .30f;
                    boolean custom = false;
                    for (Rule rule : appRules) {
                        if (!Rule.POINT.equals(rule.type) && rule.appliesTo(pkg, currentActivity)
                                && rule.matchesNode(text, description, id)) { custom = true; break; }
                    }
                    if (custom) bestCustom = choose(bestCustom, node, bounds, 150, text, id);
                    if (SkipClassifier.isUnsafeTarget(text, id)
                            || SkipClassifier.isUnsafeTarget(description, id)) continue;
                    int score = Math.max(SkipClassifier.score(text, id, topRight, false, false),
                            SkipClassifier.score(description, "", topRight, false, false));
                    if (score > 0) {
                        if (SkipClassifier.isAdSpecific(text, id) || SkipClassifier.isAdSpecific(description, id)) {
                            bestSpecific = choose(bestSpecific, node, bounds, score, text, id);
                        } else {
                            bestBroad = choose(bestBroad, node, bounds, score, text, id);
                        }
                    }
                    if (genericClose && topRight) {
                        int closeScore = Math.max(SkipClassifier.score(text, "", true, true, true),
                                SkipClassifier.score(description, "", true, true, true));
                        if (closeScore == 65) bestClose = choose(bestClose, node, bounds, 65, text, id);
                    }
                } finally {
                    recycle(node);
                }
            }
            Candidate result = bestCustom;
            if (result == null && !rewardContext) {
                result = bestSpecific;
                if (result == null && (broadSkip || adContext)) result = bestBroad;
                if (result == null && adContext) result = bestClose;
            }
            if (bestSpecific != result && bestSpecific != null) recycle(bestSpecific.node);
            if (bestBroad != result && bestBroad != null) recycle(bestBroad.node);
            if (bestClose != result && bestClose != null) recycle(bestClose.node);
            if (bestCustom != result && bestCustom != null) recycle(bestCustom.node);
            return result;
        } catch (RuntimeException error) {
            if (bestSpecific != null) recycle(bestSpecific.node);
            if (bestBroad != null) recycle(bestBroad.node);
            if (bestClose != null) recycle(bestClose.node);
            if (bestCustom != null) recycle(bestCustom.node);
            throw error;
        } finally {
            while (!queue.isEmpty()) recycle(queue.removeFirst());
        }
    }

    private static boolean smallTarget(Rect bounds, Rect window) {
        return bounds.width() >= 8 && bounds.height() >= 8
                && bounds.width() <= window.width() * .72f
                && bounds.height() <= window.height() * .18f
                && window.contains(bounds);
    }

    private static Candidate choose(Candidate previous, AccessibilityNodeInfo node, Rect bounds,
                                    int score, String label, String id) {
        if (previous != null && previous.score >= score) return previous;
        // Node identity + position, not changing countdown text, defines the retry target.
        String key = (id == null ? node.getClassName() : id) + ":" + bounds.toShortString();
        Candidate candidate = new Candidate(AccessibilityNodeInfo.obtain(node), new Rect(bounds), score, key);
        if (previous != null) recycle(previous.node);
        return candidate;
    }

    private void click(Candidate candidate, String pkg, int windowId, WindowFrame frame, String key) {
        if (!candidate.node.refresh() || !candidate.node.isEnabled() || !candidate.node.isVisibleToUser()) return;
        Rect freshBounds = new Rect();
        candidate.node.getBoundsInScreen(freshBounds);
        if (!smallTarget(freshBounds, frame.bounds) || !activeWindowMatches(pkg, windowId)) return;
        clickGuard.record(key, SystemClock.uptimeMillis());
        AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(candidate.node);
        boolean accepted = false;
        try {
            for (int level = 0; target != null && level <= 4; level++) {
                Rect area = new Rect();
                target.getBoundsInScreen(area);
                if (target.isClickable() && target.isEnabled() && target.isVisibleToUser()
                        && smallTarget(area, frame.bounds)
                        && (long) area.width() * area.height()
                        <= (long) freshBounds.width() * freshBounds.height() * 12) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        recordAcceptedClick();
                        accepted = true;
                        break;
                    }
                }
                AccessibilityNodeInfo parent = target.getParent();
                recycle(target);
                target = parent;
            }
        } finally {
            recycle(target);
        }
        if (!accepted) tap(freshBounds.centerX(), freshBounds.centerY(), frame.displayId);
        requestScan(500);
    }

    private boolean activeWindowMatches(String pkg, int windowId) {
        if (!canRun() || blocked(pkg)) return false;
        AccessibilityNodeInfo active = getRootInActiveWindow();
        try {
            return active != null && pkg.equals(string(active.getPackageName()))
                    && active.getWindowId() == windowId;
        } finally { recycle(active); }
    }

    private WindowFrame frame(AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        int displayId = 0;
        AccessibilityWindowInfo window = root.getWindow();
        if (window != null) {
            try {
                if (bounds.isEmpty()) window.getBoundsInScreen(bounds);
                if (Build.VERSION.SDK_INT >= 30) displayId = window.getDisplayId();
            } finally { window.recycle(); }
        }
        return new WindowFrame(bounds, displayId);
    }

    private void schedulePointRules() {
        if (currentActivity.isEmpty()) return;
        String pkg = currentPackage;
        String activity = currentActivity;
        int windowId = currentWindow;
        int token = generation;
        int sequence = actionSequence;
        for (Rule rule : rulesByPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (!Rule.POINT.equals(rule.type) || rule.activityClass.isEmpty()
                    || !rule.appliesTo(pkg, activity)) continue;
            Long last = pointFiredAt.get(rule.id);
            if (last != null && SystemClock.uptimeMillis() - last < 30000) continue;
            Runnable task = () -> {
                if (token != generation || sequence != actionSequence || !canRun()
                        || !pkg.equals(currentPackage) || !activity.equals(currentActivity)
                        || !activeWindowMatches(pkg, windowId)) return;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                try {
                    if (root == null || !pkg.equals(string(root.getPackageName()))
                            || root.getWindowId() != windowId) return;
                    WindowFrame frame = frame(root);
                    if (frame.bounds.isEmpty()) return;
                    Rect coordinateFrame = frame.bounds;
                    if ("screen".equals(rule.coordinateSpace)) {
                        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                        coordinateFrame = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
                    }
                    int x = coordinateFrame.left + Math.round(coordinateFrame.width()
                            * Math.max(1, Math.min(99, rule.xPercent)) / 100f);
                    int y = coordinateFrame.top + Math.round(coordinateFrame.height()
                            * Math.max(1, Math.min(99, rule.yPercent)) / 100f);
                    if (!frame.bounds.contains(x, y)) return;
                    if (pointFiredAt.size() > 64) pointFiredAt.clear();
                    pointFiredAt.put(rule.id, SystemClock.uptimeMillis());
                    tap(x, y, frame.displayId);
                } finally { recycle(root); }
            };
            pageTasks.add(task);
            handler.postDelayed(task, Math.max(0, Math.min(10000, rule.delayMs)));
        }
    }

    private void tap(int x, int y, int displayId) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId);
        int token = generation;
        if (dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) {
                if (!destroyed) recordAcceptedClick();
                if (token == generation) requestScan(500);
            }
        }, handler)) {
            // Prevent a manual point task from following an automatic gesture on the same page.
            actionSequence++;
        }
    }

    private void recordAcceptedClick() {
        actionSequence++;
        pendingCount++;
        handler.removeCallbacks(flushTask);
        handler.postDelayed(flushTask, 1500);
    }

    private void flushCount() {
        handler.removeCallbacks(flushTask);
        if (prefs != null && pendingCount > 0) {
            int count = prefs.getInt(RuleStore.KEY_COUNT, 0) + pendingCount;
            pendingCount = 0;
            prefs.edit().putInt(RuleStore.KEY_COUNT, count).apply();
        }
    }

    private void cancelWork() {
        generation++;
        handler.removeCallbacks(scanTask);
        pendingScanAt = Long.MAX_VALUE;
        for (Runnable task : pageTasks) handler.removeCallbacks(task);
        pageTasks.clear();
    }

    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo node) { if (node != null) node.recycle(); }

    @Override public void onInterrupt() { cancelWork(); flushCount(); }
    @Override public void onDestroy() {
        destroyed = true;
        cancelWork();
        flushCount();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final int score;
        final String key;
        Candidate(AccessibilityNodeInfo node, Rect bounds, int score, String key) {
            this.node = node; this.bounds = bounds; this.score = score; this.key = key;
        }
    }
    private static final class WindowFrame {
        final Rect bounds;
        final int displayId;
        WindowFrame(Rect bounds, int displayId) { this.bounds = bounds; this.displayId = displayId; }
    }
}
