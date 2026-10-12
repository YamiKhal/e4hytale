package com.yamikhal.e4hytale.relay.quic;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

// the packet protection keys of QUIC v1 Initial packets (RFC 9001 section 5.2). They come from the client's first
// destination connection id and a public salt, so anyone on the path can derive them: Initial packets are only
// obfuscated, not secret. That is what lets the relay read the server name of a joiner's ClientHello
public record InitialKeys(byte[] key, byte[] iv, byte[] hp) {

    public static final int VERSION_1 = 0x00000001;

    private static final byte[] SALT_V1 = HexFormat.of().parseHex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a");

    // keys of the client's packets for a connection that started with this destination connection id
    public static InitialKeys client(byte[] dcid) {
        return derive(dcid, "client in");
    }

    // keys of the server's packets, used to refuse a joiner with a CONNECTION_CLOSE it can read
    public static InitialKeys server(byte[] dcid) {
        return derive(dcid, "server in");
    }

    // the header protection mask for a 16 byte sample (RFC 9001 section 5.4.3)
    public byte[] mask(byte[] sample) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(this.hp, "AES"));
            return cipher.doFinal(sample);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is not available", e);
        }
    }

    // the AEAD nonce of a packet number: the iv with the number xored into its last bytes
    public byte[] nonce(long packetNumber) {
        byte[] nonce = this.iv.clone();
        for (int i = 0; i < 8; i++) {
            nonce[nonce.length - 1 - i] ^= (byte) (packetNumber >>> (8 * i));
        }

        return nonce;
    }

    private static InitialKeys derive(byte[] dcid, String label) {
        byte[] initial = hmac(SALT_V1, dcid);
        byte[] secret = expandLabel(initial, label, 32);
        return new InitialKeys(expandLabel(secret, "quic key", 16), expandLabel(secret, "quic iv", 12),
                expandLabel(secret, "quic hp", 16));
    }

    // HKDF-Expand-Label from TLS 1.3 (RFC 8446 section 7.1) with an empty context. Every length here fits one
    // HMAC-SHA256 block, so HKDF-Expand is a single round
    static byte[] expandLabel(byte[] secret, String label, int length) {
        byte[] fullLabel = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write(length >>> 8);
        info.write(length);
        info.write(fullLabel.length);
        info.writeBytes(fullLabel);
        info.write(0);
        info.write(1);
        byte[] block = hmac(secret, info.toByteArray());
        byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
