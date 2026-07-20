package com.struchev.auraserver.worktogether.guest;

import tools.jackson.databind.ObjectMapper;
import com.struchev.auraserver.worktogether.GuestLinkContext;
import com.struchev.auraserver.worktogether.SessionService;
import com.struchev.auraserver.worktogether.TokenService;
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
 * (specification.md §6). The URL only carries the link's short id, not the
 * full signed token - much shorter to share, and just as unguessable since
 * the id itself is high-entropy random (see SessionService#mintLink). This
 * controller resolves that id back to its session/link and mints an
 * equivalent WS token on the fly (deterministic: same claims + secret always
 * produce the same signature, so there's nothing to persist).
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

    @GetMapping("/join/{linkId}")
    public ResponseEntity<String> joinSession(@PathVariable String linkId) {
        GuestLinkContext ctx = sessionService.resolveGuestLink(linkId);
        if (ctx == null) {
            return errorPage("This link is invalid.");
        }
        WtSession session = ctx.session();
        WtLink link = ctx.link();
        if (session.ended()) {
            return errorPage("This session has ended.");
        }
        if (link.revoked()) {
            return errorPage("This link was revoked by the host.");
        }
        if (Instant.now().isAfter(link.expiresAt())) {
            return errorPage("This link has expired.");
        }

        String wsToken = tokenService.mint(session.sessionId(), link.role(), link.linkId(), link.expiresAt());

        String html = guestTemplate
                .replace("__FILE_PATH_HTML__", htmlEscape(session.filePath()))
                .replace("__SESSION_ID_JSON__", jsLiteral(session.sessionId()))
                .replace("__TOKEN_JSON__", jsLiteral(wsToken))
                .replace("__ROLE_JSON__", jsLiteral(link.role().value()))
                .replace("__LANGUAGE_JSON__", jsLiteral(session.language()))
                .replace("__FILE_PATH_JSON__", jsLiteral(session.filePath()))
                .replace("__INITIAL_CONTENT_JSON__", jsLiteral(session.content()));

        return htmlResponse(html);
    }

    private ResponseEntity<String> errorPage(String message) {
        String html = errorTemplate.replace("__MESSAGE_HTML__", htmlEscape(message));
        return htmlResponse(html);
    }

    private ResponseEntity<String> htmlResponse(String html) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
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
