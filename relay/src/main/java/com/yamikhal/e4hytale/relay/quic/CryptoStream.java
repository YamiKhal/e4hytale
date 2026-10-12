package com.yamikhal.e4hytale.relay.quic;

import java.util.Arrays;

// the start of a client's Initial CRYPTO stream, put together from frames that may arrive in any order and overlap
// (a ClientHello with post-quantum key shares spans two or more packets). Only the contiguous prefix matters
public final class CryptoStream {

    // a ClientHello is a few kB at most. A stream that grows past this is an attack, not a handshake
    public static final int MAX_LENGTH = 16 * 1024;

    private final byte[] data = new byte[MAX_LENGTH];
    private final boolean[] filled = new boolean[MAX_LENGTH];
    private int contiguous;

    // false if the frame reaches past MAX_LENGTH
    public boolean add(long offset, byte[] bytes) {
        if (offset < 0 || offset + bytes.length > MAX_LENGTH) return false;

        int start = (int) offset;
        System.arraycopy(bytes, 0, this.data, start, bytes.length);
        Arrays.fill(this.filled, start, start + bytes.length, true);
        while (this.contiguous < MAX_LENGTH && this.filled[this.contiguous]) {
            this.contiguous++;
        }

        return true;
    }

    // the bytes received without a gap from offset 0
    public byte[] prefix() {
        return Arrays.copyOf(this.data, this.contiguous);
    }
}
