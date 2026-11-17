// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.ArrayList;
import java.util.List;

/** The last alerts Valtrin sent (this game session), so /flip recent can show a flip you missed in the chat scroll. */
final class AlertHistory {
    private AlertHistory() {}

    record Entry(String uuid, String name, String tier, long price, long profit, long at) {}

    private static final int MAX = 25;
    private static final List<Entry> LIST = new ArrayList<>();

    static synchronized void add(String uuid, String name, String tier, long price, long profit, long at) {
        if (!Validate.uuid(uuid)) return;
        LIST.add(0, new Entry(uuid, Validate.text(name == null ? "" : name.replaceAll("§.", ""), 80), tier, price, profit, at));
        while (LIST.size() > MAX) LIST.remove(LIST.size() - 1);
    }

    /** Newest first. */
    static synchronized List<Entry> recent(int n) {
        return new ArrayList<>(LIST.subList(0, Math.min(n, LIST.size())));
    }
}
