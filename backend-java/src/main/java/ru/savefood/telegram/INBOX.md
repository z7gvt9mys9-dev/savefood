# Telegram webhook inbox

`V19__telegram_update_inbox.sql` must be applied before the new webhook accepts
updates. Authenticated requests are limited to 64 KiB (including chunked bodies),
and only the update ID and message fields used by the bot are retained. The
webhook responds after the inbox insert commits; duplicate IDs return success.
Database acceptance failures propagate so Telegram can retry.

The existing Spring scheduler polls the inbox. Configuration defaults:

| Property under `savefood.telegram` | Default | Bounds |
| --- | --- | --- |
| `inbox-poll-ms` | 1000 | Spring fixed delay |
| `inbox-batch-size` | 10 | 1–100 updates per poll |
| `inbox-max-attempts` | 5 | 1–20 attempts, including abandoned claims |
| `inbox-backoff-seconds` | 30 | 1–3600; exponential delay capped at one hour |
| `inbox-claim-seconds` | 120 | 1–3600 before an abandoned claim is eligible |

Each claim commits its attempt count. Processing then locks that exact inbox
ID/attempt with `FOR UPDATE SKIP LOCKED`. A live worker keeps the lock, including
during external I/O; expiry alone cannot let another worker steal it. A crashed
worker releases the database lock, allowing a later poll to recover the claim.
After the attempt limit, the row becomes `failed`; it is not automatically retried.

Chat insertion and inbox completion share one database transaction. The existing
ChatService transaction joins it, retaining ticket authorization and locking.
There is no committed-chat/unprocessed-inbox crash window: either both commit,
or neither does. Link/login database changes executed by the bot also join this
transaction. Do not move these writes to an independent transaction or background
thread without adding an update-specific idempotency key.

The current webhook flow creates no persistent support/escalation or in-app
notification rows: support escalation is a Telegram message, and PushDispatchService
performs external delivery. Recipient selection and message content are unchanged.
Telegram replies, support forwards, push deliveries, and AI requests have no
exactly-once guarantee. They can repeat after a worker crash/transaction rollback,
and best-effort delivery can fail. They may already have been sent when a database
transaction rolls back. Holding the processing transaction across these calls
uses one database connection per worker and can delay a competing ticket change.

Completed and failed payloads are cleared, including one-time login/link tokens;
only the ID/status tombstones remain. Do not delete these IDs or requeue completed
rows: retaining them is what prevents delayed duplicate delivery from running again.
