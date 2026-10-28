package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class WatchSectionTest {
    private WatchItem item(String name, int kind, String channel) {
        return new WatchItem(name, "", "app://" + name, "", channel, kind, 0, 0, 0, 0);
    }

    @Test public void continueAndNextEpisodeStayTogetherBeforeNewAndWatchlist() {
        WatchItem watching = item("watching", 0, ""), next = item("episode", 1, "");
        List<WatchSection> rows = WatchSection.group(Arrays.asList(watching, next,
                item("new", 2, ""), item("saved", 3, "")));
        assertEquals(3, rows.size());
        assertEquals(Arrays.asList(watching, next), rows.get(0).items);
        assertEquals(2, rows.get(1).kind);
        assertEquals(3, rows.get(2).kind);
    }

    @Test public void providerChannelsRemainSeparateWithoutInventingNewReleaseOrHistoryLabels() {
        List<WatchSection> rows = WatchSection.group(Arrays.asList(item("a", 4, "History"),
                item("b", 4, "Comedy"), item("c", 4, "History")));
        assertEquals(2, rows.size());
        assertEquals("History", rows.get(0).channel);
        assertEquals(4, rows.get(0).kind);
        assertEquals(2, rows.get(0).items.size());
        assertEquals("Comedy", rows.get(1).channel);
    }

    @Test public void missingPosterDoesNotRemoveAnOtherwiseUsableProgram() {
        WatchItem textOnly = item("text", 0, "");
        assertSame(textOnly, WatchSection.group(Collections.singletonList(textOnly)).get(0).items.get(0));
    }

    @Test public void emptyProviderProducesNoFakeHistoryOrOtherServiceCatalogue() {
        assertTrue(WatchSection.group(Collections.emptyList()).isEmpty());
    }
}
