package com.qingtiao.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkipClassifierTest {
    @Test
    public void matchesChineseEnglishAndTraditionalSkipLabels() {
        for (String label : new String[] {"跳过", "略过", "跳过广告", "跳過廣告", "略過此廣告",
                "點擊跳過", "点击跳过开屏广告", "Skip", "Skip Ad", "SKIP ADS",
                "Skip this advertisement", "关闭此广告", "關閉廣告", "Close this ad", "Dismiss ads",
                "跳过视频广告", "跳過此視頻廣告", "Skip video ad", "Skip this video ad"}) {
            assertTrue(label, SkipClassifier.score(label, "", true, false, false) > 0);
        }
    }

    @Test
    public void supportsCountdownBeforeOrAfterWithSeparatorsAndUnicodeSpacing() {
        for (String label : new String[] {"跳过广告 5 秒", "Skip Ad 5s", "5s | Skip Ad", "５秒｜跳過",
                "跳过（5秒）", "(5s) 跳过", "跳过 [3]", "广告 · 跳过", "广告 5 秒 | 跳过",
                "Skip : 5 seconds", "跳\u200b过\u00a0３ｓ", "5 / 跳过", "3秒－跳过广告",
                "ＳＫＩＰ　ＡＤ　（５Ｓ）", "跳过\n5", "Ads | Skip", "跳过广告（0秒）"}) {
            assertTrue(label, SkipClassifier.score(label, "", true, false, false) > 0);
        }
    }

    @Test
    public void countdownDoesNotImplyAdSpecificUnlessAdIsNamed() {
        assertFalse(SkipClassifier.isAdSpecific("5秒 | 跳过", ""));
        assertTrue(SkipClassifier.isAdSpecific("5秒 | 跳过广告", ""));
        assertTrue(SkipClassifier.isAdSpecific("跳過廣告", ""));
        assertTrue(SkipClassifier.isAdSpecific("Skip this ad", ""));
    }

    @Test
    public void supportsPreciseAdIdsAndSdkIdWithoutDependingOnHostPackage() {
        for (String id : new String[] {"skip_ad", "ad_skip", "btn_skip_ad", "splash_skip_button",
                "ad_close", "iv_ad_close", "close_ad_button", "splash_ad_skip", "skipads",
                "tt_splash_skip_btn"}) {
            assertTrue(id, SkipClassifier.score("", "com.demo:id/" + id, false, false, false) > 0);
            assertTrue(id, SkipClassifier.isAdSpecific("", "com.demo:id/" + id));
        }
        assertTrue(SkipClassifier.score("5", "com.byted.pangle:id/tt_splash_skip_btn",
                false, false, false) > 0);
        assertTrue(SkipClassifier.score("×", "com.demo:id/ad_close", false, false, false) > 0);
    }

    @Test
    public void idsDoNotMatchArbitrarySubstringsOrNonAdSkipControls() {
        for (String id : new String[] {"skip_intro", "skip_video", "tutorial_skip", "login_skip",
                "download_skip_status", "my_skip_setting", "ad_close_setting", "close_ad_preferences",
                "ad_container", "iv_close", "close", "skip_countdown_label", "tt_splash_skip_btn_fake"}) {
            assertEquals(id, 0, SkipClassifier.score("", "com.demo:id/" + id, true, true, true));
            assertFalse(id, SkipClassifier.isAdSpecific("", "com.demo:id/" + id));
        }
        assertEquals(0, SkipClassifier.score("", "com.demo:id/skip", false, false, false));
        assertTrue(SkipClassifier.score("", "com.demo:id/btn_skip", true, false, false) > 0);
        assertTrue(SkipClassifier.score("", "com.demo:id/layout_skip", true, false, false) > 0);
        assertTrue(SkipClassifier.score("", "com.demo:id/skipTextView", true, false, false) > 0);
        assertEquals(0, SkipClassifier.score("", "com.demo:id/layout_skip", false, false, false));
        assertFalse(SkipClassifier.isAdSpecific("", "com.demo:id/btn_skip"));
    }

    @Test
    public void rejectsBodyTextNonAdVideoAndFutureSkipPromises() {
        for (String label : new String[] {"如何跳过广告的教程", "开通会员可跳过广告", "广告跳过设置",
                "Skip intro", "跳过片头", "跳過片頭", "跳过视频", "Skip video", "跳过教程", "跳过登录",
                "跳过新手引导", "Skip in 5 seconds", "5秒后可跳过", "跳过广告教程", "点击领取",
                "跳过广告并下载", "Skip ad and install", "Close account", "Skipper"}) {
            assertEquals(label, 0, SkipClassifier.score(label, "", true, true, true));
            assertEquals(label, 0, SkipClassifier.score(label, "com.demo:id/skip_ad", true, true, false));
        }
        assertEquals(0, SkipClassifier.score("5", "", true, true, true));
    }

    @Test
    public void neverUsesRewardLabelsOrConflictingContentAsIdFallback() {
        for (String label : new String[] {"观看广告领取奖励", "跳过并放弃奖励", "继续观看", "跳过领金币",
                "Skip ad to earn coins", "Skip and claim reward", "Keep watching", "Watch to earn",
                "領取獎勵", "繼續觀看", "点击跳过广告领取红包"}) {
            assertTrue(label, SkipClassifier.isUnsafeTarget(label, ""));
            assertEquals(label, 0, SkipClassifier.score(label, "com.demo:id/skip_ad", true, true, true));
            assertFalse(label, SkipClassifier.isAdSpecific(label, "com.demo:id/skip_ad"));
        }
        assertEquals(0, SkipClassifier.score("跳过广告", "com.demo:id/reward_skip", true, true, true));
        assertTrue(SkipClassifier.isUnsafeTarget("跳过", "com.demo:id/tutorial_skip"));
    }

    @Test
    public void rewardContextRequiresVoluntaryViewingOrForfeitureEvidence() {
        for (String label : new String[]{"观看广告领取奖励", "看广告领金币", "跳过并放弃奖励",
                "繼續觀看", "Keep watching", "Watch to earn coins", "Rewarded video"}) {
            assertTrue(label, SkipClassifier.mentionsReward(label));
        }
        for (String label : new String[]{"领取红包", "领取奖励", "限时奖励", "Rewards"}) {
            assertFalse(label, SkipClassifier.mentionsReward(label));
            assertTrue(label, SkipClassifier.isUnsafeTarget(label, ""));
        }
    }

    @Test
    public void genericCloseNeedsExplicitOptionAndAdContextAndCorner() {
        for (String label : new String[] {"关闭", "關閉", "×", "✕", "Close", "关闭按钮"}) {
            assertEquals(label, 0, SkipClassifier.score(label, "", true, true, false));
            assertEquals(label, 0, SkipClassifier.score(label, "", true, false, true));
            assertEquals(label, 0, SkipClassifier.score(label, "", false, true, true));
            assertEquals(label, 65, SkipClassifier.score(label, "", true, true, true));
        }
    }

    @Test
    public void adEvidenceExcludesArticlesTutorialsAndSubstrings() {
        for (String label : new String[] {"广告", "廣告", "AD", "Sponsored", "广告剩余 5 秒", "跳过广告"}) {
            assertTrue(label, SkipClassifier.mentionsAd(label));
        }
        for (String label : new String[] {"广告规则设置", "如何跳过广告", "今天看到一个广告", "download",
                "shadow", "跳过", "跳过片头", "观看广告领取奖励"}) {
            assertFalse(label, SkipClassifier.mentionsAd(label));
        }
    }

    @Test
    public void boundsWorkOnLongContentAndNullValues() {
        StringBuilder body = new StringBuilder("跳过广告");
        for (int i = 0; i < 20000; i++) body.append(' ');
        assertEquals(0, SkipClassifier.score(body.toString(), "skip_ad", true, true, true));
        assertFalse(SkipClassifier.mentionsAd(body.toString()));
        assertFalse(SkipClassifier.isAdSpecific(body.toString(), "skip_ad"));
        assertEquals(0, SkipClassifier.score(null, null, true, true, true));
        assertFalse(SkipClassifier.mentionsAd(null));
        assertFalse(SkipClassifier.mentionsReward(null));
        assertFalse(SkipClassifier.isUnsafeTarget(null, null));
    }
}
