package com.wangfugui.notebookagent.core;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Password hashing compatible with the Python `scrypt$<b64salt>$<b64digest>` format. */
public final class PasswordHasher {

    private static final int N = 1 << 14;
    private static final int R = 8;
    private static final int P = 1;
    private static final int KEY_LEN = 64;
    private static final int SALT_LEN = 16;

    public static String hash(String password) {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] digest = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, N, R, P, KEY_LEN);
        return "scrypt$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(digest);
    }

    public static boolean verify(String password, String encoded) {
        if (encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$", 3);
        if (parts.length != 3 || !"scrypt".equals(parts[0])) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, N, R, P, KEY_LEN);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private PasswordHasher() {
    }
}
