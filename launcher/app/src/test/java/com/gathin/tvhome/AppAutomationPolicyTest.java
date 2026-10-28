package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class AppAutomationPolicyTest {
    private AppAutomationPolicy policy() {
        return new AppAutomationPolicy(new HashSet<>(Arrays.asList("youtube", "smarttube")));
    }

    @Test public void connectsOnYoutubeEntryAndDisconnectsOnAnyRealExit() {
        AppAutomationPolicy p = policy();
        p.foreground("home", 0, false);
        assertEquals(0, p.vpnCommand(true));
        p.foreground("youtube", 10, false);
        assertEquals(1, p.vpnCommand(true));
        assertEquals(0, p.vpnCommand(true));
        p.foreground("other", 20, false);
        assertEquals(-1, p.vpnCommand(true));
        assertEquals(0, p.vpnCommand(true));
    }

    @Test public void movingWithinYoutubeClientsDoesNotToggleTheTunnel() {
        AppAutomationPolicy p = policy();
        p.foreground("youtube", 0, false);
        assertEquals(1, p.vpnCommand(true));
        p.foreground("smarttube", 10, false);
        assertEquals(0, p.vpnCommand(true));
        p.foreground("", 20, false);
        assertEquals(-1, p.vpnCommand(true));
    }

    @Test public void disablingAutomationDisconnectsOnlyOnce() {
        AppAutomationPolicy p = policy();
        assertEquals(0, p.vpnCommand(false));
        p.foreground("youtube", 0, false);
        assertEquals(1, p.vpnCommand(true));
        assertEquals(-1, p.vpnCommand(false));
        assertEquals(0, p.vpnCommand(false));
    }

    @Test public void requestsCleanupOnlyAfterFiveMinutesInTheBackground() {
        AppAutomationPolicy p = policy();
        p.foreground("one", 0, true);
        p.foreground("home", 100, true);
        assertTrue(p.takeDue(300099).isEmpty());
        assertEquals(Arrays.asList("one"), p.takeDue(300100));
        assertTrue(p.takeDue(900000).isEmpty());
    }

    @Test public void returningCancelsEvenAnAlreadyDueTimerAndAnotherExitRestartsIt() {
        AppAutomationPolicy p = policy();
        p.foreground("one", 0, true);
        p.foreground("home", 100, true);
        p.foreground("one", 400000, true);
        assertTrue(p.takeDue(400000).isEmpty());
        p.foreground("home", 400001, true);
        assertTrue(p.takeDue(700000).isEmpty());
        assertEquals(Arrays.asList("one"), p.takeDue(700001));
    }

    @Test public void multipleAppsHaveIndependentDeadlinesAndRepeatedActivityEventsDoNotExtendThem() {
        AppAutomationPolicy p = policy();
        p.foreground("one", 0, true);
        p.foreground("two", 100, true);
        p.foreground("two", 200, true);
        p.foreground("home", 1000, true);
        assertEquals(300100, p.nextDeadline());
        assertEquals(Arrays.asList("one"), p.takeDue(300100));
        assertEquals(301000, p.nextDeadline());
        assertEquals(Arrays.asList("two"), p.takeDue(301000));
    }

    @Test public void screenOffWaitsUntilForegroundIsKnownAndResumingAppCancelsItsTimer() {
        AppAutomationPolicy p = policy();
        p.foreground("one", 0, true);
        p.foreground("", 100, true);
        assertEquals(Long.MAX_VALUE, p.nextDeadline());
        assertTrue(p.takeDue(900000).isEmpty());
        p.foreground("one", 900000, true);
        assertTrue(p.takeDue(900000).isEmpty());
    }

    @Test public void disablingCleanupCancelsAllPendingWork() {
        AppAutomationPolicy p = policy();
        p.foreground("one", 0, true);
        p.foreground("home", 100, true);
        p.cancelCleanup();
        assertTrue(p.takeDue(900000).isEmpty());
        assertEquals(Long.MAX_VALUE, p.nextDeadline());
    }
}
