package com.qa.utils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SecureEnv {
    private static final String DEFAULT_RESOURCE = "/.env.sec"; // put this file in src/test/resources or src/main/resources
    private final Map<String, String> encMap = new HashMap<>();
    private final byte[] kVal; // AES key (values)
    private final byte[] kKey; // HMAC key (key-name tokens)

    private SecureEnv(Map<String, String> encMap, byte[] kKey, byte[] kVal) {
        this.encMap.putAll(encMap);
        this.kKey = kKey; this.kVal = kVal;
    }

    public static SecureEnv load() {
        String b64 = System.getenv("ENCRYPTION_KEY");
        if (b64 == null || b64.isBlank()) throw new IllegalStateException("ENCRYPTION_KEY missing.");
        byte[] master = java.util.Base64.getDecoder().decode(b64);
        var keys = CryptoUtils.deriveKeys(master);

        Map<String,String> data = new HashMap<>();
        try (InputStream is = SecureEnv.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is == null) throw new IllegalStateException(".env.sec not found on classpath: " + DEFAULT_RESOURCE);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int idx = line.indexOf('=');
                    if (idx <= 0) continue;
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + 1).trim();
                    data.put(k, v);
                }
            }
        } catch (IOException e) { throw new RuntimeException(e); }

        return new SecureEnv(data, keys.kKeyHmac, keys.kValAes);
    }

    /** Use the token (K|<64-hex>) to fetch the decrypted value. */
    public String getByToken(String token) {
        String enc = encMap.get(token);
        if (enc == null) return null;
        if (!enc.startsWith("V|")) throw new IllegalStateException("Bad value for token: " + token);
        String b64 = enc.substring(2);
        String plain = CryptoUtils.decryptAesGcmFromB64Url(kVal, b64);
        SecretMasker.registerSecret(plain);
        return plain;
    }

    /** Helper (offline/dev tooling): generate token for a plaintext key. */
    public String tokenForPlainKey(String plainKey) {
        return "K|" + CryptoUtils.hmacSha256Hex(kKey, plainKey);
    }
}

