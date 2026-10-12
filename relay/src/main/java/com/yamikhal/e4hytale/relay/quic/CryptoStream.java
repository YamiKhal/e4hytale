package com.yamikhal.e4hytale.relay.quic;

import java.util.Arrays;
import java.util.BitSet;

// the start of a client's Initial CRYPTO stream, put together from frames that may arrive in any order and overlap
// (a ClientHello with post-quantum key shares spans two or more packets). Only the contiguous prefix matters. Grows
// with the data, so a joiner that sends little costs little
public final class CryptoStream {

    // a ClientHello is a few kB at most. A stream that grows past this is an attack, not a handshake
    public static final int MAX_LENGTH = 16 * 1024;

    private final BitSet filled = new BitSet();
    private byte[] data = new byte[2048];
    private int contiguous;

    // false if the frame reaches past MAX_LENGTH
    public boolean add(long offset, byte[] bytes) {
        if (offset < 0 || offset + bytes.length > MAX_LENGTH) {
            return false;
        }

        int start = (int) offset;
        int end = start + bytes.length;
        if (end > this.data.length) {
            this.data = Arrays.copyOf(this.data, Math.min(MAX_LENGTH, Math.max(end, this.data.length * 2)));
        }

        System.arraycopy(bytes, 0, this.data, start, bytes.length);
        this.filled.set(start, end);
        this.contiguous = this.filled.nextClearBit(0);
        return true;
    }

    // the bytes received without a gap from offset 0
    public byte[] prefix() {
        return Arrays.copyOf(this.data, this.contiguous);
    }
}
