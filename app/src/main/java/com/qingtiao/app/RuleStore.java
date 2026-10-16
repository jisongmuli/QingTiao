package com.qingtiao.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class RuleStore {
    public static final String PREFS = "light_skip";
    public static final String KEY_RULES = "rules";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_GENERIC_CLOSE = "generic_close";
    public static final String KEY_BROAD_SKIP = "broad_skip";
    public static final String KEY_EXCLUDED = "excluded";
    public static final String KEY_COUNT = "count";
    public static final String KEY_LAST_PACKAGE = "last_package";
    public static final String KEY_LAST_CLASS = "last_class";
    public static final String KEY_SERVICE_SEEN = "service_connected_once";

    private RuleStore() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Rule> load(SharedPreferences prefs) {
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_RULES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) {
                    Rule rule = Rule.fromJson(array.optJSONObject(i));
                    if (!rule.packageName.isEmpty()) rules.add(rule);
                }
            }
        } catch (JSONException ignored) {
            // Bad local data must never prevent the accessibility service from starting.
        }
        return rules;
    }

    public static void save(SharedPreferences prefs, List<Rule> rules) {
        JSONArray array = new JSONArray();
        for (Rule rule : rules) {
            try {
                array.put(rule.toJson());
            } catch (JSONException ignored) {
                // The model contains only JSON-safe primitives.
            }
        }
        prefs.edit().putString(KEY_RULES, array.toString()).apply();
    }

    public static boolean isExcluded(SharedPreferences prefs, String pkg) {
        String raw = prefs.getString(KEY_EXCLUDED, "");
        if (raw == null || raw.isEmpty()) return false;
        for (String item : raw.split("[,\\n\\s]+")) {
            if (pkg.equals(item.trim())) return true;
        }
        return false;
    }
}
