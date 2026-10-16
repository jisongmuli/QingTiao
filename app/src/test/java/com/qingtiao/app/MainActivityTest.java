package com.qingtiao.app;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainActivityTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void open() {
        RuleStore.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After public void close() { controller.pause().stop().destroy(); }

    @Test public void freshInstallIsEnabledAndRulesAreOptional() {
        assertTrue(((Switch) find("自动跳过")).isChecked());
        assertTrue(RuleStore.load(RuleStore.prefs(activity)).isEmpty());
        assertFalse(visibleThroughParents(find("添加按钮规则")));
        find("高级设置与自定义规则 ▾").performClick();
        assertTrue(visibleThroughParents(find("添加按钮规则")));
    }

    @Test public void setupButtonOpensSystemAccessibilitySettings() {
        find("开启无障碍服务").performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.getAction());
    }

    private View find(String text) {
        View result = findIn(activity.getWindow().getDecorView(), text);
        assertNotNull("Missing control: " + text, result);
        return result;
    }

    private static View findIn(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findIn(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean visibleThroughParents(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        return !(view.getParent() instanceof View) || visibleThroughParents((View) view.getParent());
    }
}
