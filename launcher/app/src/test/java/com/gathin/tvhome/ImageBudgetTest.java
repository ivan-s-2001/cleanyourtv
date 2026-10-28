package com.gathin.tvhome;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import static org.junit.Assert.*;

public class ImageBudgetTest {
    @Test public void readsSmallAndEmptyImagesWithoutPadding() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, ImageBudget.read(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertEquals(0, ImageBudget.read(new ByteArrayInputStream(new byte[0])).length);
    }

    @Test public void exactBudgetIsAllowed() throws Exception {
        assertEquals(ImageBudget.MAX_BYTES, ImageBudget.read(new ByteArrayInputStream(new byte[ImageBudget.MAX_BYTES])).length);
    }

    @Test public void oversizedStreamIsRejectedWithoutReadingTheEntireBody() throws Exception {
        final int[] read = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { read[0]++; return 1; }
            @Override public int read(byte[] b) { read[0] += b.length; return b.length; }
        };
        try { ImageBudget.read(endless); fail("Oversized download accepted"); }
        catch (IOException expected) { assertTrue(read[0] <= ImageBudget.MAX_BYTES + 8192); }
    }

    @Test public void interruptedWorkerDoesNotKeepDownloading() throws Exception {
        Thread.currentThread().interrupt();
        try {
            ImageBudget.read(new ByteArrayInputStream(new byte[]{1}));
            fail("Cancelled download accepted");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void downsamplingBoundsTallWideAndRoundingEdgeCases() {
        assertEquals(1, ImageBudget.sample(500, 500, 512));
        assertEquals(2, ImageBudget.sample(513, 500, 512));
        assertEquals(8, ImageBudget.sample(4000, 6000, 1280));
        assertEquals(16, ImageBudget.sample(4097, 1, 512));
        assertEquals(0, ImageBudget.sample(0, 200, 512));
        assertEquals(0, ImageBudget.sample(20, -1, 512));
        assertEquals(0, ImageBudget.sample(20, 20, 0));
        assertTrue(ImageBudget.sample(Integer.MAX_VALUE, Integer.MAX_VALUE, 512) > 0);
    }
}
