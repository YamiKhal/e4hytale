package com.yamikhal.e4hytale.tunnel;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostIdentityTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void derivesThePublicKeysOfRfc8032Section7_1() {
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", HEX.formatHex(HostIdentity
                .fromSeed(HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")).publicKey()));
        assertEquals("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", HEX.formatHex(HostIdentity
                .fromSeed(HEX.parseHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")).publicKey()));
    }

    @Test
    void sameSeedGivesTheSameIdentity() {
        byte[] seed = HostIdentity.newSeed();
        assertArrayEquals(HostIdentity.fromSeed(seed).publicKey(), HostIdentity.fromSeed(seed).publicKey());
    }

    @Test
    void signaturesVerifyOnlyForTheirKeyNonceAndCookie() {
        HostIdentity identity = HostIdentity.fromSeed(HostIdentity.newSeed());
        byte[] cookie = new byte[Frame.COOKIE_LENGTH];
        cookie[3] = 7;
        byte[] signature = identity.sign(42L, cookie);
        assertTrue(HostIdentity.verify(identity.publicKey(), 42L, cookie, signature));

        assertFalse(HostIdentity.verify(identity.publicKey(), 43L, cookie, signature));
        byte[] otherCookie = cookie.clone();
        otherCookie[0] = 1;
        assertFalse(HostIdentity.verify(identity.publicKey(), 42L, otherCookie, signature));
        byte[] otherKey = HostIdentity.fromSeed(HostIdentity.newSeed()).publicKey();
        assertFalse(HostIdentity.verify(otherKey, 42L, cookie, signature));
        byte[] tampered = signature.clone();
        tampered[10] ^= 1;
        assertFalse(HostIdentity.verify(identity.publicKey(), 42L, cookie, tampered));
    }

    @Test
    void rejectsGarbageInsteadOfThrowing() {
        assertFalse(HostIdentity.verify(new byte[32], 1L, new byte[16], new byte[64]));
        assertFalse(HostIdentity.verify(new byte[31], 1L, new byte[16], new byte[64]));
        assertFalse(HostIdentity.verify(new byte[32], 1L, new byte[16], new byte[63]));
        assertThrows(IllegalArgumentException.class, () -> HostIdentity.fromSeed(new byte[16]));
    }
}
