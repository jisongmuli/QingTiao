package com.qingtiao.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowAccessibilityService;

import java.time.Duration;
import java.util.Collections;

/** Exercises real service events, scheduling and node traversal on an Android framework sandbox. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SkipServiceTest.ActiveWindowShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SkipServiceTest {
    private static final String APP = "com.test.app";
    private static final String ACTIVITY = "ad.SplashActivity";
    private ServiceController<SkipService> controller;
    private SkipService service;
    private ActiveWindowShadow window;
    private SharedPreferences prefs;

    @Before
    public void connect() {
        controller = Robolectric.buildService(SkipService.class).create();
        service = controller.get();
        prefs = RuleStore.prefs(service);
        prefs.edit().clear().commit();
        shadowOf((PowerManager) service.getSystemService(Context.POWER_SERVICE))
                .setIsInteractive(true);
        shadowOf((KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE))
                .setKeyguardLocked(false);
        window = Shadow.extract(service);
        service.onServiceConnected();
    }

    @After
    public void disconnect() {
        controller.destroy();
    }

    @Test
    public void freshInstallSkipsAdWithoutAnyCustomRule() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        window.root = page(APP, skip);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(skip));
        assertTrue(RuleStore.load(prefs).isEmpty());
        assertTrue(window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void builtInSdkIdWorksWithoutTextOrCustomRules() {
        AccessibilityNodeInfo skip = button("", 850, 100, 1030, 180);
        skip.setViewIdResourceName("com.byted.pangle:id/tt_splash_skip_btn");
        window.root = page(APP, skip);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(skip));
    }

    @Test
    public void nonClickableAdLabelUsesGestureFallback() {
        window.setCanDispatchGestures(true);
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        skip.setClickable(false);
        window.root = page(APP, skip);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, window.getGesturesDispatched().size());
        assertEquals(0, clicks(skip));
    }

    @Test
    public void voluntaryRewardVideoIsNotInterruptedByBuiltIns() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        AccessibilityNodeInfo reward = button("观看广告领取奖励", 100, 600, 900, 700);
        window.root = page(APP, skip, reward);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(12000);
        assertEquals(0, clicks(skip));
        assertEquals(0, clicks(reward));
        assertTrue(window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void promotionalRewardCopyDoesNotPreventSkippingAnOrdinaryAd() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        AccessibilityNodeInfo promotion = button("领取红包", 100, 600, 900, 700);
        window.root = page(APP, skip, promotion);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(skip));
        assertEquals(0, clicks(promotion));
    }

    @Test
    public void conflictingRewardDescriptionCannotBeBypassedByAdId() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        skip.setContentDescription("领取奖励");
        skip.setViewIdResourceName("com.test.app:id/skip_ad");
        window.root = page(APP, skip);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(0, clicks(skip));
        assertTrue(window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void enabledChildOfDisabledInvisibleContainerIsStillFound() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        AccessibilityNodeInfo container = page(APP, skip);
        container.setEnabled(false);
        container.setVisibleToUser(false);
        window.root = page(APP, container);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(skip));
    }

    @Test
    public void windowsChangedWithoutPackageUsesActiveWindow() {
        AccessibilityNodeInfo skip = button("Skip Ad", 850, 100, 1030, 180);
        window.root = page(APP, skip);
        event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null);
        advance(700);
        assertEquals(1, clicks(skip));
    }

    @Test
    public void launchProbeFindsLateButtonEvenWithoutAnotherAccessibilityEvent() {
        window.root = page(APP);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(400);
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        window.root = page(APP, skip);
        advance(2500);
        assertEquals(1, clicks(skip));
    }

    @Test
    public void secondDismissButtonAppearingDuringCooldownIsNotLost() {
        AccessibilityNodeInfo first = button("跳过广告", 850, 100, 1030, 180);
        window.root = page(APP, first);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(350);
        assertEquals(1, clicks(first));

        AccessibilityNodeInfo second = button("关闭广告", 850, 240, 1030, 320);
        window.root = page(APP, second);
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null);
        advance(2000);
        assertEquals(1, clicks(second));
    }

    @Test
    public void eventBurstIsCoalescedAndLaunchProbesEventuallyStop() {
        window.root = page(APP);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        for (int i = 0; i < 200; i++) {
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null);
        }
        advance(300);
        assertTrue("200 events should not cause 200 root reads", window.rootReads <= 8);
        advance(12000);
        int readsAfterLaunch = window.rootReads;
        advance(30000);
        assertEquals("A quiet app must not be polled forever", readsAfterLaunch, window.rootReads);
    }

    @Test
    public void actualWindowBoundsSupportOffsetsAndLargeScreens() {
        AccessibilityNodeInfo skip = button("跳过广告", 1320, 170, 1470, 245);
        window.root = page(APP, skip);
        window.root.setBoundsInScreen(new Rect(500, 100, 1500, 1900));
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(skip));
    }

    @Test
    public void normalCloseAndLargeLabeledContainerDoNotTriggerClicks() {
        AccessibilityNodeInfo close = button("关闭", 850, 100, 1030, 180);
        window.root = page(APP, close);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, "app.EditActivity");
        advance(700);
        assertEquals(0, clicks(close));
        assertTrue(window.getGesturesDispatched().isEmpty());

        AccessibilityNodeInfo large = button("跳过广告", 0, 0, 1080, 1920);
        window.root = page(APP, large);
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null);
        advance(700);
        assertEquals(0, clicks(large));
        assertTrue(window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void openingOwnAppCancelsPreviouslyScheduledCoordinateTap() {
        Rule point = new Rule();
        point.packageName = APP;
        point.activityClass = ACTIVITY;
        point.type = Rule.POINT;
        point.delayMs = 1000;
        RuleStore.save(prefs, Collections.singletonList(point));
        window.root = page(APP);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(200);

        window.root = page(service.getPackageName());
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, service.getPackageName(),
                "com.qingtiao.app.MainActivity");
        advance(100);
        window.root = page(APP);
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, APP, null);
        advance(1800);
        assertTrue("Leaving the window must invalidate the old tap", window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void disablingServiceCancelsDelayedWork() {
        window.root = page(APP);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(100);
        prefs.edit().putBoolean(RuleStore.KEY_ENABLED, false).commit();
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        window.root = page(APP, skip);
        advance(12000);
        assertEquals(0, clicks(skip));
        assertTrue(window.getGesturesDispatched().isEmpty());
    }

    @Test
    public void upgradingKeepsLegacyCoordinatesAndNewRulesUseWindowCoordinates() throws Exception {
        Rule old = Rule.fromJson(new org.json.JSONObject(
                "{\"package\":\"com.test.app\",\"activity\":\"ad.SplashActivity\",\"type\":\"point\",\"x\":87,\"y\":9}"));
        assertEquals("screen", old.coordinateSpace);
        RuleStore.save(prefs, Collections.singletonList(old));
        Rule restored = RuleStore.load(prefs).get(0);
        assertEquals("screen", restored.coordinateSpace);
        assertEquals(87, restored.xPercent);
        assertEquals(9, restored.yPercent);
        assertEquals("window", new Rule().coordinateSpace);
    }

    @Test
    public void borrowedNodeHandlesAreRecycledOnAndroidNine() {
        AccessibilityNodeInfo skip = button("跳过广告", 850, 100, 1030, 180);
        skip.setClickable(false);
        AccessibilityNodeInfo parent = button("", 820, 85, 1050, 195);
        shadowOf(parent).addChild(skip);
        window.root = page(APP, parent);
        // Fixture nodes model the remote app. Count only copies borrowed by this service.
        ShadowAccessibilityNodeInfo.resetObtainedInstances();
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, APP, ACTIVITY);
        advance(700);
        assertEquals(1, clicks(parent));
        assertFalse("All root, child and parent handles must be released",
                ShadowAccessibilityNodeInfo.areThereUnrecycledNodes(true));
    }

    private AccessibilityNodeInfo page(String pkg, AccessibilityNodeInfo... children) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setPackageName(pkg);
        root.setEnabled(true);
        root.setVisibleToUser(true);
        root.setBoundsInScreen(new Rect(0, 0, 1080, 1920));
        for (AccessibilityNodeInfo child : children) shadowOf(root).addChild(child);
        return root;
    }

    private AccessibilityNodeInfo button(String text, int left, int top, int right, int bottom) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(APP);
        node.setText(text);
        node.setEnabled(true);
        node.setVisibleToUser(true);
        node.setClickable(true);
        node.setBoundsInScreen(new Rect(left, top, right, bottom));
        shadowOf(node).setOnPerformActionListener((action, arguments) -> {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) node.setVisibleToUser(false);
            return true;
        });
        return node;
    }

    private void event(int type, String pkg, String activity) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        event.setClassName(activity);
        service.onAccessibilityEvent(event);
        event.recycle();
    }

    private static void advance(long milliseconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }

    private static long clicks(AccessibilityNodeInfo node) {
        return shadowOf(node).getPerformedActions().stream()
                .filter(action -> action == AccessibilityNodeInfo.ACTION_CLICK).count();
    }

    @Implements(AccessibilityService.class)
    public static class ActiveWindowShadow extends ShadowAccessibilityService {
        AccessibilityNodeInfo root;
        int rootReads;

        @Implementation
        protected AccessibilityNodeInfo getRootInActiveWindow() {
            rootReads++;
            return root == null ? null : AccessibilityNodeInfo.obtain(root);
        }
    }
}
