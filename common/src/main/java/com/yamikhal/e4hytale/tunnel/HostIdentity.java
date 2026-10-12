package com.yamikhal.e4hytale.tunnel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

// a host's Ed25519 key pair. The relay ties the host's name to the public key, the host proves ownership by signing
// the relay's challenge (see Frame), and signs again to give the name up. Stored as the 32 byte seed (RFC 8032
// private key), so a config file holds one short hex string. The public key is sent raw (32 bytes)
public final class HostIdentity {

    public static final int SEED_LENGTH = 32;
    public static final int PUBLIC_KEY_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    // what a signature covers besides its values, so a registration can't pass for a release or anything else
    private static final byte[] REGISTER = "e4hytale register v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RELEASE = "e4hytale release v2".getBytes(StandardCharsets.US_ASCII);
    // DER header of an X.509 Ed25519 public key, followed by the 32 raw key bytes (RFC 8410)
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final PrivateKey privateKey;
    private final byte[] publicKey;

    private HostIdentity(PrivateKey privateKey, byte[] publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static byte[] newSeed() {
        byte[] seed = new byte[SEED_LENGTH];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    // the key pair of a seed, the same every time (the JDK has no API for this, so the generator gets a random source
    // that hands out the seed, which it uses as the private key as RFC 8032 describes)
    public static HostIdentity fromSeed(byte[] seed) {
        if (seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("The seed must be " + SEED_LENGTH + " bytes");
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed));
            KeyPair pair = generator.generateKeyPair();
            byte[] encoded = pair.getPublic().getEncoded();
            return new HostIdentity(pair.getPrivate(), Arrays.copyOfRange(encoded, encoded.length - PUBLIC_KEY_LENGTH,
                    encoded.length));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    public byte[] publicKey() {
        return this.publicKey.clone();
    }

    // answers the relay's challenge
    public byte[] sign(long nonce, byte[] cookie) {
        return this.sign(registration(nonce, cookie));
    }

    // gives up the name of this session (see Frame.Release)
    public byte[] signRelease(long session) {
        return this.sign(release(session));
    }

    // false for a wrong signature and for anything that isn't a valid key or signature at all
    public static boolean verify(byte[] publicKey, long nonce, byte[] cookie, byte[] signature) {
        return verify(publicKey, registration(nonce, cookie), signature);
    }

    public static boolean verifyRelease(byte[] publicKey, long session, byte[] signature) {
        return verify(publicKey, release(session), signature);
    }

    private byte[] sign(byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(this.privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    private static boolean verify(byte[] publicKey, byte[] payload, byte[] signature) {
        if (publicKey.length != PUBLIC_KEY_LENGTH || signature.length != SIGNATURE_LENGTH) {
            return false;
        }

        try {
            byte[] encoded = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + PUBLIC_KEY_LENGTH);
            System.arraycopy(publicKey, 0, encoded, X509_PREFIX.length, PUBLIC_KEY_LENGTH);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (InvalidKeySpecException | InvalidKeyException | SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    private static byte[] registration(long nonce, byte[] cookie) {
        return ByteBuffer.allocate(REGISTER.length + Long.BYTES + cookie.length).put(REGISTER).putLong(nonce).put(cookie)
                .array();
    }

    private static byte[] release(long session) {
        return ByteBuffer.allocate(RELEASE.length + Long.BYTES).put(RELEASE).putLong(session).array();
    }

    // hands out the seed instead of random bytes, see fromSeed
    private static final class SeedRandom extends SecureRandom {

        private final byte[] seed;

        private SeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != this.seed.length) {
                throw new IllegalStateException("Ed25519 asked for " + bytes.length + " random bytes, expected 32");
            }

            System.arraycopy(this.seed, 0, bytes, 0, bytes.length);
        }
    }
}
