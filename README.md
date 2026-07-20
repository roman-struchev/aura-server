# aura-server

Spring Boot 4.1 / Java 25 backend behind `aura.struchev.site`.

## Work Together backend

Implements the **Work Together** collaborative-editing backend contract specified in
[`docs/edit-together/specification.md`](https://github.com/roman-struchev/aura-pad/blob/main/docs/edit-together/specification.md)
of the [aura-pad](https://github.com/roman-struchev/aura-pad) editor
(see also [`feature-desc.md`](https://github.com/roman-struchev/aura-pad/blob/main/docs/edit-together/feature-desc.md)
for the product framing). In short: AuraPad shares an open file by creating a
*session* here and minting a time-limited *link*; whoever opens that link — no
AuraPad install needed — gets a live Monaco editor synced over Yjs, relayed
through this server.

What's implemented, under `src/main/java/com/struchev/auraserver/worktogether/`:

- **REST API** (`/v1/sessions/...`) — create session, mint/revoke share links,
  end session, get status (spec §3).
- **WebSocket relay** (`/v1/sessions/{sessionId}/connect`) — relays Yjs sync +
  awareness frames between the Host and guests, enforces read-only links
  server-side, and closes sockets with the spec's close codes on
  expiry/revocation/session end (spec §4, §5).
- **Guest-facing editor page** (`/join/{token}`) — a self-hosted Monaco +
  `y-websocket` + `y-monaco` page (source in `frontend/work-together-guest/`,
  built assets in `src/main/resources/static/work-together/`); see that
  directory's own notes for the rebuild command.
- Signed, opaque tokens (HMAC-SHA256) carry role/session/link claims — no
  accounts, matching the spec's "possession of the link" trust model.
- In-memory only: sessions/links/connections live in process memory and are
  swept on TTL expiry; nothing is durably persisted (spec §7/§8 — this is by
  design, AuraPad's local file remains the source of truth).

**Deliberate deviation from the spec as written:** §3.1 suggests the backend
"constructs the initial Yjs update" from the `content` sent at session
creation. This backend does **not** do that — it stores `content`/`language`
only for the guest page's pre-sync placeholder paint and syntax highlighting.
The real Yjs CRDT state lives entirely in the Host's and guests' own Yjs
runtimes; this backend is a byte-level relay (it only peeks at the outer
sync/awareness/control tag, and the sync sub-type, to enforce read-only) and
never decodes Yjs' binary format. This requires no client-side change — the
Host already seeds its own `Y.Doc` from local content — but if you're
re-implementing this spec elsewhere, don't expect an initial Yjs update from
this backend.

**REST auth:** intentionally open (no API key) per spec §3.6's "self-hosted
... may accept these calls unauthenticated" — this is a single-tenant,
personal deployment. Session creation is per-IP rate-limited instead
(`worktogether.rate-limit.max-per-minute`, default 30/min).

Session creation today only comes from AuraPad's REST calls; the service
layer (`SessionService`) has no AuraPad-specific coupling, so a future web
form for creating a session directly from `aura.struchev.site` (without
AuraPad) can reuse it as-is.

## Landing page

`/` serves a short static page (`src/main/resources/static/index.html`)
describing the AuraPad project with a link to its GitHub repo — not a
duplicate of the repo's own README.

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `worktogether.token-secret` (env `WORKTOGETHER_TOKEN_SECRET`) | random at startup | HMAC key signing tokens; set this for tokens to survive a restart |
| `worktogether.public-base-url` (env `WORKTOGETHER_PUBLIC_BASE_URL`) | `https://aura.struchev.site` | Fixed host used to build share/join URLs — deliberately **not** derived from the request's `Host` header, so a Host app calling this server through an internal address (e.g. `host.docker.internal:10005`) never leaks that into a guest-facing link |
| `worktogether.max-session-ttl-seconds` | `604800` (7 days) | Hard ceiling on `maxTtlSeconds` |
| `worktogether.cleanup-interval-seconds` | `15` | How often expired sessions/links are swept |
| `worktogether.rate-limit.max-per-minute` | `30` | Per-IP cap on `POST /v1/sessions` |

## Running

```
./gradlew bootRun
```

Deployment is via the existing GitHub Actions workflow
(`.github/workflows/gradlew-publish-and-deploy.yml`) → Docker Hub → the
`docker-compose.yml` on the target host.
