package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads searchable rows only, off the UI thread. No SQL selection: TvProvider forbids it for sideloads. */
final class WatchRepository {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    static final class Result {
        final List<WatchItem> items;
        final boolean unavailable;

        Result(List<WatchItem> items, boolean unavailable) {
            this.items = items;
            this.unavailable = unavailable;
        }
    }

    static Result load(ContentResolver resolver, String pkg, CancellationSignal cancellation) {
        List<WatchItem> items = new ArrayList<>();
        boolean failed = false;
        Map<Long, String> channels = new HashMap<>();
        try (Cursor c = query(resolver, TvContract.Channels.CONTENT_URI, cancellation)) {
            while (c != null && c.moveToNext()) {
                cancellation.throwIfCanceled();
                if (pkg.equals(text(c, "package_name"))) channels.put(number(c, "_id"), text(c, "display_name"));
            }
        } catch (OperationCanceledException e) {
            throw e;
        } catch (RuntimeException ignored) {
            // Channel names are optional; they must not stop Watch Next or previews from loading.
        }
        Uri[] tables = {TvContract.WatchNextPrograms.CONTENT_URI, TvContract.PreviewPrograms.CONTENT_URI};
        for (int table = 0; table < tables.length; table++) {
            try (Cursor c = query(resolver, tables[table], cancellation)) {
                if (c == null) { failed = true; continue; }
                while (c.moveToNext()) {
                    cancellation.throwIfCanceled();
                    if (!pkg.equals(text(c, "package_name"))) continue;
                    long position = number(c, "last_playback_position_millis");
                    items.add(new WatchItem(text(c, "title"), text(c, "episode_title"),
                            text(c, "intent_uri"), text(c, "poster_art_uri"),
                            table == 0 ? "" : channels.getOrDefault(number(c, "channel_id"), ""),
                            table == 0 ? (int) number(c, "watch_next_type") : position > 0 ? 0 : 4,
                            number(c, "last_engagement_time_utc_millis"), position,
                            number(c, "duration_millis"), number(c, "weight")));
                }
            } catch (OperationCanceledException e) {
                throw e;
            } catch (RuntimeException ignored) {
                failed = true;
            }
        }
        return new Result(WatchItem.select(items, System.currentTimeMillis()), failed);
    }

    private static Cursor query(ContentResolver resolver, Uri uri, CancellationSignal cancellation) {
        return resolver.query(uri, null, null, null, null, cancellation);
    }

    private static String text(Cursor c, String key) {
        int i = c.getColumnIndex(key);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long number(Cursor c, String key) {
        int i = c.getColumnIndex(key);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
