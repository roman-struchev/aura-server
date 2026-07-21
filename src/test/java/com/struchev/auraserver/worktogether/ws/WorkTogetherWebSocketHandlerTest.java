package com.struchev.auraserver.worktogether.ws;

import com.struchev.auraserver.worktogether.ConnectAuth;
import com.struchev.auraserver.worktogether.Role;
import com.struchev.auraserver.worktogether.SessionService;
import com.struchev.auraserver.worktogether.model.WtConnection;
import com.struchev.auraserver.worktogether.model.WtSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the snapshot resync path (specification.md §4.4) that fixes a reconnect
 * into an empty room landing on a blank document: a write-capable client's
 * snapshot frame (tag 4) is stored framing-stripped and replayed verbatim to
 * every newcomer on connect, read-only snapshots are dropped, and snapshot
 * frames are never relayed.
 */
class WorkTogetherWebSocketHandlerTest {

    private static final int TAG_SNAPSHOT = 4;
    // The inner sync message a snapshot wraps: [tag=sync=0][subtype=update=2][...]
    private static final byte[] INNER_SYNC = { 0, 2, 42, 7 };

    private SessionService sessionService;
    private WorkTogetherWebSocketHandler handler;
    private WtSession wtSession;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        handler = new WorkTogetherWebSocketHandler(sessionService);
        wtSession = new WtSession("sess_1", "src/App.tsx", "typescript", "const x = 1;",
                Instant.now(), 3600L, Instant.now().plusSeconds(3600));
        when(sessionService.findLiveSession("sess_1")).thenReturn(wtSession);
    }

    private WebSocketSession newClientSession(String connectionId, Role role) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_SESSION_ID, "sess_1");
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_CONNECT_AUTH, new ConnectAuth(role, null, "Test"));
        attributes.put("wtConnectionId", connectionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        wtSession.addConnection(new WtConnection(connectionId, "sess_1", role, null, Instant.now(), "Test", session));
        return session;
    }

    private static byte[] snapshotFrame(byte[] innerSyncMessage) {
        byte[] frame = new byte[innerSyncMessage.length + 1];
        frame[0] = TAG_SNAPSHOT;
        System.arraycopy(innerSyncMessage, 0, frame, 1, innerSyncMessage.length);
        return frame;
    }

    private static byte[] payloadOf(BinaryMessage message) {
        ByteBuffer buffer = message.getPayload();
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    @Test
    void snapshotFromWriteConnectionIsStoredWithoutItsTag() {
        WebSocketSession writer = newClientSession("conn_a", Role.WRITE);

        handler.handleBinaryMessage(writer, new BinaryMessage(snapshotFrame(INNER_SYNC)));

        // Stored verbatim minus the outer snapshot tag - i.e. the plain sync message.
        assertThat(wtSession.latestSnapshot()).isEqualTo(INNER_SYNC);
    }

    @Test
    void snapshotFromReadOnlyConnectionIsDropped() {
        WebSocketSession reader = newClientSession("conn_a", Role.READ);

        handler.handleBinaryMessage(reader, new BinaryMessage(snapshotFrame(INNER_SYNC)));

        assertThat(wtSession.latestSnapshot()).isNull();
    }

    @Test
    void snapshotFrameIsNotRelayedToOtherParticipants() throws Exception {
        WebSocketSession writer = newClientSession("conn_a", Role.WRITE);
        WebSocketSession other = newClientSession("conn_b", Role.WRITE);

        handler.handleBinaryMessage(writer, new BinaryMessage(snapshotFrame(INNER_SYNC)));

        verify(other, never()).sendMessage(any());
    }

    @Test
    void newConnectionWithNoStoredSnapshotReceivesNothing() throws Exception {
        WebSocketSession joiner = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_SESSION_ID, "sess_1");
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_CONNECT_AUTH, new ConnectAuth(Role.HOST, null, "Host"));
        when(joiner.getAttributes()).thenReturn(attributes);
        when(joiner.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(joiner);

        verify(joiner, never()).sendMessage(any());
    }

    @Test
    void newConnectionIsReplayedTheStoredSnapshotVerbatim() throws Exception {
        // A write participant populates the cache first.
        WebSocketSession writer = newClientSession("conn_a", Role.WRITE);
        handler.handleBinaryMessage(writer, new BinaryMessage(snapshotFrame(INNER_SYNC)));

        // A newcomer connects with nobody else around to answer its sync.
        WebSocketSession joiner = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_SESSION_ID, "sess_1");
        attributes.put(WorkTogetherHandshakeInterceptor.ATTR_CONNECT_AUTH, new ConnectAuth(Role.HOST, null, "Host"));
        when(joiner.getAttributes()).thenReturn(attributes);
        when(joiner.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(joiner);

        ArgumentCaptor<BinaryMessage> sent = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(joiner).sendMessage(sent.capture());
        // Replayed as the plain sync message, so a stock y-websocket client
        // applies it through ordinary sync handling.
        assertThat(payloadOf(sent.getValue())).isEqualTo(INNER_SYNC);
    }

    @Test
    void aBareSnapshotTagWithNoBodyIsIgnored() {
        WebSocketSession writer = newClientSession("conn_a", Role.WRITE);

        handler.handleBinaryMessage(writer, new BinaryMessage(new byte[] { TAG_SNAPSHOT }));

        assertThat(wtSession.latestSnapshot()).isNull();
    }
}
