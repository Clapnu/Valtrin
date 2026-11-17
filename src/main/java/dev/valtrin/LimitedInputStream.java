// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stops a decompression bomb or an endless answer: reading more than {@code max} bytes fails. */
final class LimitedInputStream extends FilterInputStream {
    private long left;

    LimitedInputStream(InputStream in, long max) { super(in); left = max; }

    @Override public int read() throws IOException {
        if (left <= 0) throw new IOException("data too large");
        int b = super.read();
        if (b >= 0) left--;
        return b;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        if (left <= 0) throw new IOException("data too large");
        int n = super.read(b, off, (int) Math.min(len, left));
        if (n > 0) left -= n;
        return n;
    }

    @Override public long skip(long n) throws IOException {
        if (n > left) throw new IOException("data too large");
        long k = super.skip(n);
        if (k > 0) left -= k;
        return k;
    }
}
