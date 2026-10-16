package com.qingtiao.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClickGuardTest {
    @Test public void differentCloseButtonIsDeferredRatherThanDropped() {
        ClickGuard guard = new ClickGuard();
        guard.record("skip", 1000);
        assertEquals(350, guard.waitMillis("close", 1100));
        assertEquals(0, guard.waitMillis("close", 1450));
    }

    @Test public void unchangedTargetHasFiniteBackoffAndCanBeUsedInLaterAd() {
        ClickGuard guard = new ClickGuard();
        guard.record("skip", 1000);
        assertEquals(800, guard.waitMillis("skip", 1100));
        guard.record("skip", 1900);
        assertEquals(0, guard.waitMillis("skip", 3900));
        guard.record("skip", 3900);
        assertEquals(4000, guard.waitMillis("skip", 3900));
        guard.record("skip", 7900);
        assertEquals(-1, guard.waitMillis("skip", 20000));
        assertEquals(0, guard.waitMillis("skip", 31000));
    }
}
