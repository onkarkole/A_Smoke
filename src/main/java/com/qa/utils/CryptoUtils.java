package com.qa.utils;


import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoUtils {
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_LEN = 12;
    private static final SecureRandom RNG = new SecureRandom();

    private CryptoUtils() {}

    // Minimal HKDF-SHA256
    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) { return hmac(salt, ikm); }
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {
        byte[] t = new byte[0];
        ByteBuffer out = ByteBuffer.allocate(len);
        byte counter = 1;
        while (out.remaining() > 0) {
            byte[] input = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(info, 0, input, t.length, info.length);
            input[input.length - 1] = counter++;
            t = hmac(prk, input);
            int toCopy = Math.min(out.remaining(), t.length);
            out.put(t, 0, toCopy);
        }
        return out.array();
    }

    public static DerivedKeys deriveKeys(byte[] masterKey) {
        byte[] salt = "SecureEnv.salt.v1".getBytes(StandardCharsets.UTF_8);
        byte[] prk  = hkdfExtract(salt, masterKey);
        byte[] kKey = hkdfExpand(prk, "key-hmac".getBytes(StandardCharsets.UTF_8), 32);
        byte[] kVal = hkdfExpand(prk, "val-aesgcm".getBytes(StandardCharsets.UTF_8), 32);
        return new DerivedKeys(kKey, kVal);
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        byte[] out = hmac(key, data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String encryptAesGcmToB64Url(byte[] key, String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance(AES_TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String decryptAesGcmFromB64Url(byte[] key, String b64) {
        try {
            byte[] blob = Base64.getUrlDecoder().decode(b64);
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ct = new byte[blob.length - GCM_IV_LEN];
            System.arraycopy(blob, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(blob, GCM_IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(AES_TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static final class DerivedKeys {
        public final byte[] kKeyHmac;  // for HMAC tokens of key names
        public final byte[] kValAes;   // for AES-GCM of values
        public DerivedKeys(byte[] kKeyHmac, byte[] kValAes) { this.kKeyHmac = kKeyHmac; this.kValAes = kValAes; }
    }
}