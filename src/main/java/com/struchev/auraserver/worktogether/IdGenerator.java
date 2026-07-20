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
        return next(prefix, 15);
    }

    /**
     * @param randomBytes how much entropy to spend - 15 bytes (120 bits) for ids that
     *                    are never brute-forceable over the network (session/connection
     *                    ids); shorter is reasonable for ids meant to be short, human
     *                    shareable links, as long as the endpoint that accepts them is
     *                    rate-limited (see linkId in SessionService#mintLink).
     */
    public static String next(String prefix, int randomBytes) {
        byte[] bytes = new byte[randomBytes];
        RANDOM.nextBytes(bytes);
        return prefix + ENCODER.encodeToString(bytes);
    }
}
