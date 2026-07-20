package com.struchev.auraserver.worktogether;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates opaque, URL-safe ids for sessions/links/connections, e.g. {@code sess_9fK3Q...}.
 */
public final class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private IdGenerator() {
    }

    public static String next(String prefix) {
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        return prefix + ENCODER.encodeToString(bytes);
    }
}
