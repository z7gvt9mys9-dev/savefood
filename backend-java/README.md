# SaveFood Backend — Java/Spring Boot

Java/Spring Boot port of the SaveFood backend, migrated module by module:

- **Step 1 — admin/moderation** (`backend/admin/routes.py`)
- **Step 2 — shop/donor** (`backend/shop/routes.py`)
- **Step 3 — needy/recipient** (`backend/needy/routes.py`)
- **Step 4 — auth: token issuance + social login** (`backend/auth_routes.py`,
  `backend/oauth_routes.py`, `/auth/*` of `backend/telegram_routes.py`)
- **Step 5 — volunteer/courier** (`backend/volunteer/routes.py`)
- **Step 6 — partner API + public impact** (`backend/partner_api.py`,
  `backend/impact.py`)
- **Step 7 — push subscriptions + in-app chat** (`backend/push_routes.py`,
  `backend/chat_routes.py`)

The service is wire-compatible with the existing Python stack — same Postgres
schema, same HS256 JWTs (`SECRET_KEY`) — so it can run side by side with the
FastAPI backend while the rest of the API is migrated module by module.

## Stack

| Concern | Choice |
|---|---|
| Framework | Spring Boot 3.3 (Web + JDBC) |
| DB access | `JdbcTemplate` (raw SQL, mirroring the psycopg2 style) |
| JWT | jjwt 0.12 (HS256) — validates tokens issued by `backend/auth.py` |
| Build | Maven, Java 25 |

## What was ported

All 16 endpoints of `/admin`, 1:1 with `backend/admin/routes.py`:

- `GET /admin/needy`, `GET /admin/delivery_photos`,
  `POST /admin/delivery_photos/{id}/approve|reject`
- `GET /admin/stats`, `GET /admin/heatmap`
- `GET /admin/routes`, `POST /admin/routes/{id}/reset`, `POST /admin/lots/{id}/reset`
- `GET /admin/users`, `POST /admin/users/{id}/block|unblock`
- `GET /admin/esg`, `GET /admin/shops`, `PATCH /admin/shops/{id}/plan`, `GET /admin/audit`

All 21 endpoints of the shop surface, 1:1 with `backend/shop/routes.py`:

- `POST /shops/register` (public, rate-limited)
- Lots: `POST /shops/{id}/lots`, `POST /shops/{id}/lots/upload` (multipart),
  `GET /shops/{id}/lots`, `DELETE /lots/{id}`, `PATCH /lots/{id}`,
  `POST /lots/{id}/confirm_transfer`, `GET /shops/{id}/history`
- Shop profile: `GET /shops/{id}`, `PATCH /shops/{id}`
- Notifications: `GET /shops/{id}/notifications`, `PATCH /shops/notifications/{id}/read`
- Receipts (OCR): `POST /shops/{id}/receipts` (rate-limited), `…/{rid}/confirm`,
  `GET /shops/{id}/receipts`, `GET /shops/{id}/receipts/{rid}/image`
- `GET /shops/{id}/forecast`, `GET /shops/{id}/plan`,
  `GET /shops/{id}/esg`, `GET /shops/{id}/esg/report.csv`
- `POST /shops/{id}/self_pickup/confirm` (rate-limited)

All 19 endpoints of the needy surface, 1:1 with `backend/needy/routes.py`:

- `POST /needy/register` (public, rate-limited)
- Tickets: `POST /needy/{id}/ticket`, `GET /needy/{id}/tickets`,
  `GET /needy/{id}/history`, `DELETE /needy/{id}/ticket/{tid}`,
  `POST /needy/{id}/ticket/{tid}/rate`,
  `POST /needy/{id}/ticket/{tid}/photo` (multipart, rate-limited 3/hour)
- Account/profile: `GET /needy/{id}`, `PATCH /needy/{id}`,
  `POST|PATCH /needy/{id}/profile`, `GET /needy/{id}/profile`,
  `POST /needy/{id}/profile/upload` (multipart),
  `PATCH /needy/{id}/geo_push`, `GET /needy/{id}/export`,
  `DELETE /needy/{id}/account`
- Notifications: `GET /needy/{id}/notifications`,
  `PATCH /needy/notifications/{id}/read`
- `GET /lots` (public — the recipient map, read-through cached)
- `GET /ws/needy/{id}` (WebSocket notification stream)

All 3 endpoints of the auth surface, 1:1 with `backend/auth_routes.py`:

- `POST /auth/login` (public, OAuth2 password flow, form-encoded, rate-limited
  5/min/IP) — verifies the bcrypt hash, rejects blocked users (403), mints the
  access token
- `POST /auth/refresh` (re-issues a 24h token for the current principal)
- `GET /auth/me` (returns the decoded payload: `sub`, `role`, `related_id`, `exp`)

`JwtService` now both **issues** and validates tokens; the minted token is
byte-for-byte interchangeable with the Python backend's (same HS256 `SECRET_KEY`,
same claims, 24h `exp`, no `iat`), so the two services share sessions during the
migration.

All 8 social-auth endpoints, 1:1 with `backend/oauth_routes.py` (+ the one
`/auth/*` route from `backend/telegram_routes.py`):

- `GET /auth/oauth/providers` (which providers are configured)
- `GET /auth/oauth/{provider}/start` (Google/Yandex auth-code flow,
  `mode=login|link`, rate-limited 10/min) — `link` pins the user id in a signed
  `state` JWT
- `GET /auth/oauth/{provider}/callback` (307 redirect back to the SPA; the access
  token rides the URL fragment on login, never the query string)
- `GET /auth/links`, `POST /auth/links/{provider}/unlink` (profile link status)
- `POST /auth/telegram/login/start` (10/min) + `POST /auth/telegram/login/poll`
  (60/min) — deep-link + polling login (no Login Widget)
- `GET /auth/telegram/init-link` (10/min) — link Telegram to the current account

The OAuth `state` is a short-lived signed JWT (`JwtService.signClaims`), so the
callback is stateless. The Telegram bot **webhook** (`/telegram/webhook`) that
fills a login/link token's `user_id` stays on the Python service during the
migration — both services share the `telegram_login_tokens` /
`telegram_link_tokens` tables, so the flow works across the split.

All 22 endpoints of the volunteer/courier surface, 1:1 with
`backend/volunteer/routes.py`:

- `POST /volunteers/register` (public, rate-limited 5/min) +
  `POST /volunteers/{id}/document/upload` (identity KYC, multipart, 10/min)
- `GET /volunteers/map` (shops with active lots + open requests; recipient
  coordinates coarsened to a ~500 m grid, anti-doxxing)
- `GET /volunteers/{id}`, `PATCH /volunteers/{id}` (profile + weekly availability)
- Route lifecycle: `POST /volunteers/{id}/start_route`,
  `POST /volunteers/route/{rid}/complete_point`, `.../finish`,
  `.../attempt_delivery`; `GET /volunteers/{id}/history`, `.../active_route`
- `GET /volunteers/{id}/notifications`, `PATCH /volunteers/notifications/{id}/read`
- `GET /volunteers/{id}/rating`, `.../stats` (gamified — achievements + level),
  `.../thanks`
- Teams: `GET /volunteers/{id}/team`, `.../team/create|join|leave`
- `PATCH /volunteers/{id}/location`, `GET /volunteers/{id}/location`

Notes specific to the volunteer module:

- **start_route** is the heaviest flow: priority selection (objective-need
  scoring, §59/Q3) + nearest-neighbour/2-opt visiting order (§14), the cold-chain
  gate (§47), a soft delivery-window horizon with a displacement counter
  (§59/Q1), and the whole lot claim + ticket assignment + cancellations as one
  `@Transactional` unit. The one-active-route invariant is the DB unique index
  `uq_routes_one_active_per_volunteer` (a parallel claim → 400, the row rolls back).
- **complete_point** keeps the server-side delivery verification (§13): the
  per-ticket QR secret (`Qr.buildCode`), the 100 m delivery / 150 m shop GPS
  radius, and the cross-check of payload coordinates against the volunteer's last
  fresh geolocation ping (§27). Like the per-block Python cursors, its writes are
  discrete (the fulfilment UPDATE is status+owner guarded, 409 on a stale route).
- **Volunteer KYC** (§58) extends `KycService` with the identity-document path
  (its own system prompt + scorer); confident verdicts auto-approve/reject, the
  rest stay pending for the Python `kyc_retry_tick`. Documents are encrypted at
  rest (`KycCrypto`) under `VOLUNTEER_KYC_UPLOAD_DIR`, never served publicly.
  `VOLUNTEER_KYC_REQUIRED` gates `start_route` (admins bypass).
- **Location.** Postgres is the source of truth; cache.py's Redis write-through
  for `vol:loc:{id}` stays on the Python layer (CacheService is a no-op here), so
  `GET /location` reads straight from the DB — always correct, never stale. The
  Go `geows` service can still serve these endpoints (it writes Postgres only).
- As elsewhere, in-app notification rows are written in full; the best-effort
  Telegram fan-out stays on the Python notifier. The `lot.taken` enterprise
  webhook is fired after commit (like the shop port's `lot.confirmed`).

All 11 partner-API endpoints, 1:1 with `backend/partner_api.py`:

- `/api/v1/*` (authenticated by **`X-API-Key`**, which maps to a shop): `ping`,
  `GET/POST /lots`, `DELETE /lots/{id}`, `GET /esg`. The key is `sf_live_<48 hex>`;
  only its sha256 hash is stored, the secret is shown once at creation.
- Dashboard management (JWT): `POST/GET /shops/{id}/api_keys`,
  `.../api_keys/{kid}/revoke`, `POST/GET /shops/{id}/webhooks`,
  `DELETE /shops/{id}/webhooks/{hid}`. These paths are distinct from
  `ShopController`'s, so the two controllers coexist under `/shops`.

Both surfaces gate on the billing `"api"` feature, so a plan downgrade revokes
access without deleting keys. Webhook events are validated against
`WebhookService.EVENTS` (`lot.taken`, `lot.confirmed`, `receipt.parsed`, or `*`).

All 7 public impact endpoints, 1:1 with `backend/impact.py` (unauthenticated —
the PR/transparency surface; no personal data, only aggregates / first names /
moderation-approved photos):

- `GET /impact/summary` (city dashboard: ESG totals + counters, short-TTL cached),
  `GET /impact/cities` / `/volunteers` / `/teams` (leaderboards),
  `GET /impact/feed` (anonymous approved-photo feed)
- `GET /impact/widget.svg` and `GET /impact/widget/{shop_id}.svg` — self-contained
  embeddable impact badges (§52), rendered server-side, `Cache-Control: 1h`

"Rescued" is the single ESG definition (`EsgService.RESCUED_*`, §56). The
short-TTL caching uses `CacheService` — a correct cache miss until Redis is
wired (as in the other ports).

All 5 push endpoints, 1:1 with `backend/push_routes.py`, and both chat endpoints,
1:1 with `backend/chat_routes.py`:

- `GET /push/public_key` (VAPID probe; 503 if unconfigured), `POST /push/subscribe`
  / `unsubscribe` (Web Push), `POST /push/fcm/register` / `unregister` (native
  Android). `fcm/register` enforces that the client-sent `(role, related_id)`
  matches the authenticated account.
- `GET /tickets/{id}/messages`, `POST /tickets/{id}/messages` (rate-limited 30/min)
  — the chat thread is visible to the recipient, the assigned volunteer and
  admins; posting is allowed only while the ticket is `assigned`, and admins
  observe but cannot post.

For both modules the **fan-out stays on the Python notifier** during the
migration (the README's standing rule): this port owns the storage and the
authoritative in-app rows — `push_subscriptions` / `fcm_tokens` writes and the
`ticket_messages` insert — while the actual Web-Push/FCM dispatch and the
Telegram/Web-Push chat mirrors are still sent by Python (which reads the same
tables). `PushService.isConfigured()` mirrors the VAPID-keys gate.

Supporting logic ported as reusable services (the foundation the rest of the
backend plugs into):

| Java | Python source |
|---|---|
| `auth/AuthController` | `auth_routes.py` (login / refresh / me) |
| `auth/OAuthController` | `oauth_routes.py` + `telegram_routes.py` init-link (social login/linking) |
| `security/JwtService` | `auth.py` `create_access_token` / `decode_access_token`; `oauth_routes.py` `_make_state` / `_read_state` |
| `security/JwtService`, `AdminArgumentResolver` | `auth.py` `get_current_user` / `require_admin` |
| `security/AuthArgumentResolver` (`@Auth`), `Authz` | `auth.py` `get_current_user` / `ensure_owner_or_admin` |
| `security/PasswordService` | `auth.py` `get_password_hash` (bcrypt) |
| `esg/EsgService` | `esg.py` (`RESCUED_SQL`, `RESCUED_KG_SQL`, `global_report`, `shop_report`, `report_to_csv`) |
| `audit/AuditService` | `database.py` `log_action` |
| `volunteer/VolunteerController` | `volunteer/routes.py` (courier surface) |
| `volunteer/VolunteerService`, `VolunteerRepository` | `volunteer/{routes,db}.py` (route lifecycle, scoring, teams) |
| `volunteer/RouteRevertService` | `background.py` `revert_route_lot` |
| `volunteer/AvailabilityService` | `volunteer/db.py` `is_available_now` |
| `gamification/Gamification` | `gamification.py` (`compute_level`) |
| `util/JoinCode` | `utils.py` `generate_join_code` |
| `partner/PartnerApiController` | `partner_api.py` (X-API-Key API + key/webhook management) |
| `impact/ImpactController` | `impact.py` (public dashboard, leaderboards, feed, SVG badges) |
| `push/PushController`, `PushService` | `push_routes.py` + storage half of `push_service.py` |
| `chat/ChatController`, `ChatService` | `chat_routes.py` + `chat.py` (in-app ticket chat) |
| `needy/NeedyController` | `needy/routes.py` (recipient surface) |
| `needy/NeedyRepository`, `NeedyService` | `needy/db.py` (reads + transactional flows) |
| `kyc/KycCrypto` | `kyc_crypto.py` (Fernet encryption-at-rest, wire-compatible) |
| `kyc/KycService` | `kyc_service.py` (needy + volunteer Auto-KYC: Gemini + auto-decide) |
| `photo/PhotoModerationService` | `photo_moderation.py` (Gemini delivery-photo gate) |
| `cache/CacheService` | `cache.py` (read-through facade; Redis stays on Python) |
| `util/Qr` | `utils.py` `generate_qr_secret` / `build_qr_code` |
| `billing/Plans`, `billing/BillingService` | `billing.py` (`PLANS`, gating, `lot_quota_guard`, `plan_summary`) |
| `shop/ShopRepository`, `ShopService` | `shop/db.py` + transactional route logic |
| `receipt/ReceiptService` | `receipt_service.py` (Gemini Vision OCR + anti-fraud) |
| `forecast/ForecastService` | `forecast.py` (write-off forecast) |
| `webhook/WebhookService` | `webhook_service.py` (HMAC-signed delivery + SSRF guard) |
| `match/NeedsMatchService` | `needs_match.py` (in-app feed match; Telegram/Web-Push stay on Python) |
| `upload/UploadService` | `utils.py` `validate_and_save_upload` (EXIF strip, +PDF for KYC docs) |
| `web/RateLimiter` | slowapi `@limiter.limit` (minute + hour windows) |
| `web/GlobalExceptionHandler` | FastAPI `HTTPException` → `{"detail": ...}` |

Error bodies match FastAPI's `{"detail": ...}` (see `web/`).

### Needy-module notes

- **Auth model.** Like the shop surface, authenticated needy routes take an
  `@Auth CurrentUser` and call `Authz.ensureOwnerOrAdmin(user, "needy", id)`;
  `POST /needy/register` and `GET /lots` are public.
- **WebSocket.** `/ws/needy/{id}` keeps the handshake-auth protocol (the JWT
  arrives in the first `{"type":"auth",…}` frame, never the query string),
  the `MAX_WS_PER_USER` cap, the resume cursor (`since_id`) and the ~3 s poll.
  Python multiplexes one coroutine over receive/poll; here it is a scheduled
  poll per session plus the container's disconnect callback — the wire protocol
  (ready frame, notification frames, `1008` close codes) is identical.
- **Auto-KYC.** Uploading an eligibility document encrypts it at rest
  (`KycCrypto`, Fernet — same `KYC_ENCRYPTION_KEY` as Python) and fires the
  fully-automated AI check (`KycService`): confident verdicts auto-approve /
  auto-reject, anything inconclusive stays `pending`. No human in the loop
  (§58). Without `GEMINI_API_KEY` the verdict is `unchecked` and the row stays
  pending — the Python `kyc_retry_tick` retries it.
- **Delivery photos.** Impact photos land `pending` and are gated by
  `PhotoModerationService` before the public feed shows them (§36.1).
- **Caching / Telegram / Web-Push.** As in the shop port, the in-app DB writes
  are ported in full; the external fan-out (Telegram, Web-Push) and the Redis
  cache backend stay on the Python layer during the migration. `CacheService`
  preserves cache.py's no-`REDIS_URL` contract (always a correct cache miss).
- **Uploads.** KYC documents default to `../backend/needy/uploads`
  (`NEEDY_UPLOAD_DIR`, never served publicly); impact photos reuse the
  volunteer uploads dir. The files are written here but served by nginx/Python.

### Shop-module notes

- **Auth model.** Authenticated shop routes take an `@Auth CurrentUser`
  (resolved per-parameter, like `Depends(get_current_user)`) instead of a
  path-wide filter, because `POST /shops/register` is public. `/admin/*` keeps
  its `AdminAuthFilter`.
- **Quota.** `lot_quota_guard` becomes a transaction-scoped
  `pg_advisory_xact_lock` taken in `ShopService` (`@Transactional`) and held
  across the insert — same serialization guarantee as the Python guard.
- **OCR / push / webhooks.** OCR needs `GEMINI_API_KEY` (absent ⇒ 503, as in
  Python). The `needs_match` in-app feed inserts are ported; its best-effort
  Telegram/Web-Push fan-out stays on the Python notifier during the migration.
- **Uploads.** Lot photos default to `../backend/shop/uploads`
  (`SHOP_UPLOAD_DIR`) and are served publicly at `/uploads/…` by nginx/Python;
  receipt photos live in `../backend/shop/receipt_uploads`
  (`RECEIPT_UPLOAD_DIR`) and are reachable only via the auth-checked image
  endpoint. WEBP EXIF-stripping falls back to a byte passthrough where the JDK
  has no WEBP codec.
- **JSON.** DTOs use `SNAKE_CASE` (matching the pydantic schemas); raw `Map`
  responses already carry literal snake_case keys.

## Operational modules (notifier, worker, observability)

The remaining non-HTTP modules — the pieces the earlier steps deliberately left
on the Python notifier/worker — are now ported too, so the Java service can run
standalone once a module is flipped:

| Java | Python source | Notes |
|---|---|---|
| `telegram/TelegramService` | `telegram_service.py` | `sendMessage` (proxy-aware), `notify{Needy,Shop,Volunteer}` + the Web-Push mirror. Uses `HttpURLConnection` so it can route through the SOCKS5 proxy (the JDK `HttpClient` can't). |
| `ai/AiService` | `ai_service.py` | `askSupportAi` — the Gemini support assistant; returns the answer, the `ESCALATE` sentinel, or null so the bot escalates to a human. |
| `push/PushDispatchService` | dispatch half of `push_service.py` | Real Web Push (RFC 8291 `aes128gcm` via JDK ECDH/HKDF/AES-GCM + RFC 8292 VAPID ES256 JWT) **and** FCM HTTP v1 (service-account RS256 → OAuth2 → send). `notifyRole` fans out off-thread; dead endpoints (404/410) and stale FCM tokens (404/UNREGISTERED) are pruned. **Validate against a live push endpoint before cutting `/push` off Python.** |
| `proxy/ProxyService` | `proxy_service.py` | VLESS→xray SOCKS5 tunnel for the Telegram API. The xray binary is supplied out of band (`XRAY_BINARY`); no `VLESS_URL` ⇒ no-op, Telegram goes out directly. |
| `monitoring/MetricsService`, `MetricsFilter`, `MonitoringController` | `monitoring.py` + `main.py` `/metrics /healthz /readyz /stats` | Prometheus text exposition (same series, route-template labels) rendered without a new dependency; the filter records every request; `METRICS_TOKEN` gates the scrape. |
| `background/MaintenanceTasks` | `background.py` + `worker.py` | All six ticks as `@Scheduled`: `expire`, `reassign`, `antifraud`, `reservation_ttl`, `kyc_retry`, `kyc_doc_retention`. Each route/lot runs in its own transaction = the Python per-route `SAVEPOINT`. Reuses `RouteRevertService` and `KycService`. |

**Background tasks are OFF by default** (`BACKGROUND_TASKS_JAVA=off`): during the
migration the Python `worker` owns the ticks, and running both would double-fire
every one. Set `BACKGROUND_TASKS_JAVA=embedded` **only after** stopping the Python
worker (set its `BACKGROUND_TASKS=off`), so exactly one scheduler drives the DB.
The FCM channel likewise stays off unless `FCM_ENABLED=true` + project id +
service-account JSON are present, mirroring the Python gate.

Sentry error tracking (`monitoring.init_sentry`) is intentionally **not** wired —
it needs the SDK dependency and is orthogonal to the HTTP/worker surface; add
`sentry-spring-boot-starter` + `SENTRY_DSN` if you want it on the Java side.

## Run locally

```bash
export SECRET_KEY=<same value as the Python backend, >= 32 chars>
export DB_HOST=localhost DB_NAME=savefood DB_USER=postgres DB_PASS=postgres DB_PORT=5432
# optional (shop module):
export GEMINI_API_KEY=<for receipt OCR / KYC / photo checks; absent ⇒ degraded>
export SHOP_UPLOAD_DIR=../backend/shop/uploads RECEIPT_UPLOAD_DIR=../backend/shop/receipt_uploads
# optional (needy module):
export NEEDY_UPLOAD_DIR=../backend/needy/uploads VOLUNTEER_UPLOAD_DIR=../backend/volunteer/uploads
export KYC_ENCRYPTION_KEY=<Fernet key; absent ⇒ KYC docs stored unencrypted (dev only)>
# optional (volunteer module):
export VOLUNTEER_KYC_UPLOAD_DIR=../backend/volunteer/kyc_uploads VOLUNTEER_KYC_REQUIRED=true
# optional (push module — both keys ⇒ Web Push live, absent ⇒ /push/* 503):
export VAPID_PUBLIC_KEY=<urlsafe-b64 P-256 public> VAPID_PRIVATE_KEY=<urlsafe-b64 private>
mvn spring-boot:run
# listens on :8000 (override with SERVER_PORT)
```

Build a jar / image:

```bash
mvn -DskipTests package
docker build -t savefood-backend-java .
```

## Cutover (not yet wired — deliberate)

To route a module to this service in production, after deploying the container:

1. Add a `backend-java` service to `docker-compose.yml` (build `./backend-java`,
   same `DB_*` / `SECRET_KEY` env as `backend` — plus `GEMINI_API_KEY`,
   `KYC_ENCRYPTION_KEY` and the shared `*_UPLOAD_DIR` volumes — `expose: 8000`).
2. In `savefood/nginx.conf`, point the chosen `location` at `backend-java:8000`
   (`/admin` for step 1; `/shops` + `/lots` for step 2; `/needy` + `/lots` +
   `/ws/needy` for step 3; `/auth` for step 4; `/volunteers` for step 5;
   `/api/v1` + `/impact` for step 6; `/push` + `/tickets/{id}/messages` for
   step 7), leaving the other paths on the Python `backend`. The whole `/auth`
   location can now be flipped as one unit —
   token issuance *and* social login/linking are ported. The only `/auth`-shaped
   dependency left on Python is the Telegram bot **webhook** (`/telegram/webhook`,
   not under `/auth`), which fills login/link tokens; it shares the
   `telegram_*_tokens` tables, so it keeps working across the split.
3. Remove that module's router include from the Python app once verified.

A few shop endpoints sit outside the `/shops` prefix (`DELETE /lots/{id}`,
`PATCH /lots/{id}`, `POST /lots/{id}/confirm_transfer`) — route the `/lots`
location alongside `/shops` when cutting over the shop module. The public
`GET /lots` recipient map lives in the needy module but is served by the same
Java service, so the `/lots` location can be flipped with either step. The
`/ws/needy` WebSocket location needs nginx's `Upgrade`/`Connection` headers (as
the Python deployment already sets). Note the public `/uploads/…` lot photos and
`/needy_uploads` / `/volunteer_uploads` files stay served by the Python
app/nginx; the Java service only writes the files.

This split is intentionally left as an explicit deployment step rather than
enacted here, so the Python modules stay authoritative until you flip them.
