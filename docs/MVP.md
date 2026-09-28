# Relay MVP scope

Task 1.3, 2026-09-25. **Scope definition only; the application is not implemented or runtime-verified.** Completing this document does not complete the MVP or Phase 1.

Relay's MVP lets a builder publish a typed workflow, an external caller or builder start a run, and an operator inspect durable execution while a human decides sensitive actions. The minimum complete product includes the required engine, console, guardrails, recovery evidence and documentation. Deterministic-only execution or a smoke-test pass alone is an intermediate milestone.

Sources: [PDF extracted evidence](source-review/brief.txt), [pack Must Have and scope](source-review/pack/RELAY_PROBLEM_STATEMENT.md), [API contract](source-review/pack/docs/API_CONTRACT.md), [catalog](source-review/pack/data/node_catalog.json), [implementation guide](source-review/pack/docs/IMPLEMENTATION_GUIDE.md), and [evaluation guide](source-review/pack/docs/EVALUATION_GUIDE.md). The [requirements mapping](REQUIREMENTS.md) retains exact contracts, source citations, implementation owners and verification cases. R/V references below refer to that document. The [roadmap](../PROJECT_PLAN.md) remains the execution order; [AGENTS.md](../AGENTS.md) governs local work.

Architecture update (task 1.4): [ARCHITECTURE.md](ARCHITECTURE.md) now selects the process split, MySQL queue, migration ownership, console tooling and host/Compose topology. Earlier proposed wording records the prior planning stage; the architecture is the current decision reference. Versions and detailed state/recovery semantics remain pending.

## Required user journeys

| User | Minimum complete journey | Requirements / verification |
| --- | --- | --- |
| Builder | Use authenticated APIs to create, read, update and list draft workflows; publish a valid definition; trigger a run manually. Inspect the resulting run in the console. Invalid types, required params, entry or edges return clear errors; legal loops remain allowed. Published definitions are frozen and every run executes its trigger-time snapshot despite later edits/republishing. | R02, R03, R04, R05; V02, V03, V04, V05 |
| External caller | Send JSON to a workflow webhook with its X-Relay-Secret. Receive a run identifier promptly after durable acceptance. Missing/wrong secrets reject; repeating the webhook creates distinct runs. Execution occurs in the worker. | R04, R05; V04, V05 |
| Operator | View workflow list/detail, run history and step traces; observe queued, running, waiting and terminal outcomes. Cancel through the API: queued work never starts; running work stops between steps; waiting approval closes. Terminal cancellation returns 409. | R16, R17, R18; V17, V18, V19 |
| Approver | Open the pending-approval inbox or API, review the resolved request and approve or reject. A durable decision records who and when; approval resumes once, rejection ends cancelled. Duplicate/conflicting decisions cannot create duplicate continuation work. | R11, R12; V12, V13 |

A documented demo bearer token is sufficient for builder/operator/approver access; these are user responsibilities, not separate roles to implement. Webhooks require only their workflow secret. Approval actions must be authenticated. Workflow creation/editing/publishing and manual triggering use APIs; the console is read-and-operate. A cancel button is not required by this scope.

## Included capabilities

| Capability | MVP boundary and acceptance | Requirements / verification |
| --- | --- | --- |
| Supplied data and contracts | Load the catalog and all four seeds as published, preserve IDs/definition JSON, and retain sample payloads as input fixtures. Preserve the eight fixed routes, six run statuses, node params/outputs and error obligations in REQUIREMENTS. Flexible route/schema decisions must be documented before implementation. | R01; V01 |
| Durable single-worker engine | Persist workflows, snapshots, runs, logical steps/attempts, approvals and jobs in MySQL. Accept asynchronously; survive restart with queued or in-flight work; skip completed nodes. Persist completion and advancement consistently and recover abandoned ownership without allowing stale writes. Delay state survives restart. | R06; V06 |
| Data flow and deterministic nodes | Execute http_request, condition, delay, notify and order_action using catalog contracts and the mock world. Resolve trigger/prior-output template paths; missing paths fail clearly and templates never evaluate code. Use exact branch/loop semantics and persist resolved inputs/outputs. | R07, R08; V07, V08, V09 |
| Side effects and dependency failures | Notify, order_action and mutating HTTP requests use Idempotency-Key. Persist resolved action inputs and key before sending; retries/resume reuse them, distinct loop executions get distinct keys. Every external call has a timeout. Transient errors use bounded exponential backoff; exhaustion fails with a recorded reason. | R09, R10; V10, V11 |
| Human gates and step cap | Approval pauses durably without occupying a worker. Before every order_action the engine checks an approved human decision earlier in the same run; its scope lasts for that run. Model output cannot grant approval. Persist/enforce max_steps across loops/restarts; runaway ends failed with a cap reason. | R11, R12, R15; V12, V13, V16 |
| AI validation and injection handling | Execute ai through a provider adapter. Validate output_schema before downstream use; allow one repair attempt including the validation error, then fail on a second invalid result. Treat payloads and model output as untrusted; neither can bypass human gates or rewrite configured notification destinations. Inspect echoed injection instructions in traces. | R13, R14; V14, V15 |
| AI configurations | Use a canned schema-valid adapter for deterministic engine tests, supplied HTTP mock for failure/timeout/malformed-output tests, and one real free-tier/local provider for AI demonstration. The supplied provider emits prose, so it cannot stand in for successful classifier JSON. Credentials remain server-side. | R19; V20 |
| Run visibility and console | Persist and expose resolved inputs, outputs, ordered steps, retry/repair attempts, timestamps/durations, failures and AI token usage. Redact secrets in traces, errors and console views. Provide workflow list/detail, run history/detail/trace and pending approvals with approve/reject feedback, including empty/loading/failure states. | R17, R18; V18, V19 |
| Verification and reproducible delivery | Automated critical-logic tests, smoke, live worker kill/resume, positive ledger evidence, failure drills and verification report are part of completion. Supply clean local setup, README, API/design/state/recovery documentation, configurations, seeded demo and known limitations. | R20, R21; V21, V22 |
| Submission obligations | Prepare the explainer video and package containing documentation, verification evidence, demo and the repository link supplied after manual user publication. Submission is a separate milestone, not a reason to omit required product work. | R22; V23 |

All seven required node types are included: `http_request`, `condition`, `delay`, `notify`, `ai`, `approval`, `order_action`. Only manual and webhook trigger execution is in scope. Full field-level contracts remain in REQUIREMENTS and the unchanged pinned pack; this scope document does not introduce replacement API shapes.

Java, Spring Boot, Spring Data JPA and MySQL remain fixed. A React console is in the roadmap; TypeScript/Vite, Flyway, Docker Compose, a MySQL jobs table, separate API/worker processes and persisted delay timestamps remain proposed technical choices. Task 1.4 will explain the architecture and Phase 2 will select compatible versions. Single-worker recovery still needs safe ownership and atomic updates; the simplification does not waive decision/cancellation races or duplicate-delivery handling.

## Demonstration and completion gates

Prerequisites for runtime acceptance: migrated MySQL, implemented API and worker, seeded definitions, documented authentication, running mock world, console, and configured provider appropriate to the scenario. These gates are **not run**. Planned inputs/actions and expected results come from REQUIREMENTS V01–V23.

| Gate | Action and required evidence |
| --- | --- |
| Workflow and security | Create → edit → publish → trigger → trace. Reject invalid definitions and unauthorized calls; verify frozen snapshot after edit/republish while paused. Repeated webhook delivery creates two independent runs. Missing resources and conflicting states have documented errors. |
| Deterministic approval | Run wf_expense_approval with pay_101 (250) and pay_102 (40); test boundary 100. Large expense waits; small/boundary takes the false branch. Demonstrate approve/resume and reject/cancel with decider/time, repeat/conflicting decisions and restart while waiting. |
| AI and human control | Run wf_support_triage with normal pay_001, refund pay_002 and question pay_003. Show validated classifier output, branch and trace usage. Exercise malformed output → repair and twice-invalid failure with both attempts visible. Demonstrate both injection payloads pay_inject_001 and pay_inject_002; no unapproved order action or attacker-directed notification. D01 resolved2026-09-28: retain the supplied graph and enforce approval gates. |
| Recovery and effects | Run wf_slow_fulfillment with pay_201, kill the worker during the delay and restart. Separately test post-effect/pre-persist recovery per the roadmap. Run succeeds with exactly the three expected keyed effects: confirmation email, shipment and shipped email. Capture duplication-check output without missing-key warnings; keep mock-world state alive. An empty-ledger pass is insufficient. |
| Failures and limits | Run wf_runaway to failed with its max_steps=12 cap reason. Show outage/restoration, flaky-world retries, timeout and retry exhaustion. Verify direct approval-bypass attempts, wrong-run evidence, step-count persistence, queued restart and cancellation/decision races. |
| Console and reproduction | Inspect workflow/run/trace and approval views for an API-triggered run; verify UI feedback and error states. Run automated backend checks, frontend typecheck/build and meaningful browser checks; perform fresh database/local startup and repeatable seeding from documented commands. |

**Application-ready:** complete roadmap Phases 1–8, satisfy mapped acceptance cases and applicable API documentation, pass required tests/drills and save actual evidence in the verification report. Explain smoke warnings and limitations rather than treating the supplied scripts as exhaustive. A missing real-provider credential or unverified live-model acceptance remains outstanding; neither is silently marked passed. A mock-only development setup is useful but does not complete the real-model demo requirement.

Receiver idempotency bounds the recovery guarantee. The mock world's ledger/cache is in memory: keep it alive during worker-kill drills and reset between independent scenarios as needed. Do not promise exactly-once effects for arbitrary receivers without idempotency support.

**Submission-ready:** application-ready plus Phase 9, including the explainer video showing trigger, AI branch, approval, crash/resume, injection and step cap; the user-provided public repository link; and the final local submission package. The agent must never push or publish, create remote repositories/PRs/releases/deployments, or substitute an API/UI upload. Publication remains a manual user task.

## Excluded scope

The following remain excluded unless the user explicitly requests them, even after the MVP works:

- Natural-language compiler/editing and its nl_eval evaluation; schedule/cron execution.
- Run wall-clock/token caps, numbered immutable version history, failed-step replay, streaming UI and AI failure triage.
- Multiple concurrent workers, agent nodes, parallel branches, sub-workflows and cost accounting.
- Real connectors, email approvals, role separation, organizations/multi-tenancy and a graphical workflow builder.

Preserve optional seed fields limits.timeout_seconds and limits.max_ai_tokens without promising enforcement. Per-call timeouts and AI token recording remain required. Preserve frozen publications/run snapshots without adding version history. Use mock-world notifications/actions; the real AI provider is required for the AI demo and is not excluded as a real connector. Workflow deletion is not required by the detailed create/read/update/list scope.

## Decisions carried forward

| Decision | Owner and boundary |
| --- | --- |
| D01 — injection-pause ambiguity | IG/EG expect pay_inject_001 to wait regardless of classifier output, but the seed routes only refund_request through approval. Resolved by user2026-09-28: retain the seed conditional graph and enforce approval gates; do not force an engine branch. |
| D02 — flexible API/state details | Tasks 1.6–1.7 and route implementation tasks settle update/republish behavior, error/status choices, malformed input, duplicate IDs, pagination and decision/cancellation races before implementation. Fixed contracts remain binding. |
| D03 — execution details | Tasks 1.6–1.8 settle schema dialect, template null/structured values/latest loop output, parameter bounds, unknown fields, retry defaults and exact step-cap counting/boundaries. |
| D04 — environment/integration | Tasks 1.4 and 2.1–2.8 settle Java availability, versions and mock networking while preserving seed localhost definitions; 5.9 selects/configures the real provider; 9.1 addresses pack redistribution terms before manual publication. |

Task 1.3 establishes scope. Tasks 1.4–1.9 now document architecture, persistence, states, semantics, recovery and the [console](CONSOLE.md). D01 stays open; detailed route implementation and runtime verification remain with their existing owners. The Phase 1 design baseline is complete and the next task is development toolchain inspection (2.1). No application code or route behavior changes in these design tasks; [API_DOCUMENTATION.md](../API_DOCUMENTATION.md) continues to report no implemented routes.

## Document verification

From the project root, run:

```sh
python3 scripts/check_mvp.py
python3 scripts/check_requirements.py
python3 scripts/check_capstone_review.py
```

Expected: MVP references/links and source coverage pass, invalid-copy checks reject omissions, and existing requirements/source-integrity checks pass. Manual comparison additionally checks semantic consistency; these document checks cannot establish runtime correctness. Actual outputs are recorded in [mvp-verification.txt](mvp-verification.txt) and the task record in PROJECT_PLAN. Application tests and drills are not run because no application exists yet.
