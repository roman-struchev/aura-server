package com.struchev.auraserver.worktogether.guest;

import tools.jackson.databind.ObjectMapper;
import com.struchev.auraserver.worktogether.Role;
import com.struchev.auraserver.worktogether.SessionService;
import com.struchev.auraserver.worktogether.TokenClaims;
import com.struchev.auraserver.worktogether.TokenService;
import com.struchev.auraserver.worktogether.exception.InvalidTokenException;
import com.struchev.auraserver.worktogether.model.WtLink;
import com.struchev.auraserver.worktogether.model.WtSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Serves the guest-facing collaborative editor page a share link resolves to
 * (specification.md §6). The page itself never talks to the REST API — it
 * embeds enough server-verified state to open the WebSocket directly.
 */
@Controller
public class GuestPageController {

    private final TokenService tokenService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final String guestTemplate;
    private final String errorTemplate;

    public GuestPageController(TokenService tokenService, SessionService sessionService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.guestTemplate = readClasspathResource("worktogether/guest-template.html");
        this.errorTemplate = readClasspathResource("worktogether/error-template.html");
    }

    @GetMapping("/join/{token}")
    public ResponseEntity<String> joinSession(@PathVariable String token) {
        TokenClaims claims;
        try {
            claims = tokenService.verify(token);
        } catch (InvalidTokenException e) {
            return errorPage("This link is invalid.");
        }
        if (claims.role() == Role.HOST) {
            // Host tokens authenticate the AuraPad app's own connection, not a shareable join link.
            return errorPage("This link is invalid.");
        }

        WtSession session = sessionService.findSessionForGuestPage(claims.sessionId());
        if (session == null || session.ended()) {
            return errorPage("This session has ended.");
        }
        WtLink link = session.link(claims.linkId());
        if (link == null) {
            return errorPage("This link is invalid.");
        }
        if (link.revoked()) {
            return errorPage("This link was revoked by the host.");
        }
        if (Instant.now().isAfter(link.expiresAt())) {
            return errorPage("This link has expired.");
        }

        String html = guestTemplate
                .replace("__FILE_PATH_HTML__", htmlEscape(session.filePath()))
                .replace("__SESSION_ID_JSON__", jsLiteral(session.sessionId()))
                .replace("__TOKEN_JSON__", jsLiteral(token))
                .replace("__ROLE_JSON__", jsLiteral(claims.role().value()))
                .replace("__LANGUAGE_JSON__", jsLiteral(session.language()))
                .replace("__FILE_PATH_JSON__", jsLiteral(session.filePath()))
                .replace("__INITIAL_CONTENT_JSON__", jsLiteral(session.content()));

        return htmlResponse(HttpStatus.OK, html);
    }

    private ResponseEntity<String> errorPage(String message) {
        String html = errorTemplate.replace("__MESSAGE_HTML__", htmlEscape(message));
        return htmlResponse(HttpStatus.OK, html);
    }

    private ResponseEntity<String> htmlResponse(HttpStatus status, String html) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, status);
    }

    /** Encodes {@code value} as a self-quoting JSON string literal, hardened against breaking out of the enclosing {@code <script>} tag. */
    private String jsLiteral(String value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.replace("</", "<\\/");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode value for guest page", e);
        }
    }

    private static String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String readClasspathResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + path, e);
        }
    }
}
