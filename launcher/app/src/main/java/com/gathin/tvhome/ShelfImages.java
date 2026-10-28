package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** Only the visible shelf loads pictures; two workers, bounded cache/downloads, no cookies or disk history. */
final class ShelfImages {
    static final String NETWORK_KEY = "watch_network_images";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    // Main-thread maps: at most one queued/running job per visible image, even while a D-pad is held.
    private final Map<String, List<Consumer<Bitmap>>> pending = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private volatile int generation;

    ShelfImages(Context context) { this.context = context.getApplicationContext(); }

    void load(String address, boolean wide, Consumer<Bitmap> ready) {
        if (address.isEmpty() || workers.isShutdown()) return;
        int token = generation;
        String key = (wide ? "wide:" : "card:") + address;
        Bitmap hit = cache.get(key);
        if (hit != null) { ready.accept(hit); return; }
        if (failed.contains(key)) return;
        List<Consumer<Bitmap>> callbacks = pending.get(key);
        if (callbacks != null) {
            // There is only one backdrop view; keep its latest focus callback.
            if (wide) callbacks.clear();
            callbacks.add(ready);
            return;
        }
        callbacks = new ArrayList<>();
        callbacks.add(ready);
        pending.put(key, callbacks);
        workers.execute(() -> {
            if (token != generation) return;
            Bitmap bitmap = read(address, wide ? 1280 : 512);
            if (token != generation) return;
            if (bitmap != null) cache.put(key, bitmap);
            main.post(() -> {
                if (token != generation) return;
                List<Consumer<Bitmap>> waiting = pending.remove(key);
                if (bitmap == null) { failed.add(key); return; }
                if (waiting != null) for (Consumer<Bitmap> callback : waiting) callback.accept(bitmap);
            });
        });
    }

    void cancel() {
        generation++;
        pending.clear();
        failed.clear();
        workers.getQueue().clear();
        for (HttpURLConnection connection : active) connection.disconnect();
        main.removeCallbacksAndMessages(null);
    }

    void close() {
        cancel();
        workers.shutdownNow();
        cache.evictAll();
    }

    private Bitmap read(String address, int maxSide) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(address);
            byte[] data;
            if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) return null;
                    data = ImageBudget.read(in);
                }
            } else {
                if (!context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(NETWORK_KEY, true)) return null;
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return null;
                // Redirects are checked individually; never follow one into a local file/content URI.
                URL url = new URL(address);
                for (int redirect = 0; ; redirect++) {
                    if (!"https".equals(url.getProtocol()) && !"http".equals(url.getProtocol())) return null;
                    connection = (HttpURLConnection) url.openConnection();
                    active.add(connection);
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(4000);
                    connection.setReadTimeout(4000);
                    connection.setUseCaches(false);
                    int status = connection.getResponseCode();
                    if (status >= 300 && status <= 399) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || redirect >= 3) return null;
                        url = new URL(url, location);
                        active.remove(connection);
                        connection.disconnect();
                        continue;
                    }
                    if (status != 200 || connection.getContentLengthLong() > ImageBudget.MAX_BYTES) return null;
                    try (InputStream in = connection.getInputStream()) { data = ImageBudget.read(in); }
                    break;
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int sample = ImageBudget.sample(options.outWidth, options.outHeight, maxSide);
            if (sample == 0) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception | OutOfMemoryError ignored) {
            // A missing/oversized poster keeps the text card usable.
            return null;
        } finally {
            if (connection != null) { active.remove(connection); connection.disconnect(); }
        }
    }
}
