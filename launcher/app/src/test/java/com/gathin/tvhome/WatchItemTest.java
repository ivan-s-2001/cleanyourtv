package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class WatchItemTest {
    private static final long NOW = 1_800_000_000_000L;
    private WatchItem item(String title, String uri, int type, long date, long position, long duration, long weight) {
        return new WatchItem(title, "", uri, "", "", type, date, position, duration, weight);
    }

    @Test public void unfinishedRecentViewingLeadsAndDuplicatePreviewIsRemoved() {
        WatchItem preview = item("preview", "app://one", 4, 0, 0, 0, 500);
        WatchItem viewing = item("viewing", "app://one", 0, NOW - 10, 50, 100, 0);
        WatchItem newest = item("newest", "app://two", 0, NOW, 20, 100, 0);
        List<WatchItem> result = WatchItem.select(Arrays.asList(preview, viewing, newest), NOW);
        assertEquals(Arrays.asList(newest, viewing), result);
    }

    @Test public void hidesCompletedStaleAndUnlaunchableButKeepsUndatedPreviewViewing() {
        WatchItem undated = item("preview viewing", "app://undated", 0, 0, 30, 100, 0);
        List<WatchItem> result = WatchItem.select(Arrays.asList(
                item("completed", "app://done", 0, NOW, 99, 100, 0),
                item("stale", "app://old", 0, NOW - 91L * 86400000, 10, 100, 0),
                item("broken", "", 2, NOW, 0, 0, 0),
                undated), NOW);
        assertEquals(Arrays.asList(undated), result);
    }

    @Test public void reservesRoomForNewContentAndHonoursPreviewWeights() {
        List<WatchItem> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            input.add(item("viewing " + i, "app://watch/" + i, 0, NOW - i, 10, 100, 0));
            input.add(item("preview " + i, "app://preview/" + i, 4, 0, 0, 0, i));
        }
        List<WatchItem> result = WatchItem.select(input, NOW);
        assertEquals(12, result.size());
        assertEquals("preview 19", result.get(6).title);
        assertEquals("viewing 0", result.get(0).title);
    }

    @Test public void progressHandlesMissingDurationNegativeAndLargeValues() {
        assertEquals(0, item("", "", 0, 0, 15, 0, 0).progress());
        assertEquals(0, item("", "", 0, 0, -10, 100, 0).progress());
        assertEquals(100, item("", "", 0, 0, Long.MAX_VALUE, Long.MAX_VALUE, 0).progress());
        assertEquals(100, item("", "", 0, 0, 120, 100, 0).progress());
    }
}
