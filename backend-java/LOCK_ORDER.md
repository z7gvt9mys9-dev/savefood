# Scoped route / recipient transaction lock graph

Recorded before the lock-order fix. Arrows denote acquisition of another row
lock, including implicit UPDATE locks; repeated writes retain existing locks.

| Flow | Existing locks, in acquisition order |
| --- | --- |
| startRoute | One lot; open self-pickup tickets (bulk cancellation); selected delivery tickets (stop order); remaining open tickets (bulk cancellation); INSERT a new route |
| cancelTicket | Recipient; active routes of the preselected volunteer (unordered); owned ticket; its lot for inventory restoration; rewrite the already locked route |
| completePoint | Current owned active route; target ticket on fulfillment; recipient profile on last-received update; rewrite the route. Shop completion only writes route/notifications |
| eraseAccount | Recipient; live owned tickets via bulk cancellation; their lots in RETURNING order; all owned tickets for PII removal; active and historical routes containing those ticket IDs, ascending route ID |
| finish / admin reset / timeout / antifraud reset | Route; lot if not picked up; assigned tickets in route point order. After pickup: route then assigned tickets |

Start and cancellation form lot/ticket cycles. Completion and erasure form
route/ticket cycles. Reverting a route also introduces route/lot edges.
Notifications are inserted by these flows. Erasure additionally deletes ticket
messages, recipient notifications, profile and user records, redacts rating
comments, and updates the already locked recipient; user deletion cascades to
authentication/subscription records. File cleanup is scheduled after commit.
These side effects are preserved; no other transaction families are audited.

## Canonical order

Acquire **lots → existing routes → tickets**, ascending primary key within each
class, before mutation. A flow may omit a class it never locks. Recipient write
serialization remains before these classes in cancellation/erasure. A newly
inserted route is private to startRoute until commit, so its insertion after
ticket locks does not acquire an existing route row held by another flow.

Start locks every open ticket for its locked lot in ID order before scoring and
assignment, including tickets it will cancel. Revert sorts and deduplicates
ticket IDs independently of visit order. Revert callers preselect the route's
lot, lock it, lock the route, and revalidate the lot association and route state.

Cancellation preselects lot/assignment, locks the lot and active routes in ID
order, then locks the owned ticket and checks the preselection again. Erasure
holds the recipient lock to stabilize ticket membership, preselects and locks
all referenced lots in ID order, discovers/locks route copies only after those
lot locks (a concurrent start may just have committed), then locks all owned
tickets in ID order. It revalidates ticket/lot membership before any writes and
uses current locked ticket status for cancellation and photo cleanup. Route PII
scrubbing only writes those already locked route copies. No deadlock retry is
introduced.

## Focused verification

`RouteLockOrderConcurrencyIT` pauses real transactions with latches at the old
inversion boundaries and waits for `pg_blocking_pids` to confirm the competing
transaction is blocked before releasing the holder. Both start/cancel and
complete/erase winner orders run three times. Additional cases cover duplicate
cancellation, erasure versus finish, a route committed after erasure's ticket
preselection, and ascending multi-row lot/route/ticket locks.

The two original cycles were also tested against an isolated copy of the
pre-fix sources: both produced PostgreSQL `deadlock detected` in all three
repetitions. With the fix, 85 focused tests passed across route, reservation,
cancellation, recipient deletion/privacy, timeout, and file-erasure suites.
The normal Maven invocation currently fails test compilation in the unrelated
`VolunteerKycRetentionIT` (constructor argument type mismatch). Verification
used a temporary POM outside the repository excluding that class; no project
build configuration or unrelated source was changed.
