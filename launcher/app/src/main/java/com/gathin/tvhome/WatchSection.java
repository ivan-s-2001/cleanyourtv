package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sections describe the publishing app's data; a channel is never relabelled as a private history. */
final class WatchSection {
    final int kind;
    final String channel;
    final List<WatchItem> items = new ArrayList<>();

    private WatchSection(int kind, String channel) {
        this.kind = kind;
        this.channel = channel;
    }

    static List<WatchSection> group(List<WatchItem> items) {
        Map<String, WatchSection> sections = new LinkedHashMap<>();
        for (WatchItem item : items) {
            int kind = item.type < 2 ? 0 : item.type == 2 ? 2 : item.type == 3 ? 3 : 4;
            String channel = kind == 4 ? item.channel.trim() : "";
            String key = kind + ":" + channel;
            WatchSection section = sections.get(key);
            if (section == null) {
                section = new WatchSection(kind, channel);
                sections.put(key, section);
            }
            section.items.add(item);
        }
        return new ArrayList<>(sections.values());
    }
}
