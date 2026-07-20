package com.struchev.auraserver.worktogether.web;

import com.struchev.auraserver.worktogether.RateLimiter;
import com.struchev.auraserver.worktogether.SessionService;
import com.struchev.auraserver.worktogether.dto.CreateSessionRequest;
import com.struchev.auraserver.worktogether.dto.CreateSessionResponse;
import com.struchev.auraserver.worktogether.dto.MintLinkRequest;
import com.struchev.auraserver.worktogether.dto.MintLinkResponse;
import com.struchev.auraserver.worktogether.dto.SessionStatusResponse;
import com.struchev.auraserver.worktogether.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Host-facing REST API, specification.md §3. Guests never call this — they
 * only ever use the link URL and the WebSocket (see {@code GuestPageController}
 * and the {@code ws} package).
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final RateLimiter rateLimiter;
    private final String publicBaseUrl;

    public SessionController(SessionService sessionService, RateLimiter rateLimiter,
                              @Value("${worktogether.public-base-url}") String publicBaseUrl) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
        // Strip any trailing slash so "url + /join/" + token never double-slashes.
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(@RequestBody CreateSessionRequest request,
                                                                 HttpServletRequest servletRequest) {
        if (!rateLimiter.tryAcquire(clientIp(servletRequest))) {
            throw new RateLimitExceededException("Rate limit exceeded, try again shortly");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.createSession(request));
    }

    @PostMapping("/{sessionId}/links")
    public ResponseEntity<MintLinkResponse> mintLink(@PathVariable String sessionId,
                                                       @RequestBody MintLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.mintLink(sessionId, request, publicBaseUrl));
    }

    @DeleteMapping("/{sessionId}/links/{linkId}")
    public ResponseEntity<Void> revokeLink(@PathVariable String sessionId, @PathVariable String linkId) {
        sessionService.revokeLink(sessionId, linkId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> endSession(@PathVariable String sessionId) {
        sessionService.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionStatusResponse> getStatus(@PathVariable String sessionId) {
        return ResponseEntity.ok(sessionService.getStatus(sessionId));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
