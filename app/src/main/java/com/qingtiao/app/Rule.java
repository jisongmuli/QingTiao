package com.qingtiao.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A user-created rule is always scoped to one Android package. */
public final class Rule {
    public static final String TEXT = "text";
    public static final String VIEW_ID = "view_id";
    public static final String POINT = "point";

    public String id = UUID.randomUUID().toString();
    public String packageName = "";
    public String activityClass = "";
    public String type = TEXT;
    public String value = "";
    public int xPercent = 90;
    public int yPercent = 8;
    public int delayMs = 800;
    public String coordinateSpace = "window";
    public boolean enabled = true;

    public boolean appliesTo(String pkg, String activity) {
        return enabled && packageName.equals(pkg)
                && (activityClass.isEmpty() || activityClass.equals(activity));
    }

    public boolean matchesNode(String text, String contentDescription, String viewId) {
        if (TEXT.equals(type)) {
            return contains(text, value) || contains(contentDescription, value);
        }
        if (VIEW_ID.equals(type) && viewId != null) {
            return viewId.equals(value) || viewId.endsWith("/" + value);
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && !needle.isEmpty()
                && haystack.toLowerCase(java.util.Locale.ROOT)
                .contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("package", packageName);
        object.put("activity", activityClass);
        object.put("type", type);
        object.put("value", value);
        object.put("x", xPercent);
        object.put("y", yPercent);
        object.put("delay", delayMs);
        object.put("coordinateSpace", coordinateSpace);
        object.put("enabled", enabled);
        return object;
    }

    public static Rule fromJson(JSONObject object) {
        Rule rule = new Rule();
        rule.id = object.optString("id", rule.id);
        rule.packageName = object.optString("package", "");
        rule.activityClass = object.optString("activity", "");
        rule.type = object.optString("type", TEXT);
        rule.value = object.optString("value", "");
        rule.xPercent = object.optInt("x", 90);
        rule.yPercent = object.optInt("y", 8);
        rule.delayMs = object.optInt("delay", 800);
        // 1.0 stored percentages relative to the display. Preserve those existing rules.
        rule.coordinateSpace = object.optString("coordinateSpace", "screen");
        rule.enabled = object.optBoolean("enabled", true);
        return rule;
    }
}
