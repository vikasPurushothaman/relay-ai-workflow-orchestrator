# Relay recovery protocol

Task 1.8, 2026-09-25. **Design only; SQL and runtime behavior are not verified.** Sources: [PDF evidence](source-review/brief.txt), [pack data model](source-review/pack/docs/DATA_MODEL.md), [API contract](source-review/pack/docs/API_CONTRACT.md), [guide](source-review/pack/docs/IMPLEMENTATION_GUIDE.md), [mock world](source-review/pack/scripts/mock_world.py), [architecture](ARCHITECTURE.md), [data model](DATA_MODEL.md), [states](STATE_TRANSITIONS.md), [semantics](WORKFLOW_SEMANTICS.md), [requirements](REQUIREMENTS.md), [roadmap](../PROJECT_PLAN.md). This completes the protocol deferred by tasks 1.4–1.7; migrations and Java implementation remain later phases.

## Guarantees and limits

One active worker process, one dispatched handler at a time. A heartbeat thread may renew ownership but never execute nodes. API requests still race with the worker, so all execution changes serialize on the run row. Stop the old worker before starting a replacement; this is a deployment prerequisite, not a distributed leader-election feature. A unique process-start UUID is lease_owner; never reuse a previous process identity.

MySQL domain state plus queue intent commit together. No network call inside a database transaction. A database fence protects persisted state, not remote services. At-least-once dispatch plus stable receiver replay detection can preserve one intended effect in the supported drill; a timeout does not prove that an effect failed. No unconditional exactly-once guarantee. Arbitrary HTTP receivers and AI providers may execute/charge twice after uncertainty. No extra broker, multi-worker product, compensation, manual replay or inbound trigger deduplication is introduced.

## Configuration and durable policy

These are selected recovery defaults; only RELAY_DB_TX_TIMEOUT_MS is bound in bootstrap as of task 2.5 (1000..60000 ms for deployment, default 5000). Other recovery settings and run-policy validation remain engine implementation work. Version selection is recorded in setup task 2.2. Accepting a run copies the effective settings below (except polling) into immutable `runs.execution_policy` JSON with policy_version=1. Changing environment defaults affects new runs only. Claiming uses the stored policy; unsupported/invalid policy stops that job from dispatching and surfaces an operational error, never silently replaces it. The API acceptance service validates policy before writing a run.

| Setting | Default | Meaning |
| --- | --- | --- |
| RELAY_JOB_POLL_MS | 250 | Idle candidate poll interval, positive. |
| RELAY_JOB_LEASE_MS | 60000 | Ownership validity measured by database UTC. |
| RELAY_JOB_RENEW_MS | 10000 | Heartbeat interval, separate from dispatch thread. |
| RELAY_DB_TX_TIMEOUT_MS | 5000 | Bound for a short transaction including lock wait; drivers/connection waits also bounded during setup. |
| RELAY_HTTP_TIMEOUT_MS | 10000 | Total call deadline including connection and body read. |
| RELAY_AI_TIMEOUT_MS | 30000 | Total provider call deadline. |
| RELAY_RETRY_MAX_ATTEMPTS | 3 | Initial dispatch plus at most two transport/recovery retries per logical step; one AI schema repair is additional. |
| RELAY_RETRY_BASE_MS | 1000 | First retry delay. |
| RELAY_RETRY_MAX_MS | 30000 | Exponential delay ceiling. |

Validate positive integral milliseconds within signed 32-bit bounds, max attempts 1..100, base <= maximum, and lease > renew + max(HTTP timeout, AI timeout) + 2 * DB transaction timeout. Default check: 60000 > 10000 + 30000 + 10000. Check arithmetic without overflow. This headroom reduces accidental expiry; fencing is still mandatory after pauses/outages. Timeout cancellation must close the local transport; no next local call until its task has actually stopped. Network libraries must not add hidden retries. Persist UTC deadlines for durable scheduling, use monotonic elapsed time for local call/transaction deadlines. Database UTC is sampled after acquiring locks, not once before a long wait.

`execution_policy` is required for every run, including seeds' runs. It contains no credentials. Per-run policy preserves budgets across restart; provider endpoint/model and concrete side-effect request are frozen at preparation, while deployment credentials are attached server-side. A credential change can still make replay fail; never switch provider/model or destination silently during recovery.

## Lock order and transaction boundaries

Use InnoDB and explicit READ COMMITTED application transactions. Workflow mutation/trigger acceptance locks workflow first; existing-run operations never lock workflow. Global order where rows apply: **workflow → run → job → step → attempt → approval**. Lock multiple steps/approvals in increasing sequence/ID order. A decision may perform a nonlocking lookup of approval ID to discover run ID, then lock in that order and re-read the approval. Never hold an approval/job lock and then acquire the run lock.

Candidate scans below are nonlocking hints; their results cannot authorize work. In the short claim transaction, lock run by PK first, then its job by PK with FOR UPDATE and recheck eligibility/due time. One worker does not need a queue-first SKIP LOCKED query. A bounded lock timeout returns to polling; it does not authorize executing the hinted target. This avoids inversion with API cancellation. Locking reads require transactions, and application transactions must handle deadlocks; see the [MySQL locking-read manual](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html) and [deadlock guidance](https://dev.mysql.com/doc/refman/8.4/en/innodb-deadlocks-handling.html). Those pages inform the design, not proof of the eventual mappings.

| Boundary | Atomic writes | Before/after crash consequence |
| --- | --- | --- |
| B01 acceptance | Run/input/definition/policy snapshot and initial ready job. | Before commit no accepted work; after commit recoverable even if HTTP acknowledgement lost. |
| B02 claim | Owner, lease, incremented generation; queued→running and started_at when first claimed. | Before commit no ownership; after commit recover after expiry, even with no step yet. |
| B03 prepare | Step allocation/cap reservation if new; frozen input/request/key; attempt number/cause; retry consumption; clear next_attempt_cause. | Before commit no call allowed; after commit dispatch may have happened, so abandoned running attempt is uncertain. |
| B04 outcome | Attempt result/usage, step completion/wait/error, run cursor/state, ready next work or inactive job. | Before commit replay existing prepared request; after commit do not repeat completed step. |
| B05 human wait | Preparation attempt result, pending approval, waiting step/run, inactive job. | No orphan request or runnable continuation. |
| B06 decision/cancel | Decision/closure evidence, step/run result, continuation or inactive job. | One committed outcome; repeated decisions conflict; cancel precedence follows states. |
| B07 recovery planning | Abandoned attempt uncertain, retry intent/deadline or terminal/cancel outcome, cleared lease on yield. | Retry scheduling and uncertainty commit together; crashes do not reset budgets. |

B03 may first allocate/resolve a step without an outbound invocation when validation fails; commit failed trace/run with no attempt rather than fabricate a call. Condition/delay/approval preparation performs only bounded local computation; its attempt and outcome may commit in one transaction. If a prepared local invocation is nevertheless left running, the same recovery rules apply. Do not persist remote result in one transaction and enqueue continuation in a later transaction.

## Indexed claim and ownership SQL

Illustrative named-parameter SQL, to translate into the selected JDBC/JPA adapter and test on MySQL in 3.10/4.3. These statements have not been run. Use two scans, index I06 `(status, available_at, run_id)` and I07 `(status, lease_until, run_id)`, alternating ready/recovery opportunities so neither starves. Fetch at most 32 candidates per scan; try each once before polling again.

```sql
SELECT run_id FROM queue_jobs
 WHERE status = 'ready' AND available_at <= UTC_TIMESTAMP(6)
 ORDER BY available_at, run_id LIMIT 32;
SELECT run_id FROM queue_jobs
 WHERE status = 'leased' AND lease_until <= UTC_TIMESTAMP(6)
 ORDER BY lease_until, run_id LIMIT 32;
```

For each hint, start transaction, lock run then job, sample `:db_now`, recheck run state and the exact ready/expired predicate. Terminal/waiting_approval cannot dispatch. A cancellation request takes recovery/settlement priority. Increment generation with overflow rejection (never wrap), assign owner and lease_until = db_now + stored lease duration, commit. Keep the immutable ownership token `(run_id, lease_owner, claim_generation, target_node_id, step_sequence)` locally; update its sequence only when B03 allocates it in the owned transaction. Reclaiming expired work changes generation even for the same process identity.

The following fence is checked after run/job locks and again in the final job update in each worker mutation transaction; exactly one matched row is required. `:db_now` is a fresh database time sample at the check. Expiry is inclusive: lease_until <= db_now is expired.

```sql
UPDATE queue_jobs
 SET status = :new_status, updated_at = :db_now,
     lease_owner = :new_owner, lease_until = :new_lease_until
 WHERE run_id = :run_id
   AND status = 'leased'
   AND lease_owner = :lease_owner
   AND claim_generation = :claim_generation
   AND target_node_id = :target_node_id
   AND step_sequence <=> :step_sequence
   AND lease_until > :db_now;
```

Actual B04 also updates target, due time, next_attempt_cause and sequence as required; the fence applies to their old values. NULL-safe sequence comparison permits claims before allocation. On outcome, also require attempt `(run_id, step_sequence, attempt_no)` to be running with `attempt.claim_generation = :claim_generation`; validate expected step status and run running state. A prior committed cancel request changes the run outcome to cancelled, not the current step result. Any failed guard rolls back **all** attempt/step/run/job writes and token additions. Never return success from a zero-row update. Commit can complete after the sampled lease time while locks are held: no newer owner can be installed until release; the serialization point is the successful guarded mutation, not a wall-clock promise about commit duration.

Preparation and the heartbeat share the current ownership token safely: when B03 attaches a sequence, publish that token change before the heartbeat can renew; a renewal with the previous NULL sequence fails safely. Renewal locks run then job, requires the same live token, refreshes lease to database now plus duration, and does not change generation, attempt count or due time. Never renew an expired lease. When cancellation is observed, stop renewal and let current bounded call settle; recovery settles if completion loses its fence. A renewal failure/unknown commit stops new dispatch; best-effort stop current local transport, await termination, then reconcile ownership. Do not silently reclaim while an old local call task is still running. Do not apply a late result under a newly claimed generation.

## Prepare, dispatch and completion

After B02, B03 rechecks live ownership, cancellation and state, allocates or reuses step per task 1.7, resolves input once, validates current gate evidence and persists concrete request. Every side-effect attempt uses `{run_id}:{sequence}`; keys never contain attempt/generation. All attempted requests for that effect preserve method, URL, business headers and body. Canonical JSON bytes for the transport body are persisted in dispatch_request (alongside parsed trace data) so retries need not depend on serializer changes. Reject user Idempotency-Key; engine appends its persisted key at dispatch. GET/pure/AI steps have NULL action key.

Prepare an attempt only if there is no running attempt for the step, budget permits and `next_attempt_cause` matches the durable intent. Increment final_attempt, create running attempt with current generation/request/provider metadata, consume a retry only for transport_retry/recovery, then clear the intent in the same transaction. No send until commit is known. An ambiguous prepare commit is reconciled by reading that exact step/attempt under run lock; do not allocate another or send merely because an exception occurred. If the process knows it never invoked transport, a still-owned prepared attempt may be invoked once after reconciliation; after process restart that knowledge is gone and recovery treats it as uncertain.

Immediately before invocation, the worker must know it still owns a live lease and has no observed cancellation; renew/check if necessary. This cannot make a remote send atomic with cancellation, so only the already admitted current invocation may race, as specified in 1.6. Attach server credentials, send once with total deadline, then persist B04 under the original token/attempt. Local result success cannot advance the graph until B04 commit is known.

B04 records usage once while transitioning running attempt to terminal. Retryable failure records failed attempt and waiting/retry step, sets next_attempt_cause and available_at, releases ownership. Success writes output/chosen successor; if another node exists, ready job points to it with step_sequence NULL, next_attempt_cause initial and retry_count=0. End/rejection/cancel sets inactive, clears intent and ownership. Approval wait also clears intent; approving with successor creates initial intent and resets per-step retry_count. Pure delay completion creates no additional attempt. The step key/request never changes when the job is reused for retries.

## Retry and repair policy

Let M be stored RELAY_RETRY_MAX_ATTEMPTS. `job.retry_count` is the number of **prepared additional transport/recovery attempts** for its current logical step, initially 0. Initial invocation does not increment it. Before preparing a transport_retry or recovery invocation require retry_count < M-1, then increment in B03. Merely claiming, renewing, scheduling, losing a candidate race or rolling back does not consume it. A prepared invocation abandoned before its actual send still consumes its reservation; recovery cannot prove it was never sent. Reset only for a new logical step, never for repair or restart.

An AI first schema failure reserves ai_repair_count=1 and persists `steps.ai_repair_request` including validation feedback in B04. Schedule `next_attempt_cause=schema_repair` at available_at=db_now. This one additional invocation does not increment retry_count and is allowed even if the transport allowance is already spent. It cannot be scheduled again: subsequent invalid output fails immediately. A failed/uncertain repair uses transport_retry/recovery and the same persisted repair request if shared retry budget remains. Total prepared attempts are bounded by **M + ai_repair_count**, at most 4 by default for AI, 3 for other nodes. A first successful valid response completes immediately. Restart cannot regenerate a different repair prompt or reset either counter.

For the upcoming retry number k=retry_count+1, persist delay_ms=min(RELAY_RETRY_MAX_MS, RELAY_RETRY_BASE_MS * 2^(k-1)), using saturating arithmetic, and available_at=db_now+delay_ms. Default retries wait 1000 then 2000 ms. No jitter for this single-worker demo. The repair itself is immediate; transport failures during repair use the same global k. Record cause, planned k and delay in attempt error metadata; next_attempt_cause preserves intent even if a claimant dies before preparation. Once scheduled, do not recompute the deadline after restart. Delay nodes independently keep their original resume_at; never substitute retry backoff for an existing delay.

| Outcome | Decision |
| --- | --- |
| Connection failure, connection reset, timeout, HTTP 408/429/500/502/503/504 | Retry within shared budget; same keyed business request. For generic HTTP these statuses fail the attempt, not a succeeded node carrying an error response. |
| Other non-2xx response, bad credentials, invalid destination/TLS certificate, malformed adapter response, permanent business 400/404/409 | Fail without ordinary retry; preserve sanitized status/reason. Redirects not automatically followed; rejected redirect is a permanent failure until explicit destination policy is implemented. |
| Invalid AI JSON/schema, first time | One persisted schema repair, regardless of remaining transport allowance. |
| Invalid AI JSON/schema again, invalid template/param, approval denied, step cap | Permanent failure; never ordinary retry to bypass the guard. |
| Abandoned running attempt | uncertain; recovery retry if budget and no cancellation, otherwise fail/cancel with unknown-effect evidence. |
| Database outage/deadlock | Database reconciliation/transaction retry, not a new remote invocation or consumed node retry. |

For HTTP 429/503 with a valid Retry-After, schedule no earlier than both exponential backoff and the server hint (integer seconds or HTTP date interpreted relative to database UTC). Persist that due time; it may exceed the exponential ceiling, which only caps locally generated backoff. Ignore malformed/past hints, clamp negative wait to zero, and fail clearly if a future timestamp cannot fit storage. Header parsing gets adapter tests in 4.9/5.9. Other provider-specific retry signals may only map into this documented classification, not create an unbounded hidden retry loop.

Exhaustion records retry_exhausted with node, prepared attempt count and last cause; no further invocation. If the last attempt was uncertain, retain unknown-effect evidence even though the run is failed. Cancellation has precedence and never retries to discover whether a cancelled effect occurred. External effects cannot be undone by changing local status.

## Recovery decision table

After reclaim B02, inspect persisted state under run/job/step/attempt locks. B07 is the sole exception to requiring the old attempt generation equal the new job generation: it may mark an old running attempt uncertain only after successful expired-lease reclaim, verifying its recorded former generation and identity under the run lock. It cannot attach a result to that attempt or send on its behalf. Reclaim is ownership acquisition, never itself permission to repeat a network call.

| Case | Durable evidence | Recovery action |
| --- | --- | --- |
| C01 | Accepted queued/ready job, no step | Claim/start and prepare first visit normally. |
| C02 | Leased, no allocated step or allocated step with no attempt | Resume preparation, preserving cap reservation if any; no consumed transport retry. |
| C03 | next_attempt_cause present, step waiting/retry, no running attempt | Reuse pending intent/deadline; claim before prepare did not consume budget. Yield back to ready if future due. |
| C04 | Running attempt, unknown remote outcome | Mark uncertain once; if allowed schedule recovery intent/backoff, step waiting/retry and ready job; else terminal exhaustion. Never immediately resend in claim transaction. |
| C05 | Step waiting/delay | Reuse resume_at; future deadline yields ready unchanged; due deadline completes step and advances atomically, no new attempt. |
| C06 | waiting_approval and pending request | Inactive job, no polling dispatch; decision service alone resumes. No duplicate request on restart. |
| C07 | Prior step completed and ready successor committed | Execute successor, never repeat completed predecessor. |
| C08 | Cancel requested, active or abandoned attempt | Known result may settle; otherwise mark uncertain, cancel unfinished step/run, inactivate job, **do not resend**. |
| C09 | Terminal run or duplicate/late callback | No dispatch; stale completion cannot mutate state/usage or enqueue work. |
| C10 | Impossible mixed state, missing row, corrupted policy/request | Refuse dispatch and report invariant violation; do not invent data or repair by replay. Operator diagnosis required. |

Waiting_approval/terminal jobs should never be leased: if discovered during scans, treat as C10, not automatically reopen the run. On restart before lease expiry, leave it owned and wait; do not bulk-reset all jobs. An early cancellation request may wait for expiry if the original process is gone. If recovery scheduling commits and another crash occurs before the next prepare, C03 reuses it; an uncertain attempt does not consume another retry merely from repeated scans.

## Ambiguous commits, outages and shutdown

For a deadlock or lock timeout, explicitly roll back the entire application transaction and retry a fresh transaction at most twice (three tries total), waiting 50 then 100 ms outside it. Re-read guards on every try. Never retry a network call inside a transaction-retry callback. After repeated DB failure, stop new dispatch and return to bounded polling/readiness failure; existing durable jobs remain. Transaction timeout/rollback handling must be verified with actual JDBC and MySQL, not assumed from an annotation.

Lost connection during COMMIT means outcome unknown, not rollback. B01 clients may not receive a run ID despite committed work; document this without promising trigger deduplication. B02/B03 worker reads the exact token/attempt before dispatch. B04 worker reads exact attempt/step/job: if committed, no re-send/usage addition; if not committed and token still live, retry persistence of the same in-memory outcome only. If ownership lost, discard result and let fenced recovery decide. B06 API callers query state; duplicate decided approvals conflict and terminal cancel returns 409. No blind API command replay following an unknown commit.

During DB outage no new work is accepted/dispatched without successful persistence. A current network call may finish; try to persist its result only while live ownership can be established. Lease loss stops dispatch and preserves uncertainty; no in-memory continuation. On graceful shutdown stop claims, allow one bounded in-flight result to settle with renewal, then stop heartbeat. Forced stop leaves lease for expiry; no reset of cap, retries or deadlines. Unsupported simultaneous workers remain outside scope even if database stale-write tests pass.

## Receiver assumptions and drill

The pinned mock world caches successful responses by Idempotency-Key in memory. Failure injection occurs before side-effect handling; non-success results are not cached. Cache lookup, action, ledger append and cache insertion are separate critical sections, so overlapping same-key requests are not proven atomic. A client timeout/worker death does not necessarily stop a request already running on the server. Merely stopping the old worker is insufficient to prove remote requests no longer overlap.

For the supported post-effect drill, keep mock world alive, use its normal zero-latency/no-failure mode, confirm the first response was produced/cached through a controlled worker hook after response receipt but before B04, kill that worker, and restart one worker after the old process has stopped. For the delay drill kill during the persisted 20-second wait. Do not reset mock cache or ledger between kill and resume. A separate timeout/slow-server test must report overlap limitations rather than claim exactly-once proof. No changes to pinned mock source.

Require successful slow-fulfillment run plus three intended successful non-replayed effects: confirmation email, shipment and shipped email, each with nonempty persisted key; replay ledger entries may exist. Run supplied duplication checker as supplementary evidence; an empty ledger passing is insufficient. Legitimate repeated loop effects have distinct sequence keys even if payloads match. Cancellation/exhaustion after uncertain remote execution may leave effects without successful local completion; preserve that evidence. Real/provider exactly-once is not promised.

## Verification roadmap

All runtime tests below are **not run**. Prerequisites: implemented schema/API/worker, Testcontainers MySQL with barriers/fault hooks, controllable clock/transport, unchanged live mocks for drill. Document arithmetic checks cannot demonstrate SQL correctness.

| Case | Inputs/actions | Expected outcome / owners |
| --- | --- | --- |
| K01 | Stop worker; accept run; inject failure before/after B01 commit. | Atomic run/job; accepted durable work survives; unknown response not false rollback. Tasks 4.2, 3.10; V05. |
| K02 | Both candidate types, future due, expiry equality, held run lock and changed candidate. | Indexed scans; lock order/recheck; no early or duplicate claim. Tasks 4.3, 3.10; V06. |
| K03 | Stale owner/generation/target/sequence/attempt; renew before/at expiry; pause beyond lease. | All writes rollback on bad fence; no resurrected lease/late output. Tasks 4.3, 4.5, 4.14; V06. |
| K04 | Crash before B03, after B03 before send, after effect before B04, after B04. | No premature send; uncertainty preserved; same key/request; completed predecessor not repeated. Tasks 4.11, 4.14; V10. |
| K05 | M=1 and M=3; transient/permanent errors; repeated crash after recovery scheduling and after retry prepare. | Reservations/bounds correct; waits 1000/2000 defaults; no restart reset. Task 4.12; V11. |
| K06 | First invalid AI after transport budget used; crash before/after repair preparation; failed repair transport; second invalid response. | One repair, max M+1 attempts, frozen repair prompt, shared transport limit. Tasks 5.7, 5.8; V14. |
| K07 | Restart delay before/at/after deadline; claim crash without dispatch. | Same deadline, no extra attempt/count, one continuation. Tasks 4.8, 4.14; V10. |
| K08 | Approval/cancel/complete competing commits; cancel then kill worker. | State precedence, one decision, no cancellation recovery resend. Tasks 5.3, 5.11; V12, V17. |
| K09 | DB outage, deadlock/timeout, lost acknowledgement at B02/B03/B04/B06. | Full rollback or read reconciliation, no blind resend/double usage; bounded transaction retry. Tasks 3.10, 4.14; V06, V11. |
| K10 | Change defaults/provider environment after preparation; normal step loops; invalid persisted policy. | Stored policy and concrete request reused; distinct loop keys; invalid policy refuses dispatch. Tasks 4.11, 4.14; V07, V10. |
| K11 | Live delay and controlled post-response kill drills, empty-ledger negative control, receiver reset/overlap limitation. | Three intended effects and terminal success plus checker output; no broader guarantee claimed. Tasks 7.3, 7.4; V10, V21. |
| K12 | Timeout/read hang, 429 Retry-After, invalid config, recovery at cap, wrong gate, graceful shutdown. | Finite calls, persisted due time, rejected unsafe config, existing visit reused; no unauthorized continuation. Tasks 4.9, 4.12, 4.13, 5.4; V09, V11, V13, V16. |

Run `python3 scripts/check_recovery.py` plus seven prior design checkers and supplied pack validator. New checks inspect boundaries/SQL guards/schema links and calculate lease/retry examples; mutated copies must fail. Manual K01–K12 walkthroughs supplement them. Evidence: [recovery-verification.txt](recovery-verification.txt). No API routes changed; [API documentation](../API_DOCUMENTATION.md) remains planned. D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates). Next task 1.9 is console design. Keep all work local; never push or publish.

## Implementation status after Phase 4

Tasks4.1–4.14 implement B01–B04 and B07 for deterministic execution, indexed candidate
scans, generation/owner/sequence/expiry fences, durable retry intent and delays, persisted
step caps, frozen requests and per-visit keys. `EngineStore` uses Spring-managed short
MySQL transactions and explicit guarded SQL; `WorkerLifecycle` dispatches one handler at
a time with a separate renewal thread. A failed/ambiguous DB transaction never directly
causes another remote send; persisted state is reclaimed/reconciled after lease expiry.
The JPA acceptance transaction commits run and initial job together. No schema changes.

Actual tests now cover MySQL locks/fences, initial and outcome rollback, duplicate/late
results, recovery before preparation, abandoned calls, preserved budgets, delay restart,
query index selection and a packaged worker killed after a real mock-world effect.
The replacement replays the frozen request with the same key and the receiver records
one original effect. See [Phase4 evidence](phase4-verification.txt). Historical design
statements above describe task1.8's status; they are not the current verification result.

B05/B06 human decisions/cancellation, AI repair/usage and their Phase5-specific recovery
cases remain pending. AI/approval handlers fail explicitly for now; sensitive actions
already fail closed without earlier same-run approved evidence. No multi-worker support
or arbitrary-receiver exactly-once guarantee is added. Database connection/driver limits
remain deployment bootstrap settings in addition to the frozen run recovery policy.


### Phase5 implementation update (2026-09-28)

AI and approval execution and human control are now implemented. The worker persists
pending approval/step/run/job state atomically and releases ownership. Human decisions
serialize with cancellation and worker results on the run lock. Approved earlier same-run
human evidence is checked on every sensitive attempt; AI output cannot create that evidence.
Strict output JSON/schema validation permits one durable corrective request. Transport and
recovery use the original shared retry budget, provider/model and frozen request; lost
usage is explicitly incomplete. Cancellation prevents new dispatch/retry/repair/successors
and preserves a known in-flight result before cancelling the run.

D01 was clarified by the user on2026-09-28: retain the supplied graph, refund_request pauses
for approval, complaint/question follows notification, and always block unapproved sensitive
actions. Earlier design-stage statements about an open discrepancy are historical. No
canonical pack bytes or seed graphs were changed. See [API contracts](../API_DOCUMENTATION.md),
[setup](SETUP.md) and [actual evidence](phase5-verification.txt).
Run-read/trace projections and interactive console data remain Phase6.
