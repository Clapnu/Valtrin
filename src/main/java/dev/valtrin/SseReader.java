// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a server-sent-events stream (text/event-stream) with hard limits, because the stream comes from a network.
 * A line longer than {@link #MAX_LINE} or an event with more data than {@link #MAX_EVENT} ends the stream with an error.
 * Comment lines (": ...") are skipped but count as activity, so a quiet connection can be told from a dead one.
 */
final class SseReader {
    static final int MAX_LINE = 32 * 1024;
    static final int MAX_EVENT = 32 * 1024;

    record Event(String id, String type, String data) {}

    private final InputStream in;
    /** Time of the last byte received (ms since 1970), for a "no news from the server" watchdog. */
    volatile long lastActivityAt = System.currentTimeMillis();

    SseReader(InputStream in) {
        this.in = in;
    }

    /** The next complete event, or null when the stream ended. */
    Event next() throws IOException {
        String id = null, type = null;
        StringBuilder data = null;
        while (true) {
            String line = readLine();
            if (line == null) return null; // end of stream; an unfinished event is dropped
            if (line.isEmpty()) {
                if (data != null) return new Event(id, type == null ? "message" : type, data.toString());
                id = null;
                type = null;
                continue;
            }
            if (line.charAt(0) == ':') continue;
            int c = line.indexOf(':');
            String field = c < 0 ? line : line.substring(0, c);
            String value = c < 0 ? "" : line.substring(c + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> type = value.length() > 40 ? null : value;
                case "id" -> id = value.length() > 80 ? null : value;
                case "data" -> {
                    if (data == null) data = new StringBuilder();
                    else data.append('\n');
                    data.append(value);
                    if (data.length() > MAX_EVENT) throw new IOException("event too large");
                }
                default -> { /* retry: and unknown fields are ignored */ }
            }
        }
    }

    /** One line without its line ending; null at end of stream. */
    private String readLine() throws IOException {
        byte[] buf = new byte[256];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) return n == 0 ? null : new String(buf, 0, n, StandardCharsets.UTF_8);
            lastActivityAt = System.currentTimeMillis();
            if (b == '\n') {
                if (n > 0 && buf[n - 1] == '\r') n--;
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            if (n >= MAX_LINE) throw new IOException("line too long");
            if (n == buf.length) buf = java.util.Arrays.copyOf(buf, Math.min(MAX_LINE, buf.length * 2));
            buf[n++] = (byte) b;
        }
    }
}
