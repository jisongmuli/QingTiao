package com.qingtiao.app;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small, bounded matchers for dismissible ad controls; no downloaded rules are needed. */
public final class SkipClassifier {
    private static final int MAX_LABEL_LENGTH = 96;
    private static final int MAX_CONTEXT_LENGTH = 256;
    private static final String SEPARATOR = "[|·•,:/\\-–—]*";
    private static final String COUNT = "[0-9]{1,2}(?:秒|s|secs?|seconds?)?";
    private static final String TIMER = "(?:\\(" + COUNT + "\\)|\\[" + COUNT + "\\]|" + COUNT + ")";
    private static final String SKIP = "(?:(?:点击)?(?:跳过|略过)(?:此广告|广告|开屏广告|开屏|(?:此)?视频广告)?"
            + "|skip(?:this)?(?:videoads?|ads?|advertisements?)?)";
    private static final Pattern SKIP_LABEL = Pattern.compile(
            "^(?:" + TIMER + SEPARATOR + ")?" + SKIP
            + "(?:" + SEPARATOR + TIMER + ")?$"
            + "|^(?:广告|ads?)" + SEPARATOR + "(?:" + TIMER + SEPARATOR + ")?"
            + "(?:跳过|略过|skip)(?:" + SEPARATOR + TIMER + ")?$");
    private static final Pattern CLOSE_AD = Pattern.compile(
            "^(?:(?:关闭|关掉)(?:此)?广告|(?:close|dismiss)(?:this)?(?:ads?|advertisements?))$");
    private static final Pattern AD_MARKER = Pattern.compile(
            "^(?:广告|广告倒计时|广告剩余|ads?|advertisements?|sponsored)"
            + "(?:" + SEPARATOR + TIMER + ")?$");
    private static final Pattern TIMER_ONLY = Pattern.compile("^" + TIMER + "$");

    // Whole resource names only: never treat an arbitrary ID containing "ad", "skip", or
    // "close" as a dismissal target. UI prefixes/suffixes are deliberately finite.
    private static final Pattern AD_CONTROL_ID = Pattern.compile(
            "^(?:(?:btn|tv|iv|img|view|layout|rl|fl)_)?"
            + "(?:skip_?ads?|ads?_?skip|close_?ads?|ads?_?close|"
            + "splash_(?:ad_)?(?:skip|close)|(?:skip|close)_splash(?:_ad)?)"
            + "(?:_(?:btn|button|tv|iv|view|text|layout))?$");
    private static final Pattern BROAD_SKIP_ID = Pattern.compile(
            "^(?:(?:btn|tv|iv|view|layout|rl|fl)_?)?skip(?:_?(?:btn|button|tv|view|text|textview|layout))?$");
    private static final Pattern UNSAFE_ID = Pattern.compile(
            "(?:^|_)(?:reward(?:ed)?|incentive|intro|outro|tutorial|onboarding|guide|login|signin|video)(?:_|$)");

    private SkipClassifier() {}

    public static int score(String label, String viewId, boolean topRight,
                            boolean adContext, boolean allowGenericClose) {
        if (label != null && label.length() > MAX_LABEL_LENGTH) return 0;
        String text = compact(label);
        String name = resourceName(viewId);
        if (unsafeText(text) || UNSAFE_ID.matcher(name).find()) return 0;
        if (SKIP_LABEL.matcher(text).matches()) return 100 + (topRight ? 5 : 0);
        if (CLOSE_AD.matcher(text).matches()) return 92 + (topRight ? 5 : 0);

        // An ID must not override a conflicting label (e.g. "watch ad to earn coins").
        boolean idLabel = text.isEmpty() || genericClose(text) || TIMER_ONLY.matcher(text).matches();
        if (idLabel && adControlId(name)) return 90 + (topRight ? 5 : 0);
        if (idLabel && BROAD_SKIP_ID.matcher(name).matches() && (topRight || adContext)) {
            return 80 + (topRight ? 5 : 0);
        }
        if (allowGenericClose && adContext && topRight && genericClose(text)) return 65;
        return 0;
    }

    /** Only short ad badges or explicit dismissal labels count as window-level evidence. */
    public static boolean mentionsAd(String label) {
        if (label == null || label.length() > MAX_LABEL_LENGTH) return false;
        String text = compact(label);
        return AD_MARKER.matcher(text).matches()
                || ((SKIP_LABEL.matcher(text).matches() || CLOSE_AD.matcher(text).matches())
                && explicitAd(text));
    }

    public static boolean isAdSpecific(String label, String viewId) {
        if (label != null && label.length() > MAX_LABEL_LENGTH) return false;
        String text = compact(label);
        String name = resourceName(viewId);
        if (unsafeText(text) || UNSAFE_ID.matcher(name).find()) return false;
        boolean actionLabel = SKIP_LABEL.matcher(text).matches() || CLOSE_AD.matcher(text).matches();
        if (actionLabel && explicitAd(text)) return true;
        return adControlId(name) && (actionLabel || text.isEmpty() || genericClose(text)
                || TIMER_ONLY.matcher(text).matches());
    }

    /** Service can suppress built-in actions while a voluntary reward video is visible. */
    public static boolean mentionsReward(String label) {
        if (label == null || label.length() > MAX_CONTEXT_LENGTH) return false;
        String text = compact(label);
        if (text.contains("继续观看") || text.contains("继续看广告")
                || text.contains("continuetowatch") || text.contains("keepwatching")) return true;
        boolean viewing = text.contains("观看") || text.contains("看广告") || text.contains("视频")
                || text.contains("watch") || text.contains("video");
        boolean forfeiture = text.contains("放弃") || text.contains("forfeit")
                || text.contains("giveup") || text.contains("lose");
        // A shop's "领取红包" promotion alone does not mean the user chose a rewarded video.
        return rewardText(text) && (viewing || forfeiture);
    }

    /** Check text and content-description separately before combining their scores. */
    public static boolean isUnsafeTarget(String label, String viewId) {
        if (label != null && label.length() > MAX_LABEL_LENGTH) return true;
        return unsafeText(compact(label)) || UNSAFE_ID.matcher(resourceName(viewId)).find();
    }

    private static boolean unsafeText(String text) {
        return rewardText(text) || text.contains("片头") || text.contains("片尾")
                || text.contains("引导") || text.contains("教程") || text.contains("登录")
                || text.contains("登入") || text.contains("intro") || text.contains("outro")
                || text.contains("tutorial") || text.contains("onboarding")
                || text.contains("signin") || text.contains("login")
                || text.equals("skipvideo") || text.equals("跳过视频");
    }

    private static boolean rewardText(String text) {
        return text.contains("奖励") || text.contains("领奖") || text.contains("领金币")
                || text.contains("赚金币") || text.contains("得金币") || text.contains("领红包")
                || text.contains("领取红包") || text.contains("领取金币")
                || text.contains("赚现金") || text.contains("领取收益")
                || text.contains("reward") || text.contains("earncoins")
                || text.contains("earnpoints") || text.contains("earncash")
                || text.contains("watchtoearn") || text.contains("继续观看")
                || text.contains("继续看广告") || text.contains("continuetowatch")
                || text.contains("keepwatching");
    }

    private static boolean explicitAd(String text) {
        return text.contains("广告") || text.contains("advertisement") || text.contains("ad");
    }

    private static boolean genericClose(String text) {
        return text.equals("关闭") || text.equals("×") || text.equals("✕")
                || text.equals("✖") || text.equals("x") || text.equals("close")
                || text.equals("关闭按钮") || text.equals("closebutton");
    }

    private static boolean adControlId(String name) {
        // Pangle splash ID verified in the upstream GKD issue and its supplied snapshot:
        // https://github.com/gkd-kit/gkd/issues/369
        return name.equals("tt_splash_skip_btn") || AD_CONTROL_ID.matcher(name).matches();
    }

    private static String resourceName(String value) {
        if (value == null || value.length() > 240) return "";
        return value.substring(value.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || c == '\u200b' || c == '\u200e' || c == '\u200f' || c == '\ufeff') continue;
            if (c >= '\uff01' && c <= '\uff5e') c -= 0xfee0;
            switch (c) {
                case '過': c = '过'; break;
                case '廣': c = '广'; break;
                case '關': c = '关'; break;
                case '閉': c = '闭'; break;
                case '開': c = '开'; break;
                case '點': c = '点'; break;
                case '擊': c = '击'; break;
                case '獎': c = '奖'; break;
                case '勵': c = '励'; break;
                case '領': c = '领'; break;
                case '幣': c = '币'; break;
                case '頻': c = '频'; break;
                case '視': c = '视'; break;
                case '頭': c = '头'; break;
                case '導': c = '导'; break;
                case '錄': c = '录'; break;
                case '繼': c = '继'; break;
                case '續': c = '续'; break;
                case '觀': c = '观'; break;
                default: break;
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}
