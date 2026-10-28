package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Event-driven timers. Nothing scans processes or persists a history of opened apps. */
final class AppAutomationPolicy {
    static final long BACKGROUND_DELAY_MS = 5 * 60 * 1000L;
    private final Map<String, Long> deadlines = new HashMap<>();
    private Set<String> vpnApps;
    private String foreground = "";
    private boolean vpnRequested;

    AppAutomationPolicy(Set<String> vpnApps) { setVpnApps(vpnApps); }

    /** Reconfigure without losing the foreground, tunnel state or independent cleanup deadlines. */
    void setVpnApps(Set<String> selected) { vpnApps = new HashSet<>(selected); }

    void foreground(String pkg, long now, boolean cleanup) {
        if (pkg.equals(foreground)) return;
        if (cleanup && !foreground.isEmpty()) deadlines.put(foreground, now + BACKGROUND_DELAY_MS);
        foreground = pkg;
        deadlines.remove(pkg);     // Returning cancels that app's timer, including an already due one.
    }

    /** 1 connects, -1 disconnects, 0 leaves Happ alone. Separate commands, never a blind toggle. */
    int vpnCommand(boolean enabled) {
        boolean wanted = enabled && vpnApps.contains(foreground);
        if (wanted == vpnRequested) return 0;
        vpnRequested = wanted;
        return wanted ? 1 : -1;
    }

    List<String> takeDue(long now) {
        List<String> result = new ArrayList<>();
        if (foreground.isEmpty()) return result; // Screen off / unknown foreground: do not guess.
        for (Map.Entry<String, Long> entry : deadlines.entrySet()) {
            if (entry.getValue() <= now && !entry.getKey().equals(foreground)) result.add(entry.getKey());
        }
        for (String pkg : result) deadlines.remove(pkg);
        return result;
    }

    long nextDeadline() {
        if (foreground.isEmpty()) return Long.MAX_VALUE;
        long next = Long.MAX_VALUE;
        for (long time : deadlines.values()) next = Math.min(next, time);
        return next;
    }

    void cancelCleanup() { deadlines.clear(); }
}
