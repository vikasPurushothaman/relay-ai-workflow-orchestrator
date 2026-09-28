# Relay requirements mapping

Task 1.2, 2026-09-25. **Planning only: no application behavior is implemented or runtime-verified.** The matrix maps requirements to the existing [roadmap](../PROJECT_PLAN.md); task numbers identify future work, not completed tests. Task 1.3 will consolidate the MVP; tasks 1.4–1.9 will settle design details.

## Sources and interpretation

- **PDF:** [four-page extracted brief](source-review/brief.txt), with [PDF provenance](source-review/brief-provenance.json); original `/Users/user/Downloads/G9I1BMX8E2C99J1M.pdf`. Pages 1–2 define features, pages 2–3 technical requirements/assessment, page 4 deliverables.
- **PS:** [problem statement](source-review/pack/RELAY_PROBLEM_STATEMENT.md), Must Have 1–8, Technical Requirements, Deliverables and optional sections.
- **API:** [API contract](source-review/pack/docs/API_CONTRACT.md), Fixed Contract, Authentication, Core API Flows, Engine Behavior Contract.
- **CAT:** [node catalog](source-review/pack/data/node_catalog.json); **SEED:** [seed workflows](source-review/pack/data/seed_workflows.json); **PAY:** [sample payloads](source-review/pack/data/sample_payloads.jsonl).
- **DM:** [data model](source-review/pack/docs/DATA_MODEL.md), Entities and Correctness Requirements.
- **IG:** [implementation guide](source-review/pack/docs/IMPLEMENTATION_GUIDE.md), build steps, demo scenarios and FAQ.
- **EG:** [evaluation guide](source-review/pack/docs/EVALUATION_GUIDE.md), required drills and Additional Checks.
- **TOOLS:** [pack validator](source-review/pack/scripts/validate_pack.py), [smoke test](source-review/pack/scripts/smoke_test.py), [duplication checker](source-review/pack/scripts/duplication_check.py), [mock world](source-review/pack/scripts/mock_world.py), [mock provider](source-review/pack/scripts/mock_provider.py).
- **Review:** [task 1.1 findings](CAPSTONE_PACK_REVIEW.md), including source ambiguities and verifier limitations. Pack pinned to `fe30f4adc2e30ae3b6175ab363a20019f8944da1`; [hash manifest](source-review/pack-provenance.json).

The PDF and pack supply product requirements; [AGENTS.md](../AGENTS.md) governs work. Java, Spring Boot, JPA and MySQL are fixed by the user. MySQL durable jobs, separate API/worker processes and scheduled delay timestamps are proposed technical choices supporting required recovery, to be explained in task 1.4. They do not add product scope. No dependency versions are selected here.

Architecture update (task 1.4): [ARCHITECTURE.md](ARCHITECTURE.md) now selects the process split, MySQL queue, migration ownership, console tooling and host/Compose topology. Earlier proposed wording records the prior planning stage; the architecture is the current decision reference. State/recovery semantics are now documented by tasks 1.5–1.8; task 1.9 [console design](CONSOLE.md) completes the Phase 1 design baseline with D01 open. Versions and implementation verification remain pending.

## Required-feature matrix

Every R row has implementation/design owners and verification owners. V cases below specify inputs and observable outcomes. All application verification is **not run**.

| ID | Required behavior and source | Implementation tasks | Verification tasks / cases |
| --- | --- | --- | --- |
| R01 | Preserve/load catalog, four published seeds, payload IDs and definition shape; payload annotations are not run input. PDF p3; PS Must Have 1; DM Source Data. | 2.8, 3.5, 3.9 | 3.10, 7.1, 7.2, 7.8; V01 |
| R02 | Create/read/update/list workflows; editable drafts, frozen runnable publication and independent run snapshots. PDF p1/p3; PS 2; API Flows 1; DM Snapshot. | 1.5, 3.6, 3.8 | 3.10, 7.2; V02, V03 |
| R03 | Validate catalog types/required params, entry and edges; legal loops, clear distinct errors. PDF p3; PS 2; API Fixed/Flows 1. | 1.7, 3.2, 3.5, 3.7 | 3.10, 7.1; V02 |
| R04 | REST management and approvals use documented authentication; webhook needs only its own secret. PDF p2; API Authentication/Errors. | 3.2, 3.3, 3.4, 4.1 | 3.10, 5.10, 7.1, 7.7; V04 |
| R05 | Manual and webhook input becomes trigger.body; persist/enqueue promptly, worker executes; repeated webhooks create distinct runs. PDF p1–2; PS 3/6; API Flows 2; EG Additional Checks. | 4.1, 4.2 | 4.14, 7.1, 7.8; V05 |
| R06 | Persist workflows, snapshots, runs, ordered logical steps/attempts, approvals and durable jobs; resume from persisted state, skip completed work. PDF p2; PS 6; DM Entities/Correctness. | 1.4, 1.5, 1.6, 1.8, 3.1, 3.2, 4.2, 4.3, 4.4, 4.5 | 3.10, 4.14, 7.3, 7.8; V06 |
| R07 | Template path interpolation from trigger/prior node outputs; no arbitrary expression execution; missing paths fail clearly. PDF p3; PS 4; IG FAQ. | 1.7, 4.6 | 7.2, 7.7; V07 |
| R08 | Execute http_request, condition, delay, notify and order_action with exact catalog contracts and mock-world adapters. PDF p2; PS 4/7; CAT. | 4.7, 4.8, 4.9, 4.10 | 4.14, 7.2, 7.5; V08, V09 |
| R09 | Persist stable keys/resolved action inputs before side effects; same logical step reuses key after timeout/crash; loop iterations have distinct keys. PDF p2; PS 6; API Engine; DM Correctness; IG FAQ. | 1.8, 4.5, 4.11 | 4.14, 7.3, 7.4; V09, V10 |
| R10 | Every external call times out; transient failures use bounded exponential backoff; exhaustion fails with recorded error. PDF p2; PS 7; API Engine. | 1.6, 1.8, 4.9, 4.10, 4.12, 5.5, 5.9 | 7.5; V11 |
| R11 | Approval pauses durably without occupying worker; approve resumes, reject cancels; persist decision/decider/time and resumed work atomically. PDF p2; PS 7; API Flows 4; CAT approval. | 1.6, 5.1, 5.2, 5.3 | 5.10, 7.2, 7.7; V12 |
| R12 | Engine checks approved human evidence earlier in same run before every requires_approval action; model output cannot grant approval. PDF p2–3; PS 7; API Engine; CAT. | 1.7, 5.4, 5.6 | 5.10, 7.6; V13 |
| R13 | AI provider adapter; schema-validate output before branching; exactly one validation repair with error supplied, then clean failure; record usage/attempts. PDF p2–3; PS 5; CAT ai; IG FAQ. | 1.7, 5.5, 5.6, 5.7, 5.8 | 5.10, 7.5, 7.6; V14 |
| R14 | Treat payload/model output as untrusted; injection cannot bypass gates or rewrite configured notification destinations; review echoed instructions. PDF p2–3; PS 5; EG Failure Drill. D01 resolved2026-09-28: preserve conditional graph and enforce gates. | 5.4, 5.6 | 5.10, 7.6; V13, V15 |
| R15 | Persist/enforce max_steps including repeated nodes across recovery; stop runaway as failed with cap reason; exact six run statuses. PDF p2–3; PS 7; API Engine; IG Scenario 4. | 1.6, 1.7, 4.13 | 4.14, 7.1, 7.2, 7.6; V16 |
| R16 | Cancel queued without work, running between steps, waiting approval with closure; terminal cancellation conflicts, record reason. API Flows 5. | 1.6, 5.11 | 5.11, 7.7; V17 |
| R17 | Trace resolved inputs, outputs, attempts, timestamps/durations, errors and AI token usage; secrets absent from traces/console/errors. PDF p2–3; PS 8; API Flows 3; EG Additional Checks. | 4.4, 5.8, 6.1, 6.2 | 7.6, 7.7; V18 |
| R18 | Read-and-operate console: workflow list/detail, run history/detail/trace, pending approvals with approve/reject. Create/edit/publish/manual trigger via API. PDF p3–4; PS Scope/8. | 1.9, 3.4, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10, 6.11 | 6.12, 7.7; V19 |
| R19 | Real free-tier/local model for AI demo; fake JSON adapter for engine tests; supplied prose mock for HTTP failure tests. IG FAQ; PS Demo. | 5.5, 5.9, 8.4 | 5.10, 7.2, 8.6; V14, V20 |
| R20 | Automated critical-logic tests, supplied smoke, live kill/resume duplication evidence, failure drill and reproducible report. PDF p3–4; PS 8; EG 1–3/Report. | 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9 | 7.9, 8.6, 8.8; V21 |
| R21 | Functional engine/console, local startup tooling and README/API/design/state/recovery docs with configuration, demo seeds and limitations. PDF p4; PS Deliverables; IG README FAQ. | 1.4, 1.5, 1.6, 1.7, 1.8, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 8.1, 8.2, 8.3, 8.4, 8.5 | 2.9, 7.8, 8.6, 8.7, 8.8, 9.3; V22 |
| R22 | Explainer video covers trigger, AI branch, approval, crash/resume, injection, cap; submission includes repository link and report. PDF p4. Publication exclusively manual user work. | 9.1, 9.2, 9.3, 9.4, 9.5 | 8.6, 9.5; V23 |

## Fixed API and JSON contracts

These are planned contract obligations, **not implemented routes**. Full route documentation and verified examples belong in [API_DOCUMENTATION.md](../API_DOCUMENTATION.md) during implementation. The eight fixed routes are:

| Method/path | Preserved minimum | Implementation tasks | Verification case |
| --- | --- | --- | --- |
| `GET /workflows` | Entries contain id, status; smoke accepts array or workflows wrapper. | 3.6 | V01, V04 |
| `POST /workflows` | Canonical definition; creates draft. | 3.6 | V02, V04 |
| `POST /workflows/{workflowId}/publish` | Validates and publishes; invalid definition gets clear 4xx. | 3.7, 3.8 | V02, V03 |
| `POST /workflows/{workflowId}/trigger` | Body {"input": {...}}; returns run_id or id promptly. | 4.1, 4.2 | V05 |
| `POST /hooks/{workflowId}` | Raw JSON becomes trigger.body; X-Relay-Secret only; absent/wrong secret 401/403; returns run ID. | 4.1, 4.2 | V04, V05 |
| `GET /runs/{runId}` | status and steps[] with node_id/status; full required trace. | 6.1, 6.2 | V18 |
| `GET /approvals?status=pending` | Entries id/run_id; smoke accepts array or approvals wrapper. | 5.2 | V12, V17 |
| `POST /approvals/{approvalId}/approve` | Persist human decision and resume. | 5.2, 5.3 | V12, V13 |

Run statuses: `queued`, `running`, `waiting_approval`, `succeeded`, `failed`, `cancelled`. Workflow states: `draft`, `published`.

Definition fields: `id`, `name`, `trigger`, `entry`, `limits`, `nodes`; preserve seed `description`. Trigger settings sit directly in `trigger`, e.g. `{"type":"webhook","secret":"<workflow-secret>"}`, not `trigger.config`. Nodes have `id`, `type`, `params` and `next`, or condition `on_true`/`on_false`; null ends a path. Preserve `limits.max_steps`, `limits.timeout_seconds`, `limits.max_ai_tokens`; only max_steps is enforced in required scope. Exact examples remain in SEED, unchanged.

Additional required flows with flexible route design: workflow detail/update (3.6), run listing (6.1), rejection (5.2–5.3), cancellation (5.11). API suggests `POST /approvals/{id}/reject` and `POST /runs/{runId}/cancel`; these are not additions to the eight fixed routes. Workflow deletion is not explicitly required by the detailed create/read/update/list list.

Authentication: a documented `Authorization: Bearer <demo-token>` is sufficient for management and approvals; a constant decider is permitted. Webhooks need no platform token. Responses must distinguish errors with clear bodies. Preserve the supplied illustrative envelope `{"error":{"message":"Node 'n1' has unknown type 'teleport'","code":"invalid_node_type"}}` as the design reference; it is not a complete mandated error vocabulary. Suggested status choices: platform auth 401, missing resource 404, invalid definition 400/422, unpublished trigger 400/409, already-decided approval 409. Fixed webhook rejection is 401/403; terminal cancellation is 409. Trigger success allows 202/200/201. Final flexible choices and schemas remain D02; no new codes are invented here.

## Exact node contract mapping

CAT is authoritative for types, required flags, enums, templatable flags and outputs. The following table retains parameter types; `?` means optional. JSON output notation gives types, not literal return values.

| Node | Parameters | Output | Implementation tasks / cases |
| --- | --- | --- | --- |
| `http_request` | method:string enum GET/POST/PUT/DELETE; url:string; headers?:object; body?:object | {"status":"number","body":"object|string"} | 4.9, 4.11; V08–V11 |
| `condition` | left:string; op:string enum equals/not_equals/greater_than/less_than/contains; right:string | {"result":"boolean"} | 4.7; V07, V08 |
| `delay` | seconds:number | {} | 4.8; V06, V08 |
| `notify` | channel:string enum email/chat; to:string; subject?:string; message:string | {"delivered":"boolean","notification_id":"string"} | 4.9, 4.11; V08–V11 |
| `ai` | prompt:string; output_schema:object | defined by output_schema | 5.5, 5.6, 5.7, 5.8; V14 |
| `approval` | message:string | {"decision":"string","decided_by":"string"} | 5.1, 5.2, 5.3; V12 |
| `order_action` | action:string enum refund/replacement; order_id:string; amount_usd?:number | {"status":"string","reference_id":"string"} | 4.10, 5.4; V09, V13 |

Templatable params: http_request url/headers/body; condition left/right; notify to/subject/message; ai prompt; approval message; order_action order_id. Path-only examples: `{{trigger.body}}`, `{{trigger.body.x.y}}`, `{{nodes.classify.output.category}}`. Missing paths fail; no expressions, filters or arithmetic. Numeric comparisons require parseable numbers on both sides or a clear failure. Null/stringification/loop lookup rules are selected in [workflow semantics](WORKFLOW_SEMANTICS.md), task 1.7.

Only order_action requires approval; a stored approval earlier in the same run satisfies gates for the rest of that run. Do not impose action-specific approval scope. Notify, order_action and non-GET http_request require stable `Idempotency-Key`; GET is exempt. Key includes logical step sequence for loops and never attempt number. Engine ownership/recovery details remain task 1.8.

Mock-world mappings: notify email → POST /email/send (to, message, optional subject); chat → POST /chat/message (map to → channel, message). order_action → POST /orders/{order_id}/refund or /replacement; omitted amount_usd means full refund. HTTP seeds use GET /orders/{id} and POST /shipments with order_id. Each external call must time out. Do not silently rewrite seed localhost URLs; settle process/container networking in D04.

## Verification cases and fixtures

Prerequisites for V01–V19: implemented API/worker, migrated MySQL, documented demo token and seeded definitions; mock world running for side effects. Use a deterministic schema-valid AI adapter when controlling branches, never send PAY note/expected metadata as input. Each case is a planned automated test or drill owned by the matrix, not a current test result. Unit cases may isolate dependencies. Exact commands and test files will be recorded in each implementation task.

| Case | Inputs/actions, including boundaries/failures | Expected observable result |
| --- | --- | --- |
| V01 | Load SEED/catalog/PAY; list workflows; restart and seed again. | Four original IDs published, exact graph/params retained, no duplicate seeds; eight payload bodies usable separately from annotations. |
| V02 | Create/read/update/list draft; publish valid graph and legal loop. Try unknown type, missing required param, wrong types/enums, dangling next/branch, invalid entry. Exercise malformed JSON, duplicate IDs, empty nodes and repeated publish after D02/D03 choices. | Correct lifecycle; required invalid cases produce distinct clear 4xx; no invalid publication. Flexible boundaries get documented expectations before implementation. |
| V03 | Trigger published run; pause; edit/republish; resume and inspect historical trace. | Original frozen run snapshot drives remaining execution and survives restart; draft edits do not mutate it. |
| V04 | Missing/wrong management token on management/approval APIs; missing/wrong/correct webhook secret without platform token; unknown resource IDs. | Protected calls denied with distinct error bodies; webhook rejects 401/403 or accepts correct secret; no work/approval from unauthorized requests; unknown resources clearly reported. |
| V05 | Trigger manually with input object and webhook with raw body; repeat identical webhook twice; stop worker before trigger; try unpublished workflow. | Prompt acceptance after persisted run/snapshot/job, zero request-handler execution; trigger.body matches input; duplicates produce two runs; queued work survives restart; unpublished trigger rejects clearly. |
| V06 | Restart with queued/in-flight runs; kill during durable delay; replay delivered job; recover expired ownership; attempt stale completion. | Completed nodes skipped; delay state survives; only current owner commits; no lost or duplicate next work; terminal outcome and ordered trace preserved. Single-worker recovery is scope. |
| V07 | Resolve nested trigger and previous-output paths, whole trigger.body; invalid/missing path and expression-like input; repeat node in loop. | Correct supported substitution; clear failure for unresolved paths, no code execution. Structured/null/latest-loop semantics tested once D03 resolved. |
| V08 | All condition operators; expense 40, 250, exactly 100 and nonnumeric operand; delay and restart; each HTTP method; email/chat notifications. | Correct branches (100 uses false); numeric parse failure is clear; catalog outputs persisted; chat maps to channel; delay resumes durably; unsupported params rejected. |
| V09 | Approved refund/replacement, omitted refund amount, above-total/nonpositive amount, missing order; mutating HTTP and notify; repeated attempt after timeout. | Correct keyed mock endpoints/outputs, full refund when omitted, external 400/404/409 failures recorded without fabricated success; retry reuses key/input. External statuses are not automatically management API statuses. |
| V10 | Reset world; trigger slow fulfillment; kill worker during 20-second delay then restart. Separately inject post-effect/pre-persist crash. Keep world alive. Test two legitimate loop iterations. | Run succeeds; exactly three intended effects (confirm email, shipment, shipped email), each keyed and executed once; replay absorbed, loop iterations have different keys; duplication checker passes with no missing-key warnings. |
| V11 | World down then restored, fail_rate 0.3, hung response, permanent error; provider timeout/rate-limit; exhaust bounded attempts. | All calls time out; transient failures show exponential backoff and recovery; exhaustion fails with error/attempt trace; permanent/schema errors are not retried indefinitely. Defaults set in D03. |
| V12 | Reach approval, restart, list pending, approve/resume; separate reject; repeat and conflict decisions, decide missing ID, race two decisions. | One durable pending approval; one human decision with decider/time, single resumed continuation; rejection cancelled; no contradictory decisions or duplicate work; conflicts documented. |
| V13 | Reach order_action without approval, on wrong branch, with pending/rejected/other-run approval or forged model decision; approved earlier same-run control. | No unapproved external action; approved control executes. Engine checks every sensitive execution; model cannot manufacture stored human evidence. |
| V14 | Valid AI JSON; invalid JSON/schema then repair; invalid twice; missing/extra fields, invalid enum, summary lengths 300/301; provider failure. | Only validated data reaches branch; repair includes validation error exactly once; second invalid result fails without transient re-asking; both attempts/usage visible. |
| V15 | pay_inject_001 and pay_inject_002 with controlled valid classifications, then real model in demo. | No approval bypass or attacker-selected destination; inspect summary for echoed instructions and report findings. User clarification supersedes unconditional waiting_approval: only refund_request pauses; complaint/question notifies. Real model demo remains Phase7. |
| V16 | wf_runaway, persisted step count at cap around restart/loop/retry; finite path ending at limit. | Runaway fails with cap reason at 12-node budget, no extra effect beyond cap; retries cannot reset budget. Task 1.7 defines one count per logical visit, retries reuse it and final success at cap is allowed; statuses only from fixed six. |
| V17 | Cancel queued, running, waiting_approval, each terminal state; race cancel with decision/completion. | Queued executes nothing; current running step may finish/fail but no next node; pending approval closes; terminal cancellation 409; durable reason and one consistent terminal outcome. |
| V18 | Fetch success/failure/waiting/AI/loop traces; include secrets in configuration and simulate errors. | Ordered logical steps and attempts, resolved inputs/outputs, timing/errors, AI token usage available; webhook/provider credentials redacted from trace/console/errors. |
| V19 | Console workflow/detail/history/trace/pending views, approve/reject API-triggered run; empty/loading/failure states and keyboard/small-screen use. | Visible states match API, decision feedback and refreshed run status; no graphical editor needed. |
| V20 | Configure real free-tier/local provider server-side; run complaint/refund/injection demonstrations. | Validated AI branches and usage visible with real model; missing credentials recorded as outstanding verification, never counted as pass. |
| V21 | Run supplied smoke, duplication drill and manual failure drill; collect backend tests/frontend typecheck/build/browser results. | Smoke pass with warnings explained, positive ledger/run evidence beyond checker exit code; reproducible report includes failures/limitations and resolved injection expectation before completion. |
| V22 | Start clean local copy/database using README commands, seed, start API/worker/console/mocks, execute demo; inspect docs and configuration. | Reproducible setup and test commands, no committed credentials or required machine-specific settings; API docs match implemented routes; known limitations explicit. |
| V23 | Review local submission package/video against PDF p4; obtain repository URL only after user's manual publication. | Video includes all required scenarios; README, report, demo and user-provided URL included; no agent push, upload, PR or deployment. |

Fixture coverage (SEED/PAY remain canonical):

| Workflow | Payload IDs and expectation | Verification |
| --- | --- | --- |
| wf_support_triage | pay_001 complaint → support; pay_002 refund → human gate → action; pay_003 question → support; pay_inject_001 gate bypass attempt; pay_inject_002 exfiltration attempt. Test adapter controls categories; real-model outcomes recorded separately. | V13, V14, V15, V20 |
| wf_expense_approval | pay_101 (250) waits; pay_102 (40) auto path; additional boundary 100 auto path. | V08, V12 |
| wf_slow_fulfillment | pay_201, ord_2003, confirmation → 20s delay → shipment → shipped email. | V06, V10 |
| wf_runaway | Manual input {}; ord_2001 remains unshipped; max_steps 12. | V16 |

The triage schema requires category refund_request/complaint/question, priority low/medium/high, summary string maxLength 300, additionalProperties false. The seed routes only refund_request to refund_gate. Approval does not allow the model to change fixed action or amount. Notifications follow configured destinations, including configured trigger.customer_email paths; model summary text must not become executable routing instructions.

Smoke omits AI, crash recovery, cancellation, rejection and snapshot proof; some assertions are WARN-only. The duplication checker can pass an empty ledger and groups by action/payload, so scope/reset the drill and independently assert successful termination and the three expected effects. World cache/ledger is in memory; receiver idempotency guarantees require it to remain alive during worker recovery. This is not a claim of unconditional exactly-once delivery to arbitrary external services.

## Open decisions and scope boundary

| Decision | Unresolved issue / owner | Effect on acceptance |
| --- | --- | --- |
| D01 | Review discrepancy 1: IG Scenario 1 and EG expect pay_inject_001 to pause regardless of classification; SEED sends valid complaint/question to notify_support. User clarified2026-09-28: preserve supplied graph and enforce gates. | No seed rewrite or forced engine branch. Same-run human gate invariant is clear and can be implemented independently. Unconditional-pause expectation is superseded by user clarification. |
| D02 | Tasks 1.6–1.7 settle decision/cancellation races, update/republish, duplicate IDs and definition validation. Flexible route naming, response codes/envelopes, client preconditions and pagination remain route tasks. | Document choices and tests before implementation; suggested codes are not claimed fixed or implemented. |
| D03 | Task 1.7 settles JSON Schema dialect, template values/latest-output lookup, parameter bounds, unknown fields and precise step-cap counting. Task 1.8 defines retry defaults/recovery budgets in [RECOVERY.md](RECOVERY.md); adapter limits and runtime verification remain implementation work. | Preserve fixtures; settle each case before its implementation. No arbitrary defaults invented in mapping. |
| D04 | 1.4, 2.1, 2.2, 2.8, 5.9: Java runtime repair, versions, mock URL/networking (seed localhost), real provider/configuration; 9.1 redistribution terms. | Preserve source provenance; mock provider emits prose, requiring canned JSON for success tests and real provider for demo. No license grant found in pack; resolve before manual redistribution. |

Excluded unless explicitly requested: natural-language compiler/editing and nl_eval evaluation, schedule/cron execution (despite registry membership), wall-clock and AI-token run caps, numbered version history, failed-step replay, streaming UI, AI failure triage, multiple concurrent workers, agent nodes, parallel branches, sub-workflows, cost accounting, real connectors, email approvals, role separation and multi-tenancy. Optional limit fields are preserved, not enforced. Per-call timeouts, AI token recording, frozen publications and run snapshots remain required. The console is read-and-operate; API creation/editing/publishing suffices.

Publication is a manual user task. The agent must never push or publish via Git, APIs, UI or another substitute; no remote repository, PR, release or deployment. Requirement R22 maps the PDF deliverable without overriding this rule.

## Document verification

Run from project root:

```sh
python3 docs/source-review/pack/scripts/validate_pack.py
python3 scripts/check_capstone_review.py
python3 scripts/check_requirements.py
```

The requirements checker validates matrix references/coverage, source links, fixed routes/statuses, catalog parameter/output details, fixtures and scope markers; negative mutations prove selected omissions fail. These checks cannot prove semantic completeness or application correctness. Manual source comparison supplements them. Actual document-check results are recorded in [requirements-verification.txt](requirements-verification.txt) and task 1.2 in the roadmap. Application tests and drills are **not run**; no application exists yet.
