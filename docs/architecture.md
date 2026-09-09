# Architecture

Jay adds group sharing to Clock You's local clock features. Personal clocks remain local. Shared alarms use Clock You's alarm rows, scheduling, ringing and response paths; shared timers use its timer behavior. The social layer owns identity, group state, network synchronization and audio distribution. [Contributing](../CONTRIBUTING.md) describes the source boundaries and integration points.

## Server and Android boundaries

Django REST Framework serves JSON requests, Django ORM manages PostgreSQL state, and Channels serves authenticated server-sent events. One Django application, `server/jay_server/social/`, contains domain models and operations for identities, groups, alarms, timers, sounds and synchronization. Explicit serializers define writable fields and response representations. Dependency versions and hashes are maintained in `server/pyproject.toml`, `server/requirements.lock` and `server/requirements-test.lock`.

Channels routes live streams before the Django HTTP handler. Each connection has a sender task and a bounded subscription; authentication uses a shared executor and releases its database connection before streaming. This keeps long-lived streams from retaining a synchronous request thread each. The stream boundary performs its own authentication, host, method and body checks. ASGI startup and shutdown own the PostgreSQL listener and its broker. There is no channel-layer service.

JSON domain operations use synchronous Django transactions and its psycopg connection pool. One separate asynchronous PostgreSQL connection per web process listens for committed scope invalidations. Provider calls run outside domain transactions in the background worker. [Deployment](../server/README.md#deployment) describes service roles, connection budgets and release sequencing.

Android keeps authoritative social state, pending operations, synchronization cursors and staged pages in `SocialDatabase`. `SharedAlarmLink` maps shared resources to Clock You's alarm rows. Room supplies durable state and WorkManager supplies constrained background execution. A repository mutex serializes synchronization; unique work allows at most one follow-up behind a running pass. Network outages do not prevent already scheduled clock items from operating locally.

## Identity, access and domain state

An identity retains its generated name, 64-character identifier and bearer-secret derivation. Imported profiles represent the same member across devices, with multiple push subscriptions. Registration cannot replace another credential, and retirement disables authentication and permanently reserves the identifier.

Groups own memberships, invitations, shared alarms, timers and sounds. A membership UUID identifies one period of access: leaving and rejoining creates a new generation. Current membership, role, entitlement and group invariants are checked when an operation is applied. Invitations are single-use and expire; a nonempty active group must retain a leader. Offline joins remain provisional until the server validates the invitation.

Client-created resources have UUID keys so dependent offline operations can refer to them. Django uses conventional table and foreign-key names, explicit choices, check constraints and uniqueness constraints. Internal append-only IDs are not synchronization cursors. Historical actor references can become null while their recorded labels remain available.

Shared alarms retain recurrence, date bounds, member-local or group-time-zone interpretation, revision and sound selection. Occurrences track scheduled delivery and each member's response deadline. A delivery acknowledgement means metadata has been applied locally. Dismissal, snooze and ignored outcomes follow occurrence transitions and shared-answer rules, separately from configuration ordering. An earlier actual response can correct a server-inferred ignored outcome.

Deleting a resource permanently reserves its identifier. Payload cleanup cannot allow a stale edit or create to resurrect it. Group deletion immediately denies access and schedules bounded cleanup. Storage deletion is recorded transactionally by a PostgreSQL trigger, including cascade and bulk sound deletion, so removing a database row cannot lose the obligation to delete its object.

## Offline writes and ordering

A local shared change stores its optimistic state and immutable pending operation in one Room transaction. The record includes its UUID, target, method, payload, raw and effective save times, observed revision, membership generation and dependencies. Retries preserve the original record. A timeout does not create a new operation ID or save time.

Editable configuration is ordered lexicographically by effective save time in milliseconds and operation UUID bytes. The greater tuple wins regardless of request arrival order. One accepted save replaces the resource's complete editable state. A superseded save receives a conflict with the currently authorized representation. Creates are insert-only; deletions remain terminal regardless of an edit's timestamp.

Android anchors effective time to server UTC sampled at the request midpoint and `elapsedRealtime` during that boot. Saves advance beyond the last locally saved or observed effective time. Raw wall time is retained separately. After an offline reboot, the persisted server/wall offset and local monotonic floor provide the estimate until a new anchor is available. Reconnection does not restamp queued edits.

The server rejects timestamps more than two minutes ahead of receipt with `clock_invalid`. Android retains the rejected edit for review, refreshes current state and resets an invalid local clock floor so new saves can use a fresh anchor. UUID ordering breaks exact timestamp ties deterministically. Disconnected clocks cannot establish perfect real-world ordering: clock skew can misorder closely spaced saves, but cannot restore permissions or deleted resources.

Timer actions store an absolute resulting expiry. Adding time uses the greater of observed expiry and effective action time, plus the increment. Two members independently extending the same observed expiry from 10:00 to 10:01 converge on 10:01. A member who observes 10:01 and adds another minute produces 10:02. Reconnecting never restarts an expired timer.

Rejected edits lose their optimistic effect and retain a local rejection record. Authoritative snapshots do not erase unsent edits: pending state is a separate overlay. Membership revocation invalidates access, and an operation from an earlier membership generation cannot apply after rejoining.

## Transactions and retries

Mutations other than registration require a UUID `Idempotency-Key`. A receipt binds the identity and credential generation to the method, normalized path/query and validated payload. Receipt claims and successful domain effects commit atomically. Duplicate claims serialize; mismatched requests receive `idempotency_mismatch`. Receipt replay rechecks current access and cannot expose revoked resource data.

Safe response bodies are retained for 30 days. Compact completion markers remain while the identity exists; an older retry receives `operation_completed` and must synchronize instead of executing again. Registration is idempotent on identity and secret. Credentials and expiring signed URLs are not persisted as replay bodies.

Lock ordering starts with the operation receipt, then identities, synchronization scopes, groups, memberships/invitations, resource rows and occurrences. Rows within a category are ordered by identifier. Group mutations hold the group scope lock, apply the domain transition, increment its head revision and write immutable versions and durable work before committing. PostgreSQL notification contains scope IDs only and becomes visible with the commit. Different groups can proceed independently.

Ordinary database operations use a two-second lock timeout and ten-second statement timeout. Contention returns a retryable response; Android retains the same operation. Worker claims use short transactions and recoverable leases. Network calls never hold domain locks.

## Scoped synchronization

An identity scope contains profile, capabilities, private preferences and membership references. A group scope contains common group resources and recipient-filtered occurrences or activity. Group content is stored once per change rather than copied into every member's identity scope.

Each scope has a transactionally ordered head revision and retention floor. Incremental requests freeze an upper revision and page immutable versions in revision/ordinal order. Pages contain at most 200 items or 256 KiB. Recipient filtering occurs before payload disclosure; cursor advancement accounts for scanned positions. Android stages pages belonging to a domain revision and applies them atomically with cursor advancement.

Initial synchronization and expired cursors use snapshots at a fixed upper revision. Snapshot pages select the latest version of each resource at that boundary, exclude deleted resources and canceled occurrences, and include pending occurrences plus two days of resolved occurrences. Tokens expire after 15 minutes and bind the cutoff and access generation. Superseded versions remain for at least 30 days, alongside the latest live versions and permanent deletion markers.

Android replaces a scope's authoritative state only when its snapshot completes, then fetches later changes. Interrupted snapshots restart without treating incomplete pages as the entire group. Membership removal discards local group access and cursors; a new membership generation requires a new snapshot. History uses separate descending keyset pagination and does not replay old notifications during bootstrap.

SSE and Firebase messages are invalidation hints. Clients obtain authoritative data through authorized synchronization requests and acknowledge alarm metadata only after applying it locally. Listener recovery invalidates subscribers so hints lost during a disconnection cannot prevent catch-up. Subscriber queues coalesce scope IDs; overflow requests a complete refresh rather than dropping unrelated changes. Client synchronization runs at most two requests concurrently and reconnects with jittered backoff.

## HTTP contract

The [OpenAPI schema](openapi.json) defines the current JSON API. The server generates it from DRF serializers at `/openapi.json` and serves the bundled Scalar reference at `/docs`. Tests validate response examples and check generated-schema drift.

Authentication uses `Authorization: Bearer …` and `X-Jay-Identity-ID`. Configuration writes include `saved_at`; group-scoped writes include `membership_id`, including DELETE bodies. Group creation supplies the group and creator-membership UUIDs. Complete configuration uses PUT; identity names and membership roles use PATCH for their single editable field.

Unknown fields, invalid types and omitted required fields are rejected. UTC instants carry timezone offsets; dates use ISO dates and local clock times use `local_time_ms`. API bodies are limited to 256 KiB. The shared exception representation is `{code, detail, errors}`, with field errors carrying path, code and message. Validation, access, absence, conflict, size, throttling and temporary failure retain distinct HTTP statuses.

Creates return 201, updates 200, deletion/leave/reset acknowledgements 204 and queued sound verification 202. History returns `items` and `next_before`, with 50 entries by default and a maximum of 100. Per-process throttles are soft overload controls rather than distributed quotas: 120 writes, 600 reads and ten upload sessions per minute per identity, and 60 registrations per minute per source IP.

## Audio and workers

Shared metadata scheduling never waits for audio preparation or transfer. Android stages the source and upload manifest durably and performs upload/download/decode in separate bounded work. Shared sound IDs can refer to pending assets. Missing audio uses the member's default ringtone at ring time; disabled sound stays silent. Audio completion updates the cache without rescheduling or changing an already ringing occurrence. [Shared sounds](entitlements.md) describes encoding, playback and entitlement policy.

Completing an upload queues verification and returns immediately. The worker streams and validates an immutable final copy, then rechecks access, entitlement, references and status before publishing readiness. The uploader's staging URL cannot modify the ready asset. Pending uploads expire after 24 hours. Signed upload URLs last 900 seconds and downloads 300 seconds; clients obtain fresh URLs when needed.

`run_worker` owns deadline and maintenance loops plus separate bounded provider execution. Each instance allows four push tasks, one verification and one deletion task. Deadline work alternates rescheduling and due outcomes in bounded batches, independently of provider latency. Long group transitions retain durable rescheduling progress and invalidate obsolete revisions immediately.

Provider work has a 60-second lease renewed every ten seconds, bounded timeouts and exponential retry with jitter up to a 300-second delay. Failures lasting 24 hours retain a classified terminal error for operator attention. Provider delivery is at least once; activity IDs prevent duplicate effective client actions. Heartbeats track progress by work class. Shutdown stops new claims and allows short work to finish; expired leases can be recovered by another worker.

[Deployment](../server/README.md#deployment) covers health checks and recovery. [Performance guidance](../server/README.md#performance) describes capacity measurement and device checks.
