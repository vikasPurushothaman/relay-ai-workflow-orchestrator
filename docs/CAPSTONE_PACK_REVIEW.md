# Capstone pack review — task 1.1

Reviewed 2026-09-25. This is source inspection, not an implementation specification or a claim that an API exists. Task 1.2 will map requirements to implementation and verification tasks.

## Provenance and inspection coverage

The four-page PDF by Airtribe links on page 4 to [relay-capstone](https://github.com/airtribe-projects/relay-capstone). All four pages were extracted and visually reviewed. The source PDF is `/Users/user/Downloads/G9I1BMX8E2C99J1M.pdf`; [extracted text](source-review/brief.txt) retains extraction artifacts and is not a replacement for the PDF. [PDF provenance](source-review/brief-provenance.json) records its SHA-256 and embedded link.

The local source snapshot is pinned to commit `fe30f4adc2e30ae3b6175ab363a20019f8944da1`. [Pack provenance](source-review/pack-provenance.json) records its archive hash, retrieval URL, and hashes of all 16 files. The files remain unchanged; task 2.8 reuses this canonical snapshot through a hash-verifying localhost launcher and isolated checks documented in [mock setup](MOCKS.md). No LICENSE/COPYING file or explicit license grant was found. Preserve Airtribe attribution and revisit redistribution terms before any manual publication. This review creates no remote repository, deployment, or upload.

| Required category | Inspected source | Findings covered below |
| --- | --- | --- |
| Problem statement | [RELAY_PROBLEM_STATEMENT.md](source-review/pack/RELAY_PROBLEM_STATEMENT.md) | Must Have, optional scope, deliverables |
| Implementation guide | [IMPLEMENTATION_GUIDE.md](source-review/pack/docs/IMPLEMENTATION_GUIDE.md) | Templates, retries, loop keys, real-model demo |
| API contract | [API_CONTRACT.md](source-review/pack/docs/API_CONTRACT.md) | Fixed routes, errors, cancellation, engine contract |
| Data model | [DATA_MODEL.md](source-review/pack/docs/DATA_MODEL.md) | Snapshots, steps, approvals, recovery |
| Evaluation guide | [EVALUATION_GUIDE.md](source-review/pack/docs/EVALUATION_GUIDE.md) | Smoke, crash, failures, injection |
| Node catalog | [node_catalog.json](source-review/pack/data/node_catalog.json) | Seven nodes, three registered triggers |
| Seed workflows | [seed_workflows.json](source-review/pack/data/seed_workflows.json) | Four published workflows, exact graph shape |
| Sample payloads | [sample_payloads.jsonl](source-review/pack/data/sample_payloads.jsonl) | Eight payloads, two injection cases |
| Verification scripts | [validate_pack.py](source-review/pack/scripts/validate_pack.py), [smoke_test.py](source-review/pack/scripts/smoke_test.py), [duplication_check.py](source-review/pack/scripts/duplication_check.py) | Assertions and verification gaps |

Also inspected [README](source-review/pack/README.md), [.gitignore](source-review/pack/.gitignore), [mock_world.py](source-review/pack/scripts/mock_world.py), [mock_provider.py](source-review/pack/scripts/mock_provider.py), and [nl_eval.jsonl](source-review/pack/data/nl_eval.jsonl). Compiler cases are inventory only: 12 workflow cases and 3 refusal traps; implementing/evaluating a compiler is out of scope.

## PDF requirements and scope

PDF pages 1–3 require editable drafts and frozen runnable published definitions; manual and secret-protected webhook triggers; durable asynchronous queue/worker execution; persisted steps and run snapshots; deterministic nodes plus schema-validated AI; human approve/reject; engine-enforced sensitive-action gates and step cap; idempotent side effects; timeouts on every external call; complete traces including AI usage; provider abstraction; and a simple console. Tests must cover publish validation, template resolution, schema enforcement, idempotency, and guardrails.

PDF page 4 requires the functional app, README/design/API/recovery documentation, seeded demo, verification report with smoke output and a live kill/resume duplication check, explainer video, and public repository link. Publication remains exclusively the user's manual task under AGENTS.md.

The pack agrees with those core requirements. It permits the chosen Java, Spring Boot, JPA, and MySQL stack. A database queue is explicitly acceptable. Separate API/worker processes and durable scheduled delays remain proposed implementation choices for crash recovery, not additional product features. Versions and configuration are still to be selected in Phase 2.

Excluded optional capabilities: natural-language compiler/editing and its eval, cron scheduling, run wall-clock/token caps, numbered immutable version history, failed-step replay, streaming UI, AI failure triage, multiple concurrent workers, agents, parallel branches/sub-workflows, cost accounting, real connectors, email approvals, and multi-tenancy. Preserve optional limit fields in seed definitions without claiming enforcement. Per-call timeouts and token **recording** remain required.

## Exact API contract facts

Source: API_CONTRACT, Fixed Contract, Authentication, Core API Flows, Error Responses. These are required/planned contracts; no routes are implemented.

| Method/path | Required shape or behavior |
| --- | --- |
| `GET /workflows` | List entries include `id`, `status`; smoke accepts an array or `{ "workflows": [...] }`. All four seeds must be published. |
| `POST /workflows` | Accept canonical definition below; create draft. Invalid definitions may fail here or at publish. |
| `POST /workflows/{workflowId}/publish` | Publish valid definition; invalid type, missing required parameter, invalid edge, or invalid entry rejected with a clear 4xx error. Loops are legal. |
| `POST /workflows/{workflowId}/trigger` | Body `{ "input": {...} }`; return `run_id` or `id`; enqueue promptly. |
| `POST /hooks/{workflowId}` | Raw JSON becomes `trigger.body`; `X-Relay-Secret` required; wrong/missing secret 401/403. No platform token required. Return run identifier promptly. |
| `GET /runs/{runId}` | At least `status`, `steps[]`, each with `node_id`, `status`; full trace also contains resolved inputs, outputs, attempts, timing, AI token usage. |
| `GET /approvals?status=pending` | Entries include `id`, `run_id`; smoke accepts array or `{ "approvals": [...] }`. |
| `POST /approvals/{approvalId}/approve` | Record decision, decider, time; resume paused run. |

Run statuses are exactly `queued`, `running`, `waiting_approval`, `succeeded`, `failed`, `cancelled`. Definition fields are `id`, `name`, `trigger`, `entry`, `limits`, `nodes`; seeds also carry `description`. Trigger config sits directly in `trigger` (e.g. `type`, `secret`), not in a nested `config` object. Each node has `id`, `type`, `params` and `next`, or `on_true`/`on_false` for a condition. A null edge ends the path. Seed `limits` include `max_steps`, `timeout_seconds`, `max_ai_tokens`; only the step cap is required.

Additional flows to design/document: workflow detail/update, run listing, approval rejection, cancellation. Workflow deletion is not explicitly required by the detailed Must Have list despite shorthand “CRUD”; do not invent it. Suggested rejection route is `POST /approvals/{id}/reject`, ending `cancelled` and recording who/when. Suggested cancellation route is `POST /runs/{runId}/cancel`: queued work never starts; running work may finish its current step but starts no next node; waiting approval closes and disappears from pending results; terminal run cancellation returns 409. Route naming outside the fixed contract can be designed, but these described behaviors must be addressed.

A documented single bearer demo token suffices for management APIs and approvals, with a constant decider identity allowed. Role separation is optional. The illustrative error envelope is `{ "error": { "message": "...", "code": "invalid_node_type" } }`; every rejection needs a distinguishable clear body. Suggested statuses are 401 for platform auth, 404 unknown resource, 400/422 invalid definition, 400/409 unpublished trigger, 409 already-decided approval, 409 terminal cancellation. Exact choices remain to be documented; webhook 401/403 is fixed. Trigger success examples allow 202, 200, or 201.

Repeated webhook requests create distinct runs; per-step idempotency does not deduplicate incoming triggers. Exact workflow update method, pagination, duplicate IDs, validation limits, malformed-input handling, error code vocabulary, approval races, and republish mechanics remain Phase 1 design decisions. No full OpenAPI/JSON Schema API document is supplied.

## Node and template contracts

Source: node_catalog.json. Parameter names and enums below are exact. Required parameters are listed first; optional ones are marked.

| Node | Parameters | Output / behavior |
| --- | --- | --- |
| `http_request` | `method`: GET/POST/PUT/DELETE; `url`; optional `headers` object, `body` object | `{status: number, body: object|string}`. Non-GET calls need stable `Idempotency-Key`. URL, headers, body templatable. |
| `condition` | String `left`, `op`, `right`; op equals/not_equals/greater_than/less_than/contains | `{result: boolean}`; use on_true/on_false. Numeric comparisons require both sides parseable as numbers, otherwise fail clearly. left/right templatable. |
| `delay` | Numeric `seconds` | `{}`; survive restart during delay. |
| `notify` | `channel`: email/chat; `to`, `message`; optional `subject` | `{delivered: boolean, notification_id: string}`. Keyed mock-world side effect. to/message/subject templatable. |
| `ai` | `prompt` string, `output_schema` object | Output must satisfy schema. Prompt templatable. One repair attempt after invalid output, with validation error included; a second invalid result fails. Track usage. |
| `approval` | Templatable `message` | `{decision: string, decided_by: string}`; persist pending, pause, human decides. |
| `order_action` | `action`: refund/replacement; templatable `order_id`; optional numeric `amount_usd` | `{status: string, reference_id: string}`; requires approved approval earlier in same run and stable key. Omitted refund amount means full refund. |

Only `order_action` has `requires_approval: true`. Catalog approval description explicitly makes an approved record satisfy gates for the rest of that run; action-specific approval scope would be an extension. The catalog marks HTTP as side-effect capable but its description exempts GET from mutating-call key requirements.

Triggers: webhook with required string secret, manual with no config, schedule with required cron string. Schedule appears in the registry but is explicitly optional in the problem statement; membership alone does not bring it into active scope.

Implementation-guide templates support path interpolation such as `{{trigger.body.x.y}}` and `{{nodes.classify.output.category}}`. No expression evaluation, arithmetic, or filters. Unresolvable paths fail clearly. The whole `trigger.body` path appears in the catalog. Structured-value stringification, null handling, and repeated-node latest-output lookup need explicit design decisions consistent with the fixtures.

## Seeds and payloads

Load all four seeds as published and preserve their IDs and definition shapes.

| Workflow | Entry / path | max_steps | Payloads |
| --- | --- | --- | --- |
| `wf_support_triage` | classify → route; refund_request → refund_gate → issue_refund → notify_customer; otherwise notify_support | 50 | pay_001 complaint; pay_002 refund; pay_003 question; pay_inject_001 gate bypass attempt; pay_inject_002 exfiltration attempt |
| `wf_expense_approval` | is_large tests amount > 100; true → finance_gate → approved_notice; false → auto_ok | 20 | pay_101 = 250; pay_102 = 40; exactly 100 should use false branch |
| `wf_slow_fulfillment` | confirm → pack_delay (20 seconds) → create_shipment → shipped_notice | 20 | pay_201: ord_2003, lena@example.com |
| `wf_runaway` | check_order → is_shipped; false → hold (1 second) → check_order; true → done | 12 | Manual input `{}`; polls ord_2001 |

The AI schema requires category in refund_request/complaint/question, priority in low/medium/high, and summary string with maxLength 300; additionalProperties is false. Refund amount and action are not AI-controlled. notify_support is fixed to #support; customer notification uses the configured trigger customer_email path. The source sample rows include `id`, `workflow`, `note`, `expected`, `body`; send only `body` as trigger data (inside `input` for manual calls), never the annotated expectations.

## Storage and durability facts

Source: DATA_MODEL and implementation FAQ. Persist workflows, run definition snapshots, runs, ordered step attempt-groups, approvals, and durable queue work. Steps need execution sequence because node IDs repeat in loops. Store resolved inputs, output, final attempt number, idempotency key, timestamps/duration, and AI usage. Additional per-attempt detail is needed to show failed and repaired AI attempts. Snapshot at trigger time; never execute the mutable live workflow during resume.

Persist completion before advancing. A worker crash after an external effect but before persistence must retry the same logical step with the same key. The guide refines the shorthand `{run_id}:{node_id}` to include step sequence for distinct loop iterations; never include attempt number. Persist resolved action inputs/key before sending so a resumed retry remains the same action.

Approval evidence must come from stored approved human decisions earlier in the same run. A model cannot create that evidence. Count every logical node execution, including loop iterations; the guide says runaway stops at step 12. Precise retry counting and cap boundary will be documented/tested in task 1.7. Transient failures need exponential backoff and bounded retries; schema-invalid output is not a transient failure after its one repair attempt.

A single worker is sufficient. Persisted recovery ownership/leases may support safe restart without committing to the optional multiple-worker feature. Keep the engine's guarantee bounded by receiver idempotency: the mock world's cache and ledger are in memory and disappear on restart/reset. Keep that process alive during the worker kill drill.

## Mock integration facts and verifier limitations

Mock world defaults to port 9210. Email sends POST /email/send with to/message and optional subject. Chat sends POST /chat/message with **channel**, message: the engine must map notify.params.to to channel. Refund/replacement use POST /orders/{id}/refund or /replacement; shipments use POST /shipments with order_id. Notifications return 200, shipments and replacements 201, refunds 200. Unknown orders fail 404; refund above total or non-positive amount fails 400; another refund without key replay fails 409. GET /orders/{id} returns the order. Built-in order totals are 89.00, 45.50, and 210.00 for ord_2001/2/3.

An Idempotency-Key replay returns the stored response and x-mockworld-replayed: true; successful responses are cached by key globally (not scoped by endpoint/body). Distinct actions must never share a key. The source uses separate lock regions for cache lookup, execution, and cache storage, so overlapping same-key requests are not proven atomic. Failure injection happens before the ledger wrapper; not every failed HTTP request is recorded. Shipment creation does not change the order status. These are source-inspection findings, not load-tested guarantees.

Mock provider defaults to port 9001, name alpha, models alpha-small/alpha-large. POST /v1/chat/completions accepts a nonempty bearer token (or configured --api-key), emits OpenAI-style choices and approximate word-count usage; modes include down, rate_limited, latency and random errors. Its build_reply emits deterministic **prose**, ignores output schemas, and cannot supply successful triage JSON. Use a separate canned-JSON adapter for successful engine tests; the supplied HTTP mock is useful for transport, timeout, usage and malformed-output tests. Real free-tier or local model is required by the pack for the AI demo.

| Tool | What it actually establishes | Gaps to cover separately |
| --- | --- | --- |
| validate_pack.py | Parses all data, counts/types, required params/edges, known references, seeds/payload IDs, optional eval structure | Not a full typed publish validator; approval presence anywhere is weaker than runtime gating; numeric optional limit fields checked in fixtures do not make their enforcement mandatory |
| smoke_test.py | Seed presence, basic notify lifecycle, 3 invalid definitions, wrong webhook secret, small/large expenses, approval resume, runaway termination, observed status vocabulary | AI, crash recovery, rejection, cancellation, snapshot integrity and missing-token cases absent. Invalid-create branch accepts any non-2xx (even transport/server failure). Several requirements are WARN-only; trace checks do not validate all required fields. |
| duplication_check.py | Groups successful non-replayed ledger entries by action and canonical payload, fails repeated groups; warns on missing keys | Empty/incomplete ledger passes! Does not require the expected 3 effects. Can flag intentionally identical effects from different runs/iterations; reset/scope the drill. |

Required evidence therefore includes smoke with warnings explained, actual successful run termination, and the three distinct slow-fulfillment effects (two different email payloads plus one shipment), all keyed and each executed once after the live worker kill/restart. Execute the normal drill during the delay; an additional post-effect/pre-persist crash test is useful regression coverage. The checker alone cannot prove durability.

Manual failure drill must cover both injection payloads, world outage/restoration, flaky world, step cap, and schema repair or deliberate schema failure, including attempts in the trace. Additional source checks cover gate bypass by construction, snapshot preservation after edit/republish, repeated webhooks, secret redaction, and restart with queued work. Preserve mock-world state during the worker drill and reset between scenarios that refund the same order.

## Discrepancies, clarifications, and roadmap corrections

No direct PDF-versus-pack conflict was found in required core behavior. The following source ambiguities or pre-review roadmap drift must not be silently implemented:

1. **Injection expectation versus graph (open):** IMPLEMENTATION_GUIDE Scenario 1 says pay_inject_001 waits whatever the classifier says; the actual seed routes only category=refund_request to approval. A valid complaint/question goes to notification. The runtime safety invariant (no unapproved order action) is clear, but unconditional pausing is not expressible by this unchanged graph. Clarify before implementing affected injection acceptance behavior; do not modify the seed or force a branch in the engine. Task 1.2 can map the invariant and mark this acceptance expectation unresolved.
2. **Mock provider mismatch (resolved by inspection):** its generic prose is not a classifier. Use a schema-valid test fake plus a real model for the demo, and retain the supplied mock unchanged for HTTP failure cases. No inference that mock-provider success equals AI demo success.
3. **Scope drift (corrected in roadmap):** full numbered version history and multi-worker execution were active roadmap items but are optional. Keep frozen published definitions, run snapshots, and single-worker crash recovery. UI editing/graph building is not required by the read-and-operate console; replace those roadmap slots with required console visibility. Approval scope is run-wide according to the catalog. Add explicit cancellation implementation and tests as described by API_CONTRACT.
4. **Script tolerance (resolved):** runaway cancellation is accepted by smoke, but normative contract requires failed for the cap. Likewise smoke accepts runId, but fixed contract names run_id or id. Use the written contract's stricter behavior. The problem statement marks workflow detail as smoke-driven, but smoke does not call it; it is still required by workflow read behavior.
5. **Design details (pending Phase 1):** choose documented update/publish semantics, exact statuses/envelope codes where flexible, parameter bounds, schema dialect, retry defaults, unknown optional-field handling, and mock-world base-URL treatment. The slow/runaway seeds embed localhost:9210; do not silently rewrite stored canonical definitions when selecting a container networking strategy.

## Verification and reproduction

From the project root, run:

```sh
python3 docs/source-review/pack/scripts/validate_pack.py
python3 scripts/check_capstone_review.py
```

Expected: Pack OK with 7 nodes, 3 triggers, 4 workflows, 8 payloads, 15 optional NL cases (3 traps), followed by passing source-integrity and document checks. The second command also probes the empty-ledger checker behavior offline and confirms the mock provider's output is not JSON. Neither runs the application.

Actual results on 2026-09-25 are recorded in PROJECT_PLAN.md and [verification.txt](source-review/verification.txt). PDF rendering command: `pdftoppm -scale-to 1400 -png /Users/user/Downloads/G9I1BMX8E2C99J1M.pdf tmp/pdfs/brief`; exit 0 and all four pages visually inspected. pypdf extraction captured four pages and the repository URI, with SHA-256 provenance. Public GitHub metadata retrieval first failed sandbox DNS; the approved curl retry downloaded the pinned archive successfully.

Application smoke test, live duplication drill, runtime failures, and AI demo were **not run**: no application exists yet. Their later execution belongs to Phases 4–7. This does not block completion of this source-review task. The open injection clarification blocks affected acceptance behavior only, not unrelated requirements mapping.

D01 resolution (user,2026-09-28): keep the supplied graph. Only refund_request takes the approval branch; complaint/question follows notification. All sensitive actions still require earlier approved human evidence. The unconditional-pause expectation is superseded by this explicit user clarification; no seed rewrite or forced engine branch.
