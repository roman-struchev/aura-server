package com.struchev.auraserver.worktogether.model;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One shared tab: a live document (held as real Yjs state inside the Host's
 * and guests' own browser/Electron runtimes, not here — see specification.md's
 * note that the backend is a relay, not a durable store) plus its participants.
 */
public class WtSession {

    private final String sessionId;
    private final String filePath;
    private final String language;
    private final String content;
    private final Instant createdAt;
    private final long maxTtlSeconds;
    private final Instant expiresAt;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicInteger guestCounter = new AtomicInteger(0);

    private final ConcurrentHashMap<String, WtLink> links = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WtConnection> connections = new ConcurrentHashMap<>();

    public WtSession(String sessionId, String filePath, String language, String content,
                      Instant createdAt, long maxTtlSeconds, Instant expiresAt) {
        this.sessionId = sessionId;
        this.filePath = filePath;
        this.language = language;
        this.content = content;
        this.createdAt = createdAt;
        this.maxTtlSeconds = maxTtlSeconds;
        this.expiresAt = expiresAt;
    }

    public String sessionId() {
        return sessionId;
    }

    public String filePath() {
        return filePath;
    }

    public String language() {
        return language;
    }

    public String content() {
        return content;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long maxTtlSeconds() {
        return maxTtlSeconds;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean ended() {
        return ended.get();
    }

    /** @return true if this call actually transitioned the session to ended. */
    public boolean end() {
        return ended.compareAndSet(false, true);
    }

    public boolean isLive(Instant now) {
        return !ended.get() && now.isBefore(expiresAt);
    }

    /** Next sequential guest number for this session, used for default display names. */
    public int nextGuestNumber() {
        return guestCounter.incrementAndGet();
    }

    public void addLink(WtLink link) {
        links.put(link.linkId(), link);
    }

    public WtLink link(String linkId) {
        return links.get(linkId);
    }

    public Collection<WtLink> links() {
        return links.values();
    }

    public void addConnection(WtConnection connection) {
        connections.put(connection.connectionId(), connection);
    }

    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
    }

    public Collection<WtConnection> connections() {
        return connections.values();
    }
}
