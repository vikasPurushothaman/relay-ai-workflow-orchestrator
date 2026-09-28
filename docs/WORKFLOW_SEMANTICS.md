# Relay workflow semantics

Task 1.7, 2026-09-25. **Design only; no application behavior verified.** Sources: [PDF evidence](source-review/brief.txt), [API contract](source-review/pack/docs/API_CONTRACT.md), [catalog](source-review/pack/data/node_catalog.json), [seeds](source-review/pack/data/seed_workflows.json), [guide](source-review/pack/docs/IMPLEMENTATION_GUIDE.md), [requirements](REQUIREMENTS.md), [data model](DATA_MODEL.md), [states](STATE_TRANSITIONS.md), [roadmap](../PROJECT_PLAN.md). Rules explicitly chosen below fill flexible contracts; they do not modify the source pack. Java, Spring Boot, JPA and MySQL remain the implementation stack.

## Publication and acceptance

Create stores a draft. Updates replace the full draft definition, preserving immutable workflow ID; they set status draft even if a previous publication exists. Existing publication JSON stays frozen but **new triggers require status published**. In-flight runs continue from their own snapshots. This avoids making edits silently runnable or making status draft misleading. Publish validates the current draft and copies it atomically to publication, sets published_at and status published. Failed create/update leaves storage unchanged; failed publish leaves prior storage unchanged. Repeated publish of an unchanged already-published definition is a no-op preserving published_at. No numbered version history.

Workflow commands serialize on the workflow row and recheck revision. A request reads/validates a draft revision and may publish only that same revision; competing mutation produces a conflict instead of publishing stale data. Full-replacement edits are serialized; a later edit replaces the earlier draft (optimistic client preconditions, if added to a route, must be documented there). Trigger acceptance under the same serialization either snapshots the old published definition before an edit, rejects while draft, or snapshots the newly published definition. It never mixes graph and secret from different publications.

All management actions require demo-token authentication; webhook authentication uses X-Relay-Secret from the accepted publication. Manual trigger may start either a manual or webhook-defined published workflow; webhook entry only accepts webhook-defined workflows. No schedule execution. Manual input must be a JSON object; webhook body may be any valid JSON value, including null/array/scalar, and becomes trigger.body exactly. Missing body/malformed JSON reject; a valid null payload is not missing. Authenticated access, resource existence, publication eligibility and trigger compatibility are checked before creating any run/job. Exact nonfixed route codes/envelopes are implementation tasks; no endpoint is implemented here.

## Validation stages

Create/update perform structural validation: valid JSON object, duplicate JSON keys rejected at every depth, required envelope fields and types, identifier/size limits, nonempty nodes and unique node IDs. Drafts may temporarily have dangling edges, unknown node types or incomplete params so an edit can be repaired. Publish performs every rule below over all nodes, including unreachable ones. Invalid publish uses the established error envelope with a distinct code/reason plus node/field path; exact HTTP 400/422 selection comes with the route. No partial publication and no network call during validation.

| Rule | Selected behavior | Failure distinction |
| --- | --- | --- |
| P01 | Required definition fields: id, name, trigger, entry, limits, nodes; optional description. Reject unknown structural fields; no implicit defaults for missing entry/limits. | Missing/wrong-type/unknown field with path. |
| P02 | Workflow ID is nonempty, case-sensitive, at most 128 Unicode code points; node IDs match `[A-Za-z_][A-Za-z0-9_-]{0,127}` to be unambiguous in paths. IDs unique within their scope; update ID must match stored ID. | Invalid ID / duplicate ID / immutable ID. |
| P03 | name nonblank and at most 200 characters; description optional string at most 4000; nodes nonempty. Definition request at most 1 MiB UTF-8, JSON nesting at most 64; inputs/resolved params share these limits. | Size/depth/type errors before persistence; no truncation. |
| P04 | trigger is manual (only type) or webhook (type and nonempty secret); secret at most 1024 UTF-8 bytes, literal, no template. Registry schedule is explicitly unsupported in this MVP. | Unsupported trigger / missing secret / extra config field. |
| P05 | entry is a literal existing node ID. Node object allows only id, type, params and applicable edge fields. params is an object; unknown catalog type fails. | Invalid entry / invalid node type. |
| P06 | Required params present and non-null; optional params may be omitted but not null unless their catalog type permits it (none currently do). Exact catalog types/enums; booleans are not numbers. Unknown params rejected. | Missing param / wrong type / invalid enum / unknown param. |
| P07 | condition requires on_true and on_false, each existing node ID or explicit null; forbids next. Other nodes require next as existing node ID or explicit null and forbid branch fields. | Missing edge / dangling edge / conflicting edge kind. |
| P08 | limits.max_steps is an integer from 1 through 2147483647; no string/boolean coercion. Optional timeout_seconds and max_ai_tokens are nonnegative integers, preserved but not enforced. Unknown limit keys rejected. | Invalid limit; optional zero retained as in seeds. |
| P09 | Validate template syntax and referenced node IDs only in catalog-templatable fields; do not require trigger keys or branch-dependent outputs to exist at publish time. | Invalid template / unknown referenced node. |
| P10 | Compile ai output_schema locally against selected dialect; never fetch schema resources over the network. Check literal constraints and all node rules below. | Invalid schema / unsupported reference / invalid param value. |

Bounds above are implementation choices for deterministic validation, not capstone requirements; all four seeds fit. Count string limits in Unicode code points, bytes only where explicitly stated. Apply request/depth limits before recursive traversal. Worker result-size, transport timeout and destination policy are separate transport concerns in 4.9/5.9; retry policy is 1.8. Errors identify paths without echoing secret values. JSON bodies and schema property maps may have arbitrary application keys: the unknown-field rule is for workflow structure and catalog params, not their user-defined contents.

## Graph execution

Begin at entry, regardless of nodes array order. Execute exactly one node at a time. A successful ordinary node follows next. A condition follows exactly one edge selected by result; persist that choice with output before continuation. Explicit null completes the path; missing edge is invalid. Failure/rejection/cancellation follows no successor. Both branches may name the same target. Unreachable nodes are permitted but still validated. Self-loops and backward jumps are legal; do not require a DAG, a terminal reachable path, or a topological ordering.

A new visit to any node gets a new step sequence, including revisits after a loop. A retry, crash recovery, delay wake-up or human decision stays attached to its existing step. Completed outputs are not overwritten by later visits. Graph/params come exclusively from the run snapshot; runtime inputs/model text cannot replace node type, entry, edges or limits. No parallel branches, expression evaluation or dynamic nodes.

## Templates and values

Supported syntax is `{{trigger.body}}`, `{{trigger.body.segment...}}`, and `{{nodes.NODE_ID.output}}` with optional `.segment...`. Each segment matches `[A-Za-z0-9_-]+`; NODE_ID follows P02. Whitespace immediately inside braces is allowed and trimmed. Empty segments, bracket notation, array indices, filters, operators, calls and unmatched/doubled delimiters are invalid. Paths traverse JSON object keys only, case-sensitively. A numeric segment is an object key, not an array index. Keys containing dots/spaces or empty keys cannot be individually addressed; whole-object interpolation can still include them. There is no escape syntax for literal template delimiters in templatable fields.

Only catalog-templatable params are resolved. Recursively walk string values inside templatable objects/arrays; keep object keys unchanged and reject template delimiters in keys. Untemplatable string params containing template delimiters are invalid; arbitrary literal strings inside output_schema are never interpreted as templates. Graph fields and trigger configuration are literal. Parse templates once from the stored definition: substituted text is never re-parsed, so a payload containing `{{...}}` stays data.

Every interpolated value becomes text, even when the entire string is one placeholder. Strings substitute unchanged; booleans render true/false; numbers render a plain decimal representation (no plus sign or insignificant fractional zeros; negative zero renders 0); null renders `null`; objects/arrays render compact JSON with lexicographically sorted object keys recursively and array order retained. Use exact decimal handling, not binary floating-point coercion. Whole-object substitution does not turn a string into an object. For an HTTP body object, author its object shape and template its string leaves. Literal number/boolean/null leaves inside that body stay typed and unchanged. `{{trigger.body}}` in an AI prompt is thus useful JSON text.

Missing key, traversing through null/scalar/array, or no previous successful output fails the step with path/node context. A present null is not a missing key. Empty strings remain empty; there is no fallback to an empty string for missing data. Resolved parameters undergo runtime type/value/size validation before a side effect. Template rendering is not URL escaping; validate the resolved URL using transport policy, and percent-encode order_id when inserting it as a path segment in order adapters.

For `nodes.X.output`, choose the succeeded step for X with greatest sequence strictly less than the current step sequence in this run. Skipped branch with no prior X success fails clearly. In a loop this may be the most recent prior iteration, even if X was skipped on the current pass. A first-visit self-reference has no prior success and fails; a later self-reference may read a prior visit. Publish does not reject references just because nodes array order differs. Current, failed, waiting, cancelled and other-run steps never supply output. Once resolved_input/dispatch_request is persisted, retries reuse it, not a fresh lookup.

| Example | Context | Expected result |
| --- | --- | --- |
| `{{trigger.body.amount}}` | amount is JSON number 250 | String `250`, suitable for numeric condition parsing. |
| `value={{trigger.body.x}}` | x is present null | String `value=null`. |
| `{{trigger.body}}` | body is object with b=2, a=1 | String `{"a":1,"b":2}`. |
| `{{trigger.body.x.y}}` | x is null | Clear path traversal failure. |
| `{{nodes.check_order.output.body.status}}` | prior check_order visits at sequences 1 and 4, current sequence 5 | Output from sequence 4. |
| `{{trigger.body.message}}` | message contains template-looking attacker text | Literal supplied text; never evaluated again. |

## Catalog semantics

The table lists exact templatable field names; fields not listed are literal. Required/type/enum/output shapes remain the pinned catalog, not a new registry.

| Node | Templatable params | Runtime behavior |
| --- | --- | --- |
| http_request | url, headers, body | method GET/POST/PUT/DELETE; resolved url absolute HTTP(S), destination policy checked before call/redirect. Optional headers object has string values, valid HTTP names/values, no CR/LF. Optional body object, preserved shape. Non-GET uses engine-owned Idempotency-Key; user key header rejected case-insensitively. Output status number and body object or string; non-object JSON response bodies are retained as JSON text to match that shape. |
| condition | left, right | Required string operands; equals/not_equals compare exact strings, contains is case-sensitive substring (empty right matches). greater_than/less_than parse both trimmed operands as finite decimal JSON-number syntax, compare numerically; parse failure fails step, never false fallback. Output result boolean. |
| delay | none | seconds is a finite nonnegative JSON number. Persist deadline once; round fractional seconds up to nearest millisecond, reject values whose deadline cannot fit storage. Zero still consumes one logical visit and may complete immediately. Output empty object. |
| notify | to, subject, message | channel email/chat literal; to nonblank after rendering, message string (empty allowed), optional subject string. Email maps to /email/send; chat maps to /chat/message with to as channel. subject is ignored for chat. Engine supplies stable key. Output delivered boolean and notification_id string. |
| ai | prompt | prompt string (empty allowed), output_schema object literal; provider output is one complete JSON value with surrounding whitespace permitted, no code-fence stripping/trailing prose/duplicate keys. Schema validation precedes successful output, one repair on validation failure. |
| approval | message | message string, including empty; persist rendered text and pending row, pause. Output decision and decided_by strings on human approve/reject; cancellation gives no fabricated decision. |
| order_action | order_id | action refund/replacement literal; order_id nonblank string after rendering. Optional amount_usd finite positive number for refund only, omission means full refund; amount on replacement rejected. Unknown order/excess refund fails through mock response, no publish-time lookup. Approved earlier same-run evidence required before every attempt. Output status and reference_id strings. |

Catalog flags do not make numeric amount_usd or delay seconds templatable. Runtime validates required string fields as strings without converting supplied numbers; template-generated numeric text is allowed only where a string is expected. Conditional numeric comparison alone parses numeric strings; equals `1` versus `1.0` is false, greater_than `1` versus `1.0` is false. Notify/HTTP nodes are not marked requires_approval by the catalog: do not infer approval from arbitrary text or reinterpret generic HTTP paths as order_action. Configured destinations still restrict outbound requests. This is the specified catalog gate boundary, not a general semantic security proof for arbitrary HTTP workflows.

### AI schema selection

Select JSON Schema Draft 2020-12: absent $schema defaults to that dialect; explicit $schema must name its standard schema URI. Use networknt json-schema-validator 3.0.6 selected in [setup task 2.2](SETUP.md), with actual schema behavior tested in 5.7. Validate schema shape at publish; allow local fragment references only, reject unresolved or external references and alternate/custom dialects. Bundle the standard meta-schema; no schema fetches. Within the chosen standard vocabulary, format is annotation-only, not an extra assertion. This choice follows the distinction in the [official validation specification](https://json-schema.org/draft/2020-12/json-schema-validation); do not promise email/date checking from format alone. Unknown annotation keywords are preserved without inventing assertions; unsupported required vocabularies reject. Resource limits must fail clearly rather than silently skip validation.

Missing schema-required fields, wrong types/enums, extra fields when forbidden, or summary longer than the triage limit fail validation. No coercion/default filling/removal of extra fields. ai_repair_count persists across transport attempts/restart. Second invalid output is permanent failure; no downstream branch sees it. Provider identity/known token usage recorded for all attempts. Exact validator library, transport budgets and provider details remain later tasks.

## Step numbering and cap

At acceptance set steps_executed=0 and next_step_sequence=1. Under the run/ownership transaction, check cancellation first. If resuming an already allocated step, keep its sequence and budget reservation. Otherwise, before allocating a new visit, require steps_executed < limits.max_steps; if false, fail the run with max_steps and target-node reason, allocate no step/attempt and send no effect. If true, allocate sequence=next_step_sequence, increment both counters exactly once, and associate the job with that step atomically. Then resolve/validate input and check human gate before dispatch. An admitted visit that fails resolution or gating still counts and has a failed trace row (possibly zero attempts).

**Retries do not increment steps_executed.** Recovery, approval decisions and delay wake-ups do not increment it either. Every new loop visit does. Counter reservation survives restart; rollback reserves nothing. Attempt numbers are positive and local to the logical step, distinct from sequence; initial counter is 0 until a handler invocation is prepared. The schema repair is another attempt within one step. No worker-local counter is authoritative.

**A final successful node at the cap succeeds.** Do not fail merely because the counter reaches the limit. If a successor exists, the next admission fails once the budget is full. An approval at the cap may wait and approve to success if final, or approve then fail before its successor; rejection/cancellation still cancels. A delayed node at the cap may finish its existing wait; it cannot admit another visit. Retry at the cap remains permitted within retry budget, using the same action key. Terminal/cancellation precedence remains the state design.

With wf_runaway and order remaining processing, the exact logical path is four repetitions of check_order → is_shipped → hold. Sequences 1–12 finish; step 12's delay may complete. Admission of check_order at sequence 13 is denied: run failed, steps_executed=12, next_step_sequence=13, exactly 12 step rows, no thirteenth GET, no done notification. This is not a wall-clock timeout. A distinct legitimate repeated side effect uses a different `{run_id}:{sequence}` key; all attempts within a visit reuse its frozen request/key.

## Approval scope

The runtime gate is a query for approved human evidence at an earlier sequence in this run. One approved approval satisfies subsequent requires_approval actions for the rest of that run, including loop visits; approvals are not consumed, action-specific, or transferable between runs. Each newly visited approval node still creates its own pending request and waits, even if earlier evidence already exists. Rejected/pending/closed/later/other-run records never grant access. A forged AI field such as decision=approved cannot become an Approval row.

Publish validates graph/schema but does not require an approval node to dominate every sensitive action; the engine must reject any unapproved execution regardless of static graph shape. A workflow with an unguarded order_action may publish but fails at that step before sending an effect. This preserves testing of the runtime invariant without claiming static analysis can prove it. Check gate before every dispatch/retry; decision and cancellation serialization follow task 1.6. D01 resolved on 2026-09-28 (preserve supplied graph and enforce approval gates): the triage conditional graph only selects refund_gate for refund_request; do not force complaint/question into approval or rewrite the seed to satisfy the conflicting unconditional-pause expectation.

## Seed walkthroughs

These paths are design expectations, not completed application runs. AI classifications are controlled test outputs, not promises about a real model.

| Seed | Condition/input | Expected logical path |
| --- | --- | --- |
| wf_support_triage | Valid classification refund_request, then approve | classify → route → refund_gate → issue_refund → notify_customer |
| wf_support_triage | Valid classification complaint or question | classify → route → notify_support |
| wf_expense_approval | amount_usd > 100, then approve | is_large → finance_gate → approved_notice |
| wf_expense_approval | amount_usd <= 100 | is_large → auto_ok |
| wf_slow_fulfillment | pay_201 | confirm → pack_delay → create_shipment → shipped_notice |
| wf_runaway | Order remains processing | check_order → is_shipped → hold (four times, then admission denied) |

## Test roadmap and checks

Future tests require implemented parser/engine, canned provider, controllable clock and MySQL where persistence matters. All runtime tests are **not run**.

| Case | Prerequisites / inputs and actions | Expected outcome / owners |
| --- | --- | --- |
| W01 | Draft create/edit/publish; trigger after edit before republish; concurrent publish/edit/trigger; duplicate ID and unchanged publish. | No draft trigger, no mixed snapshot, lost publish revision conflicts, unchanged publish no-op. Tasks 3.6, 3.8, 3.10; V02, V03. |
| W02 | Each P01–P10 violation; malformed/duplicate JSON key, empty nodes, case-distinct/duplicate IDs, dangling entry/edge, unknown type/param; limits 0/1/max/max+1. | Distinct safe validation error, no partial publication; valid boundaries accepted. Tasks 3.2, 3.7; V02. |
| W03 | Reorder nodes array; self/backward loops; same-target branches; unreachable invalid node; null versus missing edge. | Entry drives execution; legal loops accepted, bad nodes/edges rejected. Tasks 3.7, 4.7; V02, V08. |
| W04 | Missing/present-null/scalar/object/array values, nested paths, numeric object keys, array traversal, whole-body substitution, malformed expression, template-looking payload. | Exact string conversion, missing path failure, no code execution or second evaluation. Task 4.6; V07. |
| W05 | Skipped node output, prior loop visits, first/later self-reference; crash after resolved input persisted. | Latest earlier success selected, unavailable output fails, recovery reuses frozen input. Tasks 4.6, 4.11, 4.14; V07, V10. |
| W06 | All condition operators; numeric 100/100.0/250, whitespace, exponent, bad number, case mismatch and empty contains operand. | Deterministic comparisons; expense boundary 100 uses auto_ok, invalid number fails. Task 4.7; V08. |
| W07 | Every catalog node with missing/wrong/null/unknown params; template in numeric field; zero/fractional/negative delay; refund omitted/positive/zero/excess amount. | Catalog and selected value rules enforced before calls where possible; world errors retained. Tasks 3.7, 4.8, 4.9, 4.10; V08, V09. |
| W08 | Triage valid schema, summary length 300/301, extra/missing property, malformed JSON twice; unsupported dialect/reference; literal braces in schema. | No unvalidated output, one repair, no schema network fetch, literal schema unchanged. Tasks 3.7, 5.7; V14. |
| W09 | Cap=1 final/with successor; retry at cap; approval and delay at cap; repeat loop/restart/rollback. | Final success, next admission denied, exactly one count per visit; no new effect beyond budget. Tasks 4.13, 4.14; V16. |
| W10 | Wrong-run/pending/rejected/closed/later/forged approval, approved earlier control, repeat sensitive loop and repeat approval node. | Only stored earlier approval grants action; each approval visit still waits; cancellation prevents continuation. Tasks 5.4, 5.10, 5.11; V13, V15, V17. |
| W11 | Four original seeds and payload bodies; both expense branches and controlled triage categories; runaway processing order. | Paths above, slow path has three intended effects, runaway exactly 12 visits. Tasks 7.1, 7.2; V01, V10, V16. |
| W12 | Size/depth/ID/name boundaries just below/at/above; nested template keys, CR/LF headers, user idempotency header, auth/missing resource. | No truncation or unauthorized work; clear validation failures; generated engine key protected. Tasks 3.2, 3.3, 4.1, 4.9; V02, V04, V09. |

Run `python3 scripts/check_workflow_semantics.py`, the six prior design checkers and `python3 docs/source-review/pack/scripts/validate_pack.py`. The new checker validates document references/catalog flags and seed path walkthroughs, including cap arithmetic; it is not an implementation of template execution or JSON Schema validation. Negative copies test omissions. Manual P01–P10 and W01–W12 review supplements those checks. Actual outcomes: [workflow-semantics-verification.txt](workflow-semantics-verification.txt).

D02 lifecycle/duplicate publication rules and D03 template/schema/cap rules are settled here; route names, response codes, client concurrency headers, pagination and exact transport policies remain their implementation tasks. Task 1.8 defines retry budgets/backoff, lease fencing and recovery SQL in [RECOVERY.md](RECOVERY.md); executable verification remains implementation work. Optional schedules, numbered versions, parallelism, compiler and run time/token caps remain excluded. No routes implemented; never push or publish.

Implementation update (task3.7): the structural parser3.5 and publish-validation component3.7
now cover P01–P10 admission checks with automated seed/catalog/graph/template/schema tests.
This does not implement publication storage, template rendering or workflow execution.
Runtime resolved values, transport/deadline rules and output-schema enforcement remain
in their assigned later tasks. See [validation evidence](publish-validation-verification.txt)
and [API implementation status](../API_DOCUMENTATION.md).


Implementation update (task3.8): authenticated publication now validates a detached draft
and fences its revision under a workflow-row lock before freezing it. Detail projections
include the separately redacted last publication. An internal mandatory-transaction
snapshot factory copies that publication into per-run immutable JSON and holds the same
row lock through caller commit. Real MySQL tests verify stale validation conflicts,
no-op repeat publication, edit/snapshot ordering and snapshot persistence across restart.
Trigger acceptance and run+job queueing remain4.1/4.2. See
[publication evidence](publication-verification.txt).

Implementation update (Phase4): manual/webhook acceptance and deterministic execution
now implement the graph, template, condition, delay, HTTP/notify/action and persisted cap
rules above. Engine tests verify latest prior successful loop output, string/canonical JSON
rendering without second evaluation, distinct per-visit keys and reused retry requests.
The packaged worker crash test verifies recovery against the unchanged mock-world handler.
AI/approval execution, human decision/cancellation APIs and provider/schema repair remain
Phase5. Sensitive action adapters require prior approved evidence; positive adapter tests
seed that valid earlier evidence explicitly, without claiming a human API exists.
See [Phase4 verification](phase4-verification.txt) and [API reference](../API_DOCUMENTATION.md).


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
