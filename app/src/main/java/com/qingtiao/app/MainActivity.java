package com.qingtiao.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int INK = Color.rgb(23, 32, 52);
    private static final int MUTED = Color.rgb(92, 106, 128);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private SharedPreferences prefs;
    private TextView statusText;
    private TextView accessInfo;
    private Button accessButton;
    private TextView countText;
    private TextView recentText;
    private Button advancedButton;
    private LinearLayout advancedContainer;
    private LinearLayout rulesContainer;
    private String renderedRules;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean returningFromSettings;
    private long statusCheckUntil;
    private AccessibilityManager accessibilityManager;
    private final Runnable statusCheck = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh();
            if (SystemClock.uptimeMillis() < statusCheckUntil) {
                statusHandler.postDelayed(this, 1000);
            }
        }
    };
    private final ContentObserver serviceObserver = new ContentObserver(statusHandler) {
        @Override public void onChange(boolean selfChange) { serviceStateChanged(); }
    };
    private final AccessibilityManager.AccessibilityStateChangeListener serviceListener =
            enabled -> serviceStateChanged();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = RuleStore.prefs(this);
        returningFromSettings = state != null && state.getBoolean("returning_from_settings", false);
        accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 249, 252));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(26), dp(20), dp(30));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            page.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(26) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(),
                    dp(30) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);

        page.addView(text("轻跳", 30, INK, true));
        TextView subtitle = text("开启无障碍，即可自动跳过可识别的广告", 14, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, dp(20));
        page.addView(subtitle);

        LinearLayout accessCard = card(page);
        statusText = text("", 19, INK, true);
        accessCard.addView(statusText);
        accessInfo = text("", 14, MUTED, false);
        accessInfo.setPadding(0, dp(7), 0, dp(10));
        accessCard.addView(accessInfo);
        accessButton = button("开启无障碍服务");
        accessButton.setOnClickListener(v -> openAccessibilitySettings());
        accessCard.addView(accessButton);
        if (isVivoDevice()) {
            TextView recoveryHint = text(getString(R.string.vivo_service_hint), 13, MUTED, false);
            recoveryHint.setPadding(0, dp(8), 0, 0);
            accessCard.addView(recoveryHint);
        }

        LinearLayout switchCard = card(page);
        Switch enabled = new Switch(this);
        enabled.setText("自动跳过");
        enabled.setTextColor(INK);
        enabled.setTextSize(17);
        enabled.setChecked(prefs.getBoolean(RuleStore.KEY_ENABLED, true));
        enabled.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(RuleStore.KEY_ENABLED, checked).apply());
        switchCard.addView(enabled);
        TextView enabledHint = text("内置识别已就绪，无需添加规则。开启服务后，照常使用其他应用即可。", 14, MUTED, false);
        enabledHint.setPadding(0, dp(7), 0, dp(11));
        switchCard.addView(enabledHint);

        LinearLayout infoCard = card(page);
        countText = text("", 18, INK, true);
        infoCard.addView(countText);
        TextView localHint = text("本地处理 · 无需联网 · 不截屏", 13, MUTED, false);
        localHint.setPadding(0, dp(7), 0, 0);
        infoCard.addView(localHint);

        advancedButton = button("高级设置与自定义规则 ▾");
        page.addView(advancedButton);
        advancedContainer = new LinearLayout(this);
        advancedContainer.setOrientation(LinearLayout.VERTICAL);
        advancedContainer.setVisibility(View.GONE);
        page.addView(advancedContainer);
        advancedButton.setOnClickListener(v -> setAdvancedExpanded(
                advancedContainer.getVisibility() != View.VISIBLE));

        LinearLayout recognitionCard = card(advancedContainer);
        recognitionCard.addView(text("识别选项", 18, INK, true));
        Switch broadSkip = new Switch(this);
        broadSkip.setText("识别未标注广告的“跳过”按钮");
        broadSkip.setTextColor(INK);
        broadSkip.setTextSize(15);
        broadSkip.setChecked(prefs.getBoolean(RuleStore.KEY_BROAD_SKIP, true));
        broadSkip.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(RuleStore.KEY_BROAD_SKIP, checked).apply());
        recognitionCard.addView(broadSkip);
        TextView broadHint = text("可覆盖未标注广告的页面，也可能跳过新手引导。", 12, MUTED, false);
        broadHint.setPadding(0, dp(4), 0, dp(11));
        recognitionCard.addView(broadHint);
        Switch genericClose = new Switch(this);
        genericClose.setText("识别广告页右上角的“关闭”");
        genericClose.setTextColor(INK);
        genericClose.setTextSize(15);
        genericClose.setChecked(prefs.getBoolean(RuleStore.KEY_GENERIC_CLOSE, false));
        genericClose.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(RuleStore.KEY_GENERIC_CLOSE, checked).apply());
        recognitionCard.addView(genericClose);
        recognitionCard.addView(text("默认关闭，开启后可能误点普通弹窗的关闭按钮。", 12, MUTED, false));

        TextView rulesTitle = text("自定义规则", 22, INK, true);
        rulesTitle.setPadding(0, dp(12), 0, dp(5));
        advancedContainer.addView(rulesTitle);
        TextView rulesHelp = text("通常无需配置。自动识别不到时，可为单个应用补充按钮文字、控件 ID 或页面坐标。", 13, MUTED, false);
        rulesHelp.setPadding(0, 0, 0, dp(12));
        advancedContainer.addView(rulesHelp);
        LinearLayout ruleCard = card(advancedContainer);
        Button addNode = button("添加按钮规则");
        addNode.setOnClickListener(v -> showNodeRuleDialog());
        ruleCard.addView(addNode);
        Button addPoint = button("添加坐标规则");
        addPoint.setOnClickListener(v -> showPointRuleDialog());
        ruleCard.addView(addPoint);
        rulesContainer = new LinearLayout(this);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        ruleCard.addView(rulesContainer);

        LinearLayout excludeCard = card(advancedContainer);
        excludeCard.addView(text("排除应用", 17, INK, true));
        TextView excludeHelp = text("不希望自动点击的应用，可填入它的包名。", 13, MUTED, false);
        excludeHelp.setPadding(0, dp(5), 0, dp(7));
        excludeCard.addView(excludeHelp);
        Button editExclude = button("编辑排除列表");
        editExclude.setOnClickListener(v -> showExcludeDialog());
        excludeCard.addView(editExclude);

        LinearLayout recentCard = card(advancedContainer);
        recentCard.addView(text("最近识别的页面", 17, INK, true));
        recentText = text("", 13, MUTED, false);
        recentText.setPadding(0, dp(8), 0, 0);
        recentText.setTextIsSelectable(true);
        recentCard.addView(recentText);

        TextView footer = text("说明：图像按钮、游戏画面和未暴露控件的页面，可能需要坐标规则；页面变化后坐标规则可能失效。", 12, MUTED, false);
        footer.setPadding(dp(2), dp(6), dp(2), dp(12));
        advancedContainer.addView(footer);
        TextView limitation = text("部分广告不提供可识别的跳过按钮，可能无法自动跳过。", 12, MUTED, false);
        limitation.setPadding(dp(2), dp(12), dp(2), dp(12));
        page.addView(limitation);
        setAdvancedExpanded(state != null && state.getBoolean("advanced_expanded", false));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        prefs.registerOnSharedPreferenceChangeListener(this);
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, serviceObserver);
        if (accessibilityManager != null) accessibilityManager.addAccessibilityStateChangeListener(serviceListener);
        if (returningFromSettings || isServiceAuthorized()
                || (isVivoDevice() && prefs.getBoolean(RuleStore.KEY_SERVICE_SEEN, false))) {
            statusCheckUntil = SystemClock.uptimeMillis() + 15000;
        }
        returningFromSettings = false;
        statusCheck.run();
    }

    @Override
    protected void onPause() {
        resumed = false;
        statusHandler.removeCallbacks(statusCheck);
        statusCheckUntil = 0;
        getContentResolver().unregisterContentObserver(serviceObserver);
        if (accessibilityManager != null) accessibilityManager.removeAccessibilityStateChangeListener(serviceListener);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("advanced_expanded", advancedContainer.getVisibility() == View.VISIBLE);
        outState.putBoolean("returning_from_settings", returningFromSettings);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (RuleStore.KEY_RULES.equals(key) || RuleStore.KEY_COUNT.equals(key)
                || RuleStore.KEY_LAST_PACKAGE.equals(key) || RuleStore.KEY_LAST_CLASS.equals(key)
                || RuleStore.KEY_ENABLED.equals(key)) {
            runOnUiThread(this::refresh);
        }
    }

    private void refresh() {
        if (statusText == null) return;
        boolean serviceEnabled = isServiceEnabled();
        if (serviceEnabled) statusCheckUntil = 0;
        boolean waiting = !serviceEnabled && SystemClock.uptimeMillis() < statusCheckUntil;
        boolean authorized = !serviceEnabled && isServiceAuthorized();
        boolean automaticEnabled = prefs.getBoolean(RuleStore.KEY_ENABLED, true);
        statusText.setText(waiting ? "正在等待系统连接服务"
                : !serviceEnabled ? (authorized ? "已授权，服务尚未连接" : "只需一步：开启无障碍")
                : automaticEnabled ? "自动跳过已开启" : "自动跳过已暂停");
        statusText.setTextColor(serviceEnabled && automaticEnabled ? Color.rgb(14, 117, 69) : INK);
        String instructions = waiting ? "请留在轻跳稍等，状态会自动更新。部分系统离开设置后需约 8–15 秒恢复服务。"
                : authorized ? "系统已保存授权，但服务还未连接。可在系统设置中关闭后重新开启，再返回轻跳等待。"
                : !serviceEnabled ? "在系统设置中找到“轻跳自动跳过”，开启后彻底退出系统设置，返回轻跳。"
                : automaticEnabled ? "已准备好，返回其他应用即可自动识别。" : "打开下方“自动跳过”开关即可恢复。";
        accessInfo.setText(getString(R.string.service_disclosure_format, instructions));
        accessButton.setText(serviceEnabled ? "管理无障碍服务" : "开启无障碍服务");
        countText.setText(getString(R.string.click_count, prefs.getInt(RuleStore.KEY_COUNT, 0)));
        if (advancedContainer.getVisibility() != View.VISIBLE) return;
        String pkg = prefs.getString(RuleStore.KEY_LAST_PACKAGE, "");
        String cls = prefs.getString(RuleStore.KEY_LAST_CLASS, "");
        recentText.setText(pkg == null || pkg.isEmpty() ? "尚未检测到其他应用"
                : "最近应用：" + pkg + (cls == null || cls.isEmpty() ? "" : "\n最近页面：" + cls));
        renderRules();
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedButton.setText(expanded ? "收起高级设置 ▴" : "高级设置与自定义规则 ▾");
        if (expanded) refresh();
    }

    private void openAccessibilitySettings() {
        try {
            returningFromSettings = true;
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            returningFromSettings = false;
            new AlertDialog.Builder(this).setTitle("请在系统设置中开启")
                    .setMessage("打开手机“设置”，搜索“无障碍”，找到“轻跳自动跳过”并开启服务。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private boolean isServiceEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        ComponentName service = new ComponentName(this, SkipService.class);
        for (AccessibilityServiceInfo info :
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service.equals(ComponentName.unflattenFromString(info.getId()))) return true;
        }
        return false;
    }

    private void serviceStateChanged() {
        if (!resumed) return;
        statusCheckUntil = SystemClock.uptimeMillis() + 15000;
        statusHandler.removeCallbacks(statusCheck);
        statusHandler.post(statusCheck);
    }

    private boolean isServiceAuthorized() {
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null || services.isEmpty()) return false;
        ComponentName own = new ComponentName(this, SkipService.class);
        for (String value : services.split(":")) {
            if (own.equals(ComponentName.unflattenFromString(value))) return true;
        }
        return false;
    }

    private static boolean isVivoDevice() {
        String maker = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return maker.contains("vivo") || maker.contains("iqoo");
    }

    private void renderRules() {
        String saved = prefs.getString(RuleStore.KEY_RULES, "[]");
        if (saved != null && saved.equals(renderedRules)) return;
        renderedRules = saved;
        rulesContainer.removeAllViews();
        List<Rule> rules = RuleStore.load(prefs);
        if (rules.isEmpty()) {
            TextView empty = text("正在使用内置识别，无需添加自定义规则。", 13, MUTED, false);
            empty.setPadding(0, dp(12), 0, dp(2));
            rulesContainer.addView(empty);
            return;
        }
        for (Rule rule : rules) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(12), 0, dp(3));
            String detail = Rule.POINT.equals(rule.type)
                    ? ("screen".equals(rule.coordinateSpace) ? "屏幕坐标（旧版） " : "窗口坐标 ")
                    + rule.xPercent + "%, " + rule.yPercent + "% · 等待 " + rule.delayMs + "ms"
                    : (Rule.TEXT.equals(rule.type) ? "文字包含 " : "控件 ID ") + rule.value;
            item.addView(text(rule.packageName, 15, INK, true));
            item.addView(text(detail, 13, MUTED, false));
            if (!rule.activityClass.isEmpty()) item.addView(text("页面 " + rule.activityClass, 12, MUTED, false));
            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            Switch toggle = new Switch(this);
            toggle.setText("启用");
            toggle.setChecked(rule.enabled);
            toggle.setOnCheckedChangeListener((view, checked) -> {
                rule.enabled = checked;
                RuleStore.save(prefs, rules);
            });
            controls.addView(toggle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button remove = button("删除");
            remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除规则？")
                    .setMessage(rule.packageName + "\n" + detail)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        rules.remove(rule);
                        RuleStore.save(prefs, rules);
                    }).show());
            controls.addView(remove);
            item.addView(controls);
            rulesContainer.addView(item);
        }
    }

    private void showNodeRuleDialog() {
        LinearLayout form = form();
        EditText pkg = field(form, "应用包名（必填）", prefs.getString(RuleStore.KEY_LAST_PACKAGE, ""));
        EditText activity = field(form, "页面类名（留空为该应用所有页面）", "");
        form.addView(text("识别方式", 13, MUTED, false));
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"按钮文字", "控件 ID"}));
        form.addView(type);
        EditText value = field(form, "文字片段或控件 ID", "");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加按钮规则")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String packageName = pkg.getText().toString().trim();
            String target = value.getText().toString().trim();
            if (!validPackage(packageName)) {
                pkg.setError("请输入有效的应用包名");
                return;
            }
            if (target.isEmpty()) {
                value.setError("请输入按钮文字或控件 ID");
                return;
            }
            Rule rule = new Rule();
            rule.packageName = packageName;
            rule.activityClass = activity.getText().toString().trim();
            rule.type = type.getSelectedItemPosition() == 0 ? Rule.TEXT : Rule.VIEW_ID;
            rule.value = target;
            List<Rule> rules = RuleStore.load(prefs);
            rules.add(rule);
            RuleStore.save(prefs, rules);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showPointRuleDialog() {
        LinearLayout form = form();
        EditText pkg = field(form, "应用包名（必填）", prefs.getString(RuleStore.KEY_LAST_PACKAGE, ""));
        EditText activity = field(form, "页面类名（必填，默认最近页面）",
                prefs.getString(RuleStore.KEY_LAST_CLASS, ""));
        EditText x = numericField(form, "横向位置：窗口宽度的 1–99%", "90");
        EditText y = numericField(form, "纵向位置：窗口高度的 1–99%", "8");
        EditText delay = numericField(form, "页面出现后等待毫秒 0–10000", "800");
        TextView warning = text("仅在指定页面出现时触发，每 30 秒最多一次。建议先用按钮规则。", 12, MUTED, false);
        warning.setPadding(0, dp(8), 0, 0);
        form.addView(warning);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加坐标规则")
                .setView(scrollForm(form))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String packageName = pkg.getText().toString().trim();
            String className = activity.getText().toString().trim();
            if (!validPackage(packageName)) {
                pkg.setError("请输入有效的应用包名");
                return;
            }
            if (className.isEmpty()) {
                activity.setError("坐标规则必须限定页面");
                return;
            }
            int px = parseInRange(x, 1, 99);
            int py = parseInRange(y, 1, 99);
            int wait = parseInRange(delay, 0, 10000);
            if (px < 0 || py < 0 || wait < 0) return;
            Rule rule = new Rule();
            rule.packageName = packageName;
            rule.activityClass = className;
            rule.type = Rule.POINT;
            rule.xPercent = px;
            rule.yPercent = py;
            rule.delayMs = wait;
            List<Rule> rules = RuleStore.load(prefs);
            rules.add(rule);
            RuleStore.save(prefs, rules);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showExcludeDialog() {
        LinearLayout form = form();
        EditText excluded = field(form, "每行或逗号分隔一个应用包名",
                prefs.getString(RuleStore.KEY_EXCLUDED, ""));
        excluded.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        excluded.setSingleLine(false);
        excluded.setMinLines(5);
        excluded.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this).setTitle("排除应用")
                .setView(scrollForm(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> prefs.edit()
                        .putString(RuleStore.KEY_EXCLUDED, excluded.getText().toString().trim()).apply())
                .show();
    }

    private static boolean validPackage(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    private static int parseInRange(EditText field, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {}
        field.setError("请输入 " + min + "–" + max + " 之间的数字");
        return -1;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(4));
        return form;
    }

    private ScrollView scrollForm(LinearLayout form) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(form);
        return scroll;
    }

    private EditText field(LinearLayout parent, String hint, String initial) {
        TextView label = text(hint, 13, MUTED, false);
        label.setPadding(0, dp(10), 0, 0);
        parent.addView(label);
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setHint(hint);
        edit.setText(initial == null ? "" : initial);
        parent.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private EditText numericField(LinearLayout parent, String hint, String initial) {
        EditText edit = field(parent, hint, initial);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        return edit;
    }

    private LinearLayout card(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(13);
        page.addView(card, params);
        return card;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(BLUE);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
