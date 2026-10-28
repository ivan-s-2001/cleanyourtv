package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Compressed and decoded image budgets, independent of the Android UI. */
final class ImageBudget {
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = System.nanoTime() + 12_000_000_000L;
        for (int n; (n = in.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new InterruptedIOException();
            if (n > MAX_BYTES - out.size()) throw new IOException("Image exceeds size budget");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    static int sample(int width, int height, int maxSide) {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 0;
        int sample = 1;
        while (sample < (1 << 30) && ((width + sample - 1L) / sample > maxSide
                || (height + sample - 1L) / sample > maxSide)) sample *= 2;
        return sample;
    }
}
