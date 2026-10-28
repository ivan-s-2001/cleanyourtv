package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A published TV recommendation, not a record read from another app's private storage. */
final class WatchItem {
    final String title, episode, intentUri, posterUri, channel;
    final String description, metadata, backdropUri;
    final int type;
    final long engaged, position, duration, weight;

    WatchItem(String title, String episode, String intentUri, String posterUri, String channel,
              int type, long engaged, long position, long duration, long weight) {
        this(title, episode, intentUri, posterUri, channel, type, engaged, position, duration, weight, "", "", "");
    }

    WatchItem(String title, String episode, String intentUri, String posterUri, String channel,
              int type, long engaged, long position, long duration, long weight,
              String description, String metadata, String backdropUri) {
        this.title = title;
        this.episode = episode;
        this.intentUri = intentUri;
        this.posterUri = posterUri;
        this.channel = channel;
        this.type = type;
        this.engaged = engaged;
        this.position = position;
        this.duration = duration;
        this.weight = weight;
        this.description = description;
        this.metadata = metadata;
        this.backdropUri = backdropUri;
    }

    int progress() {
        return duration <= 0 ? 0 : (int) Math.min(100, Math.max(0, position * 100.0 / duration));
    }

    /** Recent unfinished viewing first, then next/new/watchlist and the app's ranked preview rows. */
    static List<WatchItem> select(List<WatchItem> source, long now) {
        List<WatchItem> sorted = new ArrayList<>();
        long cutoff = now - 90L * 24 * 60 * 60 * 1000;
        for (WatchItem item : source) {
            if (item.title.isEmpty() || item.intentUri.isEmpty()) continue;
            if (item.type == 0 && (item.progress() >= 98 || item.engaged > 0 && item.engaged < cutoff)) continue;
            sorted.add(item);
        }
        sorted.sort(Comparator.comparingInt((WatchItem i) -> i.type)
                .thenComparing(Comparator.comparingLong((WatchItem i) -> i.engaged).reversed())
                .thenComparing(Comparator.comparingLong((WatchItem i) -> i.weight).reversed())
                .thenComparing(i -> i.title));
        List<WatchItem> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int viewing = 0, previews = 0;
        for (WatchItem item : sorted) {
            // Leave space for both viewing and new content, even when a provider has hundreds of rows.
            if (item.type < 2 && viewing >= 6 || item.type >= 2 && previews >= 6) continue;
            if (!seen.add(item.intentUri)) continue;
            result.add(item);
            if (item.type < 2) viewing++; else previews++;
        }
        return result;
    }
}
