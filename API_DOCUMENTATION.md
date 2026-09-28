# Relay API Documentation

## Implementation status

Tasks 2.4–2.5 implement the three health probes and API/worker bootstrap below. Workflow draft CRUD, publication, manual/webhook triggers, approval decisions, cancellation, run list/detail (task6.1) and redacted run trace payloads (task6.2) are implemented; the read-and-operate console consumes them (tasks6.3–6.12). Their fixed contracts are recorded in [requirements](docs/REQUIREMENTS.md) and will be implemented in later tasks.

Update this file whenever a route is added, changed, or removed. Planned behavior must be clearly distinguished from implemented and verified behavior.

Lifecycle decisions are recorded in [state transitions](docs/STATE_TRANSITIONS.md), including approval closure, cancellation races and duplicate decisions. Phase5 implements human decisions and cancellation; their route contracts appear below.

Planned definition validation, draft/publish eligibility, templates and step caps are specified in [workflow semantics](docs/WORKFLOW_SEMANTICS.md). No routes have been added by this design task.

Planned persistence, acknowledgement, retry and uncertain-outcome rules are in [recovery protocol](docs/RECOVERY.md). Phase4 implements durable worker recovery; Phase5 extends it to AI repair and human control.

Planned screen data requirements and fixed/flexible API dependencies are listed in [console design](docs/CONSOLE.md). The implemented console and the verified API demo commands are described in [console demo](docs/CONSOLE_DEMO.md). Browser paths under /console are UI navigation, not implemented backend routes. Full projection/pagination contracts remain route implementation work.

Task 2.5 requires `RELAY_MODE=api` for the database-backed HTTP server.
`RELAY_MODE=worker` creates a non-web process with no health routes or listener.
The isolated `scaffold` profile must have RELAY_MODE unset or blank. API startup
requires RELAY_DEMO_TOKEN; task3.3 enables bearer authentication for management
namespaces as documented below. Scaffold deny rules remain unchanged. Configuration failures terminate startup,
not return HTTP errors. See [launch instructions](docs/SETUP.md#api-and-worker-launch--task-25).

## Route index

| Method | Path | Purpose | Implementation status | Tests |
| --- | --- | --- | --- | --- |
| GET | /actuator/health | Aggregate process/dependency status | Verified in scaffold profile | [HealthEndpointsTest](backend/src/test/java/com/relay/bootstrap/HealthEndpointsTest.java) |
| GET | /actuator/health/liveness | Process liveness | Verified including failure/recovery | Same test class |
| GET | /actuator/health/readiness | Ability to accept work | Verified unavailable without DB | Same test class |
| GET | /workflows | List draft/published summaries | Task3.6 | WorkflowApiMySqlTest |
| POST | /workflows | Create a draft | Task3.6 | WorkflowApiMySqlTest |
| GET | /workflows/{workflowId} | Read current draft with webhook secret removed | Task3.6 | WorkflowApiMySqlTest |
| PUT | /workflows/{workflowId} | Replace draft definition | Task3.6 | WorkflowApiMySqlTest |
| POST | /workflows/{workflowId}/publish | Validate and freeze current draft | Task3.8 | PublicationMySqlTest |
| POST | /workflows/{workflowId}/trigger | Accept manual run | Task4.1 | EngineMySqlTest |
| POST | /hooks/{workflowId} | Accept webhook run | Task4.1 | EngineMySqlTest |
| GET | /approvals | List approval requests by status (workflow_id/created_at added in 6.10) | Task5.2, 6.10 | HumanAiMySqlTest |
| POST | /approvals/{id}/approve | Approve and resume or finish | Task5.2–5.3 | HumanAiMySqlTest |
| POST | /approvals/{id}/reject | Reject and cancel the run | Task5.2–5.3 | HumanAiMySqlTest |
| POST | /runs/{id}/cancel | Request cancellation | Task5.11 | HumanAiMySqlTest |
| GET | /runs | List runs newest first with filters and cursor paging | Task6.1 | RunQueryMySqlTest, RunQueryTest |
| GET | /runs/{runId} | Run status and ordered, redacted step trace (paged) | Task6.1–6.2 | RunQueryMySqlTest, RunQueryTest, TraceRedactorTest |

## Shared API conventions

The current health server binds to 127.0.0.1 and defaults to port 8080 (`RELAY_API_PORT` overrides it). Health probes use Actuator representations, not the future domain API envelope. API-mode bearer authentication is implemented in3.3 below; the isolated scaffold denies all requests except the exact health GET paths. There is no login endpoint or user account. Task3.4 adds management CORS and a memory-only browser access panel, documented below.

## Health probes — implemented in task 2.4

**Source:** roadmap 2.4 and architecture readiness requirement; [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html). These are operational foundation routes, not additional workflow features.

### GET /actuator/health/liveness

Reports Boot application liveness: CORRECT gives HTTP 200 and `{"status":"UP"}`;
BROKEN gives HTTP 503 and `{"status":"DOWN"}`. It deliberately excludes database
health: a database outage must not cause a process restart loop. Failure/recovery
transitions are verified by application availability events in the tests.

### GET /actuator/health/readiness

Combines Boot readiness state and the `db` health contributor. In the explicit
`scaffold` profile, database services are disabled and this route always returns
HTTP 503 with `{"status":"OUT_OF_SERVICE"}`, even after ACCEPTING_TRAFFIC.
REFUSING_TRAFFIC also returns 503. Task 2.9 verified database-backed readiness200,
readiness503 during a real MySQL outage and recovery to200 after restart. Liveness
remained200 during that outage. Tests use an isolated database and test-only migration. Startup without RELAY_MODE fails before creating a listener; valid API mode also requires database configuration. Worker mode has no HTTP service.

### GET /actuator/health

Aggregates available health contributors and advertises the health group names.
The scaffold response is HTTP 503:

```json
{"groups":["liveness","readiness"],"status":"OUT_OF_SERVICE"}
```

A broken liveness contributor makes the aggregate DOWN (503). When all contributors
are UP the normal Actuator mapping is 200; that database-backed scenario remains
2.9. Dependency components, connection addresses, exceptions and credentials are
never returned by these health responses.

### Request and response contract shared by the three probes

- **Authentication/authorization:** exact GET paths are public. No token is needed;
  an arbitrary Authorization header does not change health access or reveal details.
  All other ordinary requests currently receive 401 with an empty body, even with
  bearer/basic headers. This is the scaffold deny rule, not working platform auth.
- **Headers:** use `Accept: application/json` for the documented JSON representation.
  No Content-Type or Idempotency-Key is required for a bodyless GET. Responses carry
  a JSON content type and `Cache-Control` including `no-store`; no session cookie.
- **Path/query parameters:** no caller-supplied parameters. Exact paths above only.
  Unrecognized query keys are ignored, including `showDetails` and `token`; they
  cannot enable health details. No pagination, resource identifier or workflow ID.
- **Body/validation:** no request body is required or defined. Send bodyless GETs.
  Actuator parses JSON bodies when supplied; malformed JSON returns 400. A valid
  JSON body has no documented input semantics for these probes. No persistence or
  authentication decisions use it.
- **Success schema:** probe bodies contain `status` (string); aggregate additionally
  contains `groups` (array of group-name strings). Boot maps UP to 200 and DOWN or
  OUT_OF_SERVICE to 503. Status does not mean workflow execution succeeded.
- **Errors:** malformed JSON gives HTTP 400 with Boot's error representation
  (`timestamp` string, `status` integer, `error` string, `path` string), for example
  `{"timestamp":"2026-09-25T00:00:00Z","status":400,"error":"Bad Request","path":"/actuator/health/liveness"}`.
  Timestamp is illustrative. No message, stack trace or rejected payload is returned.
  Internal ERROR dispatch preserves the original status; direct GET `/error` is 401.
  Health 503 is a status response, not this error schema. Unsupported content negotiation
  and malformed HTTP may be rejected by Spring/Tomcat; only the JSON representation
  and malformed-JSON case are verified in this task.
- **Methods/missing resources:** POST/PUT/PATCH/DELETE/HEAD/OPTIONS to the probe paths
  are denied with 401 before route dispatch. `/actuator`, `/actuator/env`,
  `/actuator/info`, `/actuator/health/db` and unknown health subpaths are also denied
  with 401. There is no resource-level 404/409 behavior on the scaffold.
- **State changes/side effects:** reads do not create runs, mutate business state,
  write database records or enqueue work. No asynchronous operation is started.
  Duplicate and concurrent reads return current health and are safe to repeat;
  no idempotency key, retry queue or server-side retry policy applies. A normal
  DB health check will involve a datasource query in later configuration; scaffold
  checks perform no network I/O.
- **Timeout/restart:** clients should set a finite timeout (examples use 5 seconds).
  Server absence/startup failure is connection failure, not an HTTP response.
  Packaged startup and clean stop were verified. Database timeouts, reconnects,
  and real readiness transitions were verified in2.9; worker execution recovery remains later tasks.

From the backend directory, after building:

```sh
java -jar build/libs/relay-backend-0.1.0.jar --spring.profiles.active=scaffold
```

From another terminal (readiness/aggregate HTTP 503 is expected):

```sh
curl --max-time 5 -i -H 'Accept: application/json' http://127.0.0.1:8080/actuator/health/liveness
curl --max-time 5 -i -H 'Accept: application/json' http://127.0.0.1:8080/actuator/health/readiness
curl --max-time 5 -i -H 'Accept: application/json' http://127.0.0.1:8080/actuator/health
```

### Health verification

| Case | Expected result | Automated coverage | Actual result |
| --- | --- | --- | --- |
| Liveness / scaffold aggregate and readiness | 200 UP / 503 OUT_OF_SERVICE, no details | livenessIsUpButDatabaseFreeScaffoldIsNotReady | Passed |
| Broken/recovered liveness | 503 DOWN then 200 UP | brokenAndRecoveredLivenessIsReflectedWithoutRestart | Passed |
| Readiness event with absent DB | Cannot override 503 | acceptingTrafficCannotOverrideMissingDatabase | Passed |
| Other paths and internal error URL | Empty 401, no redirect/session | otherPathsAreDenied (9 paths) | Passed |
| Non-GET methods | Empty 401 on all three paths | onlyGetIsPublic (6 methods) | Passed |
| Arbitrary bearer/basic credentials | No added privilege or disclosure | credentialsDoNotGrantAccessOrChangePublicHealth (2 cases) | Passed |
| Query override / malformed JSON | Query cannot expose details; malformed body 400 without trace | queryCannotEnableDetailsAndMalformedBodyPreservesBadRequest | Passed |
| Concurrent repeated reads | 8 reads return unavailable, no session | repeatedAndConcurrentReadsHaveNoSessionOrStateMutation | Passed |
| Scaffold service boundary / Jakarta Validation | No DataSource/JPA/Flyway service; constraints usable | scaffoldDoesNotCreateDatabaseServicesAndValidationIsAvailable | Passed |
| Packaged process and default startup | Jar probes behave as above; missing-mode startup fails | [packaged smoke check](scripts/check_backend_smoke.py) | Passed |

Tests: [HealthEndpointsTest.java](backend/src/test/java/com/relay/bootstrap/HealthEndpointsTest.java).
Run `./gradlew --gradle-user-home .gradle/user-home --no-daemon test bootJar` in backend;
run `python3 scripts/check_backend_smoke.py` from the root. **23 automated HTTP tests
passed on 2026-09-25.** The packaged smoke check also passed and stopped its process.
Exact outcomes, including the initial regression and repair:
[backend verification](docs/backend-scaffold-verification.txt).

## Required entry for each route

Copy this structure for each implemented route and replace every placeholder with actual contract and implementation details.

### METHOD /path — Route name

- **Status:** Planned / Implemented, verification pending / Verified.
- **Requirement/source:** PDF requirement and supporting contract section.
- **Purpose:** What the route does.
- **Authentication and authorization:** Required credentials, permissions, and resource access checks.
- **Headers:** Required and optional headers, including content type and idempotency where applicable.
- **Path/query parameters:** Names, types, required/default values, limits, and validation.
- **Request body:** Field types, required/optional fields, constraints, and unknown-field handling.
- **Example request:** Reproducible curl command with placeholders instead of real credentials.
- **Success response:** Actual status code, headers, response schema, and example body.
- **Error responses:** Each applicable status code, trigger condition, schema, and example body.
- **State changes and side effects:** Persistence changes, queued work, approval effects, and external actions.
- **Async/retry/idempotency behavior:** What acceptance means, how results are obtained, duplicate handling, and retry expectations.

#### Edge cases and test cases

For each applicable case, record concrete input/action and expected status/body/state changes. Explain any case marked not applicable.

| Case | Input / action | Expected result | Automated test reference | Actual result |
| --- | --- | --- | --- | --- |
| Success | To specify | To specify | To add | Not run |
| Missing/invalid credentials or insufficient access | To specify | To specify | To add | Not run |
| Missing fields, invalid types, malformed JSON | To specify | To specify | To add | Not run |
| Boundary values and empty input | To specify | To specify | To add | Not run |
| Resource not found | To specify | To specify | To add | Not run |
| Invalid state or conflicting operation | To specify | To specify | To add | Not run |
| Repeated request / duplicate decision | To specify | To specify | To add | Not run |
| Concurrent requests | To specify | To specify | To add | Not run |
| Dependency failure, timeout, retry, or restart | To specify | To specify | To add | Not run |
| Route-specific edge cases | To specify | To specify | To add | Not run |

#### Verification

- Prerequisites and fixtures:
- Automated test command(s):
- Manual test steps:
- Expected observable results:
- Actual results and date:
- Known limitations / outstanding checks:

## Documentation change log

| Date | Change | Verification |
| --- | --- | --- |
| 2026-09-28 | Task6.13: added nullable frozen-snapshot `max_steps` to every `GET /runs` summary item | Verified 2026-09-28: RunQueryTest serialization (unit, pass) and RunQueryMySqlTest list projection/cap/null assertions (real MySQL via Testcontainers, pass); evidence in `docs/phase6-verification.txt` |
| 2026-09-25 | Created documentation structure; no routes implemented | Checked that no example route is presented as implemented |

| 2026-09-25 | Task 2.4: three health probes and scaffold security/error behavior | 23 HTTP tests and packaged smoke check passed; MySQL readiness remains 2.9 |

| 2026-09-25 | Task 2.5: API/worker availability and startup requirements | 78 automated tests and packaged startup checks passed; real MySQL verification remains 2.9. See [launch evidence](docs/launch-modes-verification.txt) |

| 2026-09-28 | Task6.1: added GET /runs (filters, keyset cursor) and GET /runs/{runId} (ordered paged steps); status sentence and cancel note updated | 219 unit and 31 real-MySQL tests passed; 13 document checks passed. See [Phase6 verification](docs/phase6-verification.txt) |

| 2026-09-28 | Task6.2: redacted trace payload (input, resolved input, output, error, attempts, AI usage, approval evidence, retry due) on GET /runs/{runId}; task6.10: workflow_id and created_at on GET /approvals | 223 unit and 32 real-MySQL tests passed (full run); HumanAiMySqlTest 14/14 after the approvals change. See [Phase6 verification](docs/phase6-verification.txt) |

## Supplied local test services — task 2.8

The mock world and provider are separate local test doubles, not Relay API routes.
Their methods, auth, request/response behavior, side effects, failures and limitations
are documented in [local mock HTTP reference](docs/MOCKS.md#local-mock-http-reference).
No platform route changed in this task. Runtime checks use disposable mock processes;
full engine/API acceptance remains outstanding.

## Real database startup verification — task 2.9

[Component startup checker](scripts/check_component_startup.py) runs the executable
jar against a disposable MySQL8.4.11 database. API startup applies a temporary
filesystem migration; readiness and liveness return200. Stopping that database
produces readiness503 while liveness remains200; restarting it restores readiness200.
Worker starts without an HTTP service after the matching migration; absent, pending,
checksum-mismatched or future schema history fails startup. Wrong API credentials
also fail startup rather than return an HTTP error. No new routes were added.
Evidence: [component verification](docs/component-startup-verification.txt).
Domain schema, execution and authenticated platform endpoints remain later tasks.


## Domain error foundation — task3.2

No domain endpoint is added. Advice scoped to future com.relay.api controllers is
verified through test-only MVC routes, which are not packaged. Its envelope is
`{"error":{"message":"The request is invalid.","code":"invalid_input"}}`.

| Condition | Status / code | Actual verification |
| --- | --- | --- |
| Malformed JSON or Bean Validation failure | 400 / invalid_input | Test-only MVC passed |
| Explicit missing resource | 404 / not_found | Test-only MVC passed |
| State, database integrity or optimistic-lock conflict | 409 / conflict | Test-only MVC passed |
| Unexpected controller exception | 500 / internal_error | Test-only MVC passed |

General failure messages are fixed and omit raw SQL, rejected payloads, exception
text and stack traces. Specific workflow-validation codes come with those routes.
Scaffold security returns empty401 on non-health paths; API mode now uses the3.3
authentication envelopes and implemented workflow CRUD. Actuator behavior is unchanged. Internal summary DTOs exclude raw definitions
and credentials; they are not finalized list-route contracts.
Tests: [ApiFoundationTest](backend/src/test/java/com/relay/api/ApiFoundationTest.java).
Evidence: [persistence verification](docs/persistence-foundation-verification.txt).

## Management authentication — task3.3

In `RELAY_MODE=api`, the `/workflows`, `/runs` and `/approvals` namespaces (including
nested paths and all methods) require exactly one Authorization header:

```http
Authorization: Bearer <RELAY_DEMO_TOKEN>
```

The configured token must be nonempty and use HTTP bearer-token characters
(letters/digits and `-._~+/`, with optional trailing `=`). Whitespace/non-ASCII and
other punctuation fail API startup without printing the value. Bearer scheme is
case-insensitive; token bytes are case-sensitive. Only the Authorization header is
used: query parameters, cookies, X-Decided-By and JSON actor fields cannot authenticate
or select the authenticated principal. Duplicate/combined credentials are rejected.
Token hashes are compared using constant-time digest comparison. Never put the token
in frontend environment files, URLs, logs or database decision fields.

| Request | API-mode result | Verified behavior |
| --- | --- | --- |
| Missing/wrong/malformed/duplicate token to management namespace | 401 | JSON unauthorized, WWW-Authenticate: Bearer realm="Relay", Cache-Control: no-store |
| Valid token to management namespace | Passes authentication | Implemented workflow CRUD routes respond as documented in3.6; other domain handlers remain absent |
| Authenticated request denied by a protected handler | 403 | JSON forbidden, no raw exception or token |
| Exact GET health paths | Public | Existing200/503 representations; arbitrary authorization does not alter access |
| Hooks, login, other Actuator/unknown namespaces | Denied401 | Management token does not grant access; webhook secret handling remains4.1 |
| Scaffold profile | Health-only | Existing empty401 deny responses unchanged; token does not enable management |
| Worker mode | No HTTP | No authentication listener/session |

401 body: `{"error":{"message":"Management authentication is required.","code":"unauthorized"}}`.
403 body: `{"error":{"message":"Access is denied.","code":"forbidden"}}`.
Security-filter failures use these envelopes before controller dispatch. No session,
login redirect, Basic authentication or cookies are created. Each request authenticates
independently; missing credentials following a successful request still fail. CSRF is
disabled for stateless header credentials. CORS/preflight support is implemented in task3.4 below; do not
interpret an authenticated unsupported method as an implemented operation.

All valid demo-token requests have server principal `demo-operator` and the management
authority. This represents the shared demo credential, not an individually identified
person. An approval entity's recordHumanDecision method derives this actor from the
security context and persists actor/time/status, rejecting anonymous/untrusted identities,
invalid timestamps or an already decided/closed record. Caller-supplied actor strings
are not accepted. Real MySQL verification confirms actor/time persistence.

**No approval endpoint or run continuation is added here.** The later5.2/5.3 decision
service must lock/check run and approval state and commit decision, run and queue changes
atomically. An in-memory decision guard is not a concurrent database decision protocol.
No authorization is conferred by model output or by merely constructing a DTO/entity.

Use a private random token, for example `python3 -c 'import secrets; print(secrets.token_hex(32))'`
in your own terminal, and supply it through the API process environment. Restart API to
rotate it; the old token then stops working. Do not share it as an individual identity.
Example after normal API startup (returns200 workflow summary array since3.6):

```sh
curl --max-time 5 -i http://127.0.0.1:8080/workflows -H "Authorization: Bearer $RELAY_DEMO_TOKEN"
```

Test coverage: [ManagementSecurityTest](backend/src/test/java/com/relay/security/ManagementSecurityTest.java),
[DecisionActorTest](backend/src/test/java/com/relay/security/DecisionActorTest.java),
[PersistenceMySqlTest](backend/src/test/java/com/relay/persistence/PersistenceMySqlTest.java).
Test handlers are absent from the production jar. Results:
[authentication verification](docs/authentication-verification.txt).


## Management browser access — task3.4

API mode accepts exact browser origins from `RELAY_ALLOWED_ORIGINS`, a comma-separated
HTTP(S) origin list (default `http://localhost:5173`). Blank disables cross-origin
management access. Origins must contain only scheme, host and optional valid port;
paths, trailing slash, credentials, query, fragment, wildcard and `null` are rejected
at API startup. Duplicates are removed. `localhost` and `127.0.0.1` are different origins.
Worker ignores this setting; scaffold retains its health-only policy.

For `/workflows/**`, `/runs/**`, `/approvals/**` (including namespace roots), CORS runs
before bearer authentication. `OPTIONS` with `Origin` and `Access-Control-Request-Method`
is a preflight, requires no token and returns200 for an allowed origin/method/header
combination. Allowed methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS. Allowed
request headers: Authorization, Content-Type, Accept. Method permission does not
create an endpoint or authorize an operation. Preflight has no domain side effects.

Successful CORS responses include `Access-Control-Allow-Origin` with the exact origin,
`Vary` for Origin/request-method/request-headers, and expose `WWW-Authenticate` and
`Retry-After`. Preflight allows the requested permitted headers/methods and caches
permission for600 seconds. `Access-Control-Allow-Credentials` is absent; clients omit
cookies. Actual requests still require the bearer token and all domain validation.
Allowed-origin401/403 authentication/authorization responses retain CORS headers so
the browser can read their documented envelopes. No-Origin/same-origin requests
retain their previous behavior. Health/hooks do not receive management CORS permission.

Unlisted origin, disallowed preflight method/header or disabled cross-origin access
returns403 with `Content-Type: application/json`, `Cache-Control: no-store` and body
`{"error":{"message":"Browser origin or preflight is not allowed.","code":"cors_denied"}}`.
No allow-origin header grants access on rejection; the browser may report a generic
network/CORS failure instead of exposing this body. CORS is not authentication.

Example preflight (no token; requires matching configured origin):

```sh
curl -i -X OPTIONS http://127.0.0.1:8080/workflows \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization'
```

The console submits `GET /workflows` with the entered bearer token and confirms
connection only after a valid minimal workflow list response (array or `workflows`
wrapper, rows with nonempty id and draft/published status). The production list route is implemented in3.6: valid credentials now produce200,
including an empty array when no workflows exist.
Tests use contract-compatible list doubles for the successful connection flow.
Tokens never enter persistent browser storage, URLs or a shared frontend environment
variable. Submission clears the password field; disconnect/reload clears the session;
protected401 disconnects and aborts pending work. Session generations discard late
responses, including old401 responses after a new connection.403 does not discard a
confirmed session. Invalid input, missing API, conflict, throttling, server, timeout,
network and malformed-response failures use safe messages. Mutations are never retried
automatically. Data caches and polling remain later console tasks.

Tests: [CORS HTTP cases](backend/src/test/java/com/relay/security/ManagementSecurityTest.java),
[origin validation](backend/src/test/java/com/relay/security/ManagementCorsTest.java),
[session tests](frontend/tests/session.test.ts), [browser UI](frontend/tests/console.spec.ts),
[real browser CORS](frontend/tests/browser-cors.mjs). Actual commands/results are in
[verification evidence](docs/browser-access-verification.txt).

## Workflow parsing foundation — task3.5

The backend now bundles the unchanged canonical node catalog and a structural draft
parser. **No workflow or catalog HTTP route is added by this task.** Authentication,
CORS remains unchanged. Workflow CRUD is implemented in3.6 below;
publish-time catalog parameter, graph, template and schema validation is implemented in3.7 and connected to publication in3.8.

`DefinitionParser.parse(String)` accepts a single JSON object up to1MiB UTF-8 with
nesting depth at most64. Duplicate keys at every depth, trailing JSON values, malformed
JSON, unknown structural fields and invalid field types reject with `DefinitionException`.
Its fixed reason and structural path omit submitted values and raw parser exceptions.
Future routes must translate these failures to the API error envelope and enforce
request byte limits before buffering; this parser is not an HTTP request-size filter.

Required fields: id (nonblank,128 Unicode code points), name (nonblank,200), trigger,
entry, limits and a nonempty nodes array. Optional description is a string up to4000
code points. Node IDs/entry/nonnull edge targets match `[A-Za-z_][A-Za-z0-9_-]{0,127}`;
node IDs are unique and case-sensitive. Node fields are id, type (nonblank string),
params (object), and optional next/on_true/on_false (node ID or explicit null).
Missing and null edges remain distinct. Unknown node types, incomplete parameters,
missing/dangling edges, conflicting edge kinds and loops can be stored as drafts;
parsing alone does not authorize publishing or execution.

Triggers: manual has only type; webhook also requires a nonblank literal secret up to
1024 UTF-8 bytes, without template delimiters. Schedule remains outside the MVP.
Limits: max_steps is an integer1..2147483647; optional timeout_seconds/max_ai_tokens
are nonnegative integers retained without enforcement. Unknown limit keys reject.
Numbers in arbitrary params preserve decimal precision; parameter object keys and
contents are retained without applying structural unknown-field rules to user data.

`WorkflowDefinition` provides defensive JSON/node copies and ordered node IDs;
`NodeCatalog` provides defensive copies of all seven node contracts (required/types/
enums/template flags/outputs/side-effect/approval flags). Its source retains the pack's
schedule metadata for provenance, but supportedTriggers exposes only manual/webhook.
Catalog metadata is not evidence of implemented runtime handlers.

Tests: [parser/catalog tests](backend/src/test/java/com/relay/workflow/DefinitionParserTest.java).
Actual results: [parser verification](docs/definition-parsing-verification.txt).


## Workflow draft APIs — task3.6

All four routes require exactly one `Authorization: Bearer <RELAY_DEMO_TOKEN>` header.
The shared demo credential has management access; there are no per-user roles or
ownership filters. Missing/invalid credentials return401 before body parsing or DB writes.
API mode only; scaffold/worker behavior is unchanged. CORS follows task3.4. Responses
are JSON, with security `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`.
No cookies, background jobs, webhook calls, AI calls or automatic retries occur.

### Create and replace

`POST /workflows` accepts the original seed-shaped definition and returns201 with
the detail projection below. Duplicate IDs return409, including concurrent creates;
POST never overwrites an existing workflow. No Location header is required or emitted.

`PUT /workflows/{workflowId}` accepts a complete definition, returns200 with the same
projection, and requires body id to equal the decoded path id (400 immutable_id otherwise).
Unknown ID returns404 after valid input is checked. Replacement is not a partial patch:
omitted optional description is removed; supplied params/edges replace their old values.
A webhook replacement must include its secret again. **Do not send the redacted GET
projection as a PUT body.** Keep the original authoring definition securely.

Both writes require `Content-Type: application/json` and a UTF-8 JSON body. Maximum is
1MiB of actual bytes, checked while reading even without Content-Length; oversize413.
Malformed UTF-8/JSON, duplicate keys, trailing values, empty body and structural failures
return400. See the task3.5 field/type/size/ID/trigger/limit rules above. Unknown node types,
incomplete params and dangling graph edges remain valid drafts; publish validation is implemented in3.7/3.8.
The server controls status and timestamps; they are not accepted definition fields.

Create sets status draft and created_at/updated_at to the same UTC instant. Replacement
keeps id/created_at, updates updated_at and sets status draft. Any existing frozen
published_definition/published_at remains unchanged. Updating a published workflow
therefore makes it ineligible for new triggers until republished (trigger routes remain
unimplemented). Existing run snapshots are not touched. These operations commit
synchronously before success. There is no client revision/ETag precondition: updates
lock the workflow row and serialize; the last successful replacement wins in full.
DB uniqueness resolves competing inserts; lock/optimistic conflicts return409.

A lost POST response can be reconciled by GET before retry: a retry after commit returns409.
A repeated valid PUT replaces the same draft content but refreshes updated_at; do not
assume a failed connection means the server did not commit. No Idempotency-Key handling
is provided for workflow editing. After409, reread and explicitly decide whether to retry.
Unexpected storage errors return a safe500 envelope, without SQL, credentials or payload.

### List and detail

`GET /workflows` returns200 with an array sorted by ID (database binary collation), or
`[]` for an empty database (normal API startup now inserts missing seeds; disable loading
for an intentionally empty DB). No pagination or filtering is implemented; query parameters
have no effect. Each row has id, name, status (draft/published), trigger_type
(manual/webhook), updated_at (UTC timestamp). It does not include secrets or definitions.

```json
[{"id":"wf_demo","name":"Demo","status":"draft","trigger_type":"manual","updated_at":"2026-09-27T10:00:00Z"}]
```

`GET /workflows/{workflowId}` returns200 detail or404 not_found. Treat workflowId as a
case-sensitive opaque ID, URL-encoded as a single path segment; use ordinary IDs such
as the supplied wf_* values for portable routing. Servlet/security restrictions on encoded
slashes and reserved path syntax still apply; these are not multi-segment ID routes.
GET has no state changes. No request body is required; no special Accept header is needed.
The current draft is returned even if an older publication exists. Since3.8 the detail
also includes the separately redacted published_definition; run history remains later work. Known webhook secret is removed from definition.trigger;
secret_configured reports its presence. Arbitrary authored node params remain in the
definition: this is not a general-purpose scrubber for secrets embedded in user content.

Detail/write response schema: id/name strings, description string or null, status string,
definition object, secret_configured boolean, created_at/updated_at UTC timestamp strings,
published_at UTC timestamp or null. Internal revision/entities and raw secret-bearing publication JSON are not exposed. Since3.8,
published_definition is a redacted object or null, and published_secret_configured is boolean.

```json
{"id":"wf_demo","name":"Demo","description":null,"status":"draft","definition":{"id":"wf_demo","name":"Demo","trigger":{"type":"manual"},"entry":"wait","limits":{"max_steps":1},"nodes":[{"id":"wait","type":"delay","params":{"seconds":0},"next":null}]},"secret_configured":false,"created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:00:00Z","published_at":null,"published_definition":null,"published_secret_configured":false}
```

### Reproduction and errors

With the normal API running and RELAY_DEMO_TOKEN set in your terminal:

```sh
curl -i http://127.0.0.1:8080/workflows \
  -H "Authorization: Bearer $RELAY_DEMO_TOKEN" -H 'Content-Type: application/json' \
  --data '{"id":"wf_demo","name":"Demo","trigger":{"type":"manual"},"entry":"wait","limits":{"max_steps":1},"nodes":[{"id":"wait","type":"delay","params":{"seconds":0},"next":null}]}'
curl -i http://127.0.0.1:8080/workflows -H "Authorization: Bearer $RELAY_DEMO_TOKEN"
curl -i http://127.0.0.1:8080/workflows/wf_demo -H "Authorization: Bearer $RELAY_DEMO_TOKEN"
curl -i -X PUT http://127.0.0.1:8080/workflows/wf_demo \
  -H "Authorization: Bearer $RELAY_DEMO_TOKEN" -H 'Content-Type: application/json' \
  --data '{"id":"wf_demo","name":"Updated demo","trigger":{"type":"manual"},"entry":"wait","limits":{"max_steps":1},"nodes":[{"id":"wait","type":"delay","params":{"seconds":1},"next":null}]}'
```

These manual commands intentionally save one draft to your configured DB. Repeat POST
expects409. PUT with a different body ID expects400. Reads of an unknown ID expect404.
The console can now confirm Connect with an empty or populated real list; its data pages
remain placeholders until6.x. Restart an older running API process to use the new jar.

| Status | Code/meaning |
| --- | --- |
| 200 | Successful list/detail/replacement; standard Spring HEAD uses GET semantics without body |
| 201 | Created draft |
| 400 | Parser reason such as invalid_json, invalid_utf8, immutable_id, expected_object, expected_string, unknown_field, invalid_string, invalid_node_id, duplicate_node_id, unsupported_trigger, invalid_secret, invalid_limit, expected_nonempty_array; safe structural path in message |
| 401 | unauthorized (missing/wrong bearer token) |
| 403 | cors_denied/forbidden as documented in shared security rules |
| 404 | not_found for missing workflow; publish and trigger routes are documented below |
| 405 | method_not_allowed (e.g. DELETE on detail; no deletion API) |
| 409 | conflict for existing ID or competing storage operation |
| 413 | payload_too_large |
| 415 | unsupported_media_type (non-JSON write) |
| 500 | internal_error, safe unexpected failure |

Error example: `{"error":{"message":"Invalid workflow definition: immutable_id at $.id","code":"immutable_id"}}`.
No submitted secret or raw parse/SQL exception is included in errors. Framework rejections
before controller dispatch (e.g. malformed URL) can use the framework error representation.

Automated evidence: [real HTTP/MySQL test](backend/src/test/java/com/relay/api/WorkflowApiMySqlTest.java),
[parser tests](backend/src/test/java/com/relay/workflow/DefinitionParserTest.java),
[auth tests](backend/src/test/java/com/relay/security/ManagementSecurityTest.java),
[browser CORS](frontend/tests/browser-cors.mjs), [results](docs/workflow-api-verification.txt).

## Publish validation component — task3.7

`PublishValidator.validate(WorkflowDefinition)` now checks parsed drafts for publication.
This is an internal side-effect-free component. Draft POST/PUT structural validation remains
unchanged. Task3.8 now invokes this component from POST /workflows/{id}/publish before
atomic frozen-definition storage. No partial publication,
DB mutation, template execution or provider/world call happens during validation.

All nodes, including unreachable ones, must use one of the seven catalog types. Required
params must be present; all supplied params must match catalog types/enums. Optional
nulls and unknown params reject. Boolean values are not numbers. Entry and nonnull edge
targets must exist. Conditions require both on_true/on_false and forbid next; other nodes
require next and forbid branch fields. Explicit null ends a path; an omitted edge rejects.
Self-loops, backward jumps, equal branch targets, out-of-array-order entry and unreachable
valid nodes are allowed. Static approval dominance is not required; actual sensitive
execution still needs the later runtime human-approval gate.

Catalog-templatable strings are parsed for `{{trigger.body[.key...]}}` or
`{{nodes.NODE_ID.output[.key...]}}` (bracketed portions here denote optional syntax,
not literal brackets). Object keys use dot-separated `[A-Za-z0-9_-]+` segments. Numeric
segments denote object keys, not array indices. Whitespace inside delimiters is trimmed.
Missing referenced node IDs, malformed/unmatched/nested delimiters, operators, calls,
bracket notation and empty path segments reject. Nested object/array string values are
checked; template delimiters in object keys reject. No trigger keys or historical node
outputs are looked up at publish validation. Literal output_schema strings are never
interpreted as templates. Rendering, missing-value handling and resolved-value checks
remain4.6 and runtime handler tasks.

Literal validation: delay.seconds is nonnegative; refund amount_usd, when supplied, is
positive and forbidden for replacement; notify.to and order_action.order_id cannot be
blank. Literal HTTP URL must have an HTTP(S) scheme and host; templated URLs await
resolved destination validation. Headers require HTTP token names and string values
without control characters other than horizontal tab (or characters outside HTTP byte range);
authored Idempotency-Key is forbidden case-insensitively. Transport policy,
resolved nonblank values, deadline storage bounds and world-side order/refund validation
remain runtime obligations. Empty approval/AI prompt/notification message strings are allowed.

AI output_schema uses the pinned networknt3.0.6 validator and bundled Draft2020-12
meta-schemas. Absent $schema selects2020-12; explicit alternative/custom dialects reject.
Only local-fragment $ref/$dynamicRef is admitted; unresolved references reject, including
inside unused $defs. Referenced annotation objects become schemas and undergo the same
checks. Unreferenced annotation/default/const/example data remains literal. Standard
format is annotation-only; unknown annotations and optional unknown vocabularies are
retained. Unsupported required vocabularies (including format-assertion) reject. Schema
shape and compilation failures reject with safe errors. Remote retrieval is disabled;
no network request is made to resolve a supplied schema. Provider-output validation and
repair/retry behavior remain5.7. Schema recursion is permitted; compilation overflow
becomes a safe invalid_output_schema failure rather than silently skipping validation.

The component throws DefinitionException with a fixed reason and safe structural path
(e.g. $.nodes[2].params.prompt), without echoing arbitrary parameter keys or values.
Reasons: invalid_entry, invalid_node_type, missing_param, unknown_param,
invalid_param_type, invalid_param_enum, missing_edge, conflicting_edge,
invalid_edge_target, invalid_template, unknown_template_node, invalid_template_key,
invalid_param_value, invalid_header, invalid_output_schema, unsupported_schema_dialect,
unsupported_schema_reference, unsupported_schema_vocabulary. Existing structural
parser errors remain as in3.5. The publish route now maps these using the existing400 error advice, as documented in3.8.

Tests: [catalog/graph/template/value cases](backend/src/test/java/com/relay/workflow/PublishValidatorTest.java),
[schema cases and no-fetch check](backend/src/test/java/com/relay/workflow/OutputSchemaValidatorTest.java).
Commands/results: [publish validation verification](docs/publish-validation-verification.txt).


## Publish and frozen definitions — task3.8

### POST /workflows/{workflowId}/publish

Validates the stored draft and freezes it for future trigger acceptance. API mode only;
requires `Authorization: Bearer <RELAY_DEMO_TOKEN>`. This shared management credential
has builder permission; no additional role or owner parameter is accepted. The path ID
uses the same case-sensitive, single-segment encoding rules as GET/PUT detail.

**Send no request body.** Content-Type is unnecessary and ignored; even `{}`, whitespace
or malformed JSON sent as a body yields400 invalid_input. No query parameters, optimistic
client headers or Idempotency-Key behavior are defined. Missing/wrong token returns401
before publication work. Normal management CORS/preflight rules apply.

```sh
curl -i -X POST http://127.0.0.1:8080/workflows/wf_demo/publish \
  -H "Authorization: Bearer $RELAY_DEMO_TOKEN"
```

Success is200 with the workflow detail response (same schema as GET detail, with the
new publication fields described below). No Location header, run ID or queued job is
created. Example after publishing the wf_demo definition from the create example:

```json
{"id":"wf_demo","name":"Demo","description":null,"status":"published","definition":{"id":"wf_demo","name":"Demo","trigger":{"type":"manual"},"entry":"wait","limits":{"max_steps":1},"nodes":[{"id":"wait","type":"delay","params":{"seconds":0},"next":null}]},"secret_configured":false,"created_at":"2026-09-27T10:00:00Z","updated_at":"2026-09-27T10:01:00Z","published_at":"2026-09-27T10:01:00Z","published_definition":{"id":"wf_demo","name":"Demo","trigger":{"type":"manual"},"entry":"wait","limits":{"max_steps":1},"nodes":[{"id":"wait","type":"delay","params":{"seconds":0},"next":null}]},"published_secret_configured":false}
```

All detail responses (GET detail, create, replace and publish) now include:

| Field | Type | Meaning |
| --- | --- | --- |
| published_definition | object or null | Last frozen definition with trigger.secret removed; null before first publication |
| published_secret_configured | boolean | Whether that publication has a webhook secret; false for no publication/manual trigger |

`definition`/`secret_configured` still describe the current draft. List summaries remain
unchanged and include neither definition. Both secret flags are safe metadata, not
credentials. Arbitrary authored node parameters are still returned: secret redaction
covers the known webhook-secret field, not arbitrary user content. Redacted projections
cannot be used directly as webhook-definition replacement bodies.

### Validation, atomicity and retries

The server reads the draft and internal revision in a short transaction, then performs
all3.7 validation outside the row lock. It opens a write transaction, locks the workflow,
and checks that revision and draft still match the validated read. Any intervening edit
or publication that changes the revision yields409 conflict; it never publishes a newer
unvalidated draft. Failed validation leaves draft/publication/status/timestamps/revision
unchanged. It validates every node, including unreachable ones; loops are legal.

Success copies the entire draft (including its original webhook secret) into restricted
publication storage, sets status published and sets published_at/updated_at together.
The transaction commits before the HTTP response. Repeating publication while already
published and unchanged returns200 without changing either timestamp or revision.
Concurrent first-publication requests may produce200/409; concurrent no-op repeats on
an unchanged published workflow return200. After409, reread and explicitly retry if the
current draft is the intended one; the server does not automatically retry a stale request.
After a lost connection, GET detail can reconcile whether publication committed.

PUT always returns a workflow to draft, even if its content is unchanged. It preserves
the previous frozen publication/timestamp; a later successful publish replaces that
publication atomically. There is no numbered version history. Already captured run
snapshots retain their own definition, including the original secret, independently of
all subsequent workflow edits/publications. No existing run or job is modified by publish.

| Status | Error code / result |
| --- | --- |
| 200 | Published or unchanged no-op; detail projection |
| 400 | invalid_input for nonempty body; structural/parser or publish-validation reason for invalid draft |
| 401 | unauthorized for missing/invalid bearer token |
| 403 | Existing forbidden/CORS rejection rules |
| 404 | not_found for unknown workflow |
| 405 | method_not_allowed for GET or other unsupported publish methods |
| 409 | conflict for stale revision or storage/lock conflict |
| 500 | internal_error for unexpected safe failure; raw SQL/stack/secret not exposed |

Validation uses the exact3.7 reason codes, including invalid_node_type, missing_param,
invalid_entry, invalid_edge_target, invalid_template and invalid_output_schema. Example:
`{"error":{"message":"Invalid workflow definition: invalid_node_type at $.nodes[0].type","code":"invalid_node_type"}}`.
All successful and controller/security error responses use JSON and no-store caching;
framework-level malformed URL/method errors may retain their standard representation.
Schema validation does not fetch remote resources. Publication has no asynchronous work,
provider calls or external side effects; it is local database state only.

### Internal run snapshot boundary

`RunSnapshotFactory.prepare` is an internal service requiring an existing writable
transaction. It locks the same workflow row, rejects missing/not-published workflows,
and returns an **unsaved** queued Run owning the accepted publication JSON, input and
caller-supplied execution-policy JSON as immutable strings. Entry comes from that snapshot;
step count starts0, next sequence1, known AI usage starts0. Manual input must be an object;
webhook input may be any JSON value including null. Manual triggering may use a webhook
workflow; webhook triggering cannot use a manual workflow. No snapshot can be selected
from an edited draft simply because an older publication still exists.

The lock lasts through the caller's transaction, serializing snapshot selection with
edits/publication. Definition snapshot/input/policy columns are not updated by JPA;
the factory makes no reference to caller-mutable JSON trees. This is not a public trigger
API or completed run acceptance. Tasks4.1/4.2 must authenticate the caller/webhook secret
against the accepted publication, validate the full execution policy and atomically save
run plus initial queue job in that same transaction. Publication does not mean a worker
can execute workflows yet.

Verified tests: [unit orchestration](backend/src/test/java/com/relay/api/PublicationTest.java),
[real HTTP/MySQL snapshots and races](backend/src/test/java/com/relay/api/PublicationMySqlTest.java),
[existing CRUD regression](backend/src/test/java/com/relay/api/WorkflowApiMySqlTest.java).
Exact commands/results: [publication verification](docs/publication-verification.txt).


## Default startup seed data — task3.9

There is no seed-management HTTP route and no route/schema change. Normal API startup
now inserts missing canonical workflows before completing startup, so a fresh default
GET/workflows response contains four Published IDs: wf_support_triage,
wf_expense_approval, wf_slow_fulfillment and wf_runaway. The same bearer token and
redacted list/detail/publication representations apply. Pack-defined webhook secrets
remain in restricted stored definitions and are not returned by those projections.

`RELAY_LOAD_SEEDS=true` is the API default; exactly `false` disables loading without
deleting anything. Invalid API values fail startup with a setting-only error. Worker
ignores the setting and never seeds; scaffold does not load seeds. No automatic runtime
reload or reset endpoint exists. See [startup instructions](docs/SETUP.md).

Every bundled seed is structurally and publish-validated before any seed writes. Missing
IDs are inserted as already-published draft/publication pairs in one transaction. Existing
IDs are preserved completely—including edited drafts, changed secrets or unrelated
content with a colliding seed ID. Existing timestamps/revisions and other workflow IDs
are untouched. Repeated startup therefore adds no duplicates and does not restore user
edits. GET/list may correctly show an existing seed ID as draft after a user edit.

Concurrent loaders rely on database uniqueness; any insertion/lock race rolls back the
whole attempted batch and retries in a new transaction, up to3 attempts. Other failures
or exhausted retries fail startup, and an unsuccessful transaction inserts no partial batch.
Startup failure is a process error, not a new HTTP response code. Normal readiness only
becomes accepting after the runner finishes. Logs contain created/preserved counts only.
Seeding does not authenticate webhooks, trigger runs, enqueue jobs, call mocks/providers,
resolve D01 or change the supplied graphs. No numbered workflow versions are added.

Tests: [source/validation](backend/src/test/java/com/relay/workflow/SeedCatalogTest.java),
[retry/failure](backend/src/test/java/com/relay/api/SeedLoaderTest.java),
[startup configuration](backend/src/test/java/com/relay/bootstrap/LaunchConfigurationTest.java),
[real MySQL startup/repeat/concurrency/rollback](backend/src/test/java/com/relay/api/SeedMySqlTest.java).
Actual commands/results: [seed verification](docs/seed-loading-verification.txt).

### Phase 3 integration verification

The existing workflow routes are also exercised by Gradle `testcontainersTest` against
fresh MySQL8.4.11 containers: CRUD authentication/errors, invalid publication preserving
stored state, publication/edit concurrency, frozen snapshots and restart durability.
[SeedMySqlTest](backend/src/test/java/com/relay/api/SeedMySqlTest.java) additionally GETs a
seed definition, PUTs an edited draft, POSTs publication and verifies the published copy
survives restart. No route or response schema changes in task3.10.
See [run instructions](docs/SETUP.md#phase-3-testcontainers-verification) and
[actual verification results](docs/testcontainers-verification.txt).

## Trigger acceptance and durable execution (tasks4.1–4.14)

### POST /workflows/{workflowId}/trigger

Starts a manual run of a currently Published workflow, including a webhook-configured
workflow. Requires one valid `Authorization: Bearer <RELAY_DEMO_TOKEN>` header, as for
other management routes. `workflowId` identifies an existing workflow. No query parameters.
Use `Content-Type: application/json` and UTF-8. The body must be exactly
`{"input":{...}}`: input is an object (empty allowed), and no extra top-level fields are
accepted. Nested input is application data, not a workflow definition.

```sh
curl -i -X POST http://127.0.0.1:8080/workflows/wf_runaway/trigger \
  -H "Authorization: Bearer $RELAY_DEMO_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"order_id":"ord_2001"}}'
```

### POST /hooks/{workflowId}

Starts a webhook run. Requires exactly one nonblank `X-Relay-Secret` header matching the
frozen Published definition's trigger secret; comparisons use SHA-256 plus constant-time
byte comparison. A management token is neither required nor a substitute. The workflow
must currently be Published and configured for webhook triggering. JSON body becomes
`trigger.body` without an `input` wrapper; objects, arrays, strings, numbers, booleans and
JSON null are allowed. Missing bodies are rejected. No query parameters. The route does
not expose secrets in responses and does not add browser CORS access for secret headers.

```sh
curl -i -X POST http://127.0.0.1:8080/hooks/wf_expense_approval \
  -H "X-Relay-Secret: $WORKFLOW_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"employee_email":"dev@example.com","amount_usd":50,"description":"Supplies"}'
```

### Shared acceptance response, validation and errors

Success is `202 Accepted`, `Cache-Control: no-store`, with precisely one generated run ID:

```json
{"run_id":"run_0123456789abcdef0123456789abcdef"}
```

A short transaction locks the workflow, checks publication and (for hooks) its frozen
secret/type, copies definition/input/execution-policy snapshots, inserts a queued run and
its initial ready job, and commits before responding. Failure rolls back both rows.
Worker execution is asynchronous: `202` means durable acceptance, not node completion.
Neither trigger waits for a delay, provider, notification or approval. A separate worker
must be running to make progress. Workflow edits return the workflow to Draft and block
new triggers until republished; already accepted runs retain their original snapshots.

Every accepted call creates a different run. Inbound `Idempotency-Key` is not implemented
as trigger deduplication. Retrying a trigger after losing its response may create another
run. Outbound per-step replay keys are a separate guarantee within one accepted run.

Request limit:1MiB UTF-8 bytes, JSON nesting64, strict duplicate-key/trailing-token
rejection. Invalid UTF-8, malformed JSON, empty input or invalid manual wrapper is400;
requests beyond the byte limit are413. Input values are never executed as code.

| Status | Code / condition | Effect |
| --- | --- | --- |
| 202 | `run_id` response above | Run and initial queue job committed together |
| 400 | `invalid_input` | Bad JSON/UTF-8/manual wrapper; no run |
| 401 | `unauthorized` | Missing/invalid management token on manual route; no run |
| 401 | `invalid_webhook_secret` | Missing, duplicate, blank, overlong or wrong webhook secret; no run |
| 404 | `not_found` | Unknown workflow (hook needs a syntactically present secret first); no run |
| 409 | `workflow_not_published` | Draft workflow, including edited previously-published workflow; no run |
| 409 | `invalid_trigger` | Hook invoked for manual-only workflow; no run |
| 409 | `conflict` | Lock/integrity conflict; transaction rolled back |
| 413 | `payload_too_large` | Request over1MiB; no run |
| 415 | `unsupported_media_type` | Use application/json |
| 500 | `internal_error` | Unexpected storage failure; no partial acceptance |

Errors use the existing `{"error":{"message":"...","code":"..."}}` envelope.
For hooks, malformed/missing secret headers are rejected before parsing body; with a
present header, body validation precedes workflow lookup, publication/type checks and
secret matching. Thus a Draft gives409 and unknown workflow gives404 even with an
incorrect present secret. There is no claim that webhook existence/status is concealed.

### Deterministic execution and recovery

The worker persists numbered logical visits and separately numbered attempts. Condition
nodes select one stored edge; loops allocate new visit numbers. Template references read
trigger input or the latest earlier successful output in the same run; replacement text
is never interpreted again. Missing values fail clearly rather than becoming empty text.
Delays persist a resume timestamp and release ownership; waking consumes no extra attempt
or logical step. `max_steps` is reserved in MySQL per new visit; retries/recovery never
reset it. A final node at the cap can succeed; admission of a successor fails `max_steps`.

HTTP/notify/order adapters freeze the concrete method, destination, business headers and
canonical body before sending. Mutating calls carry `{run_id}:{sequence}`; retries reuse
that key and request, while distinct loop visits have distinct keys. Exact deployment
origin allowlists apply to every send; no redirects, user authorization/cookie/transport
headers or user Idempotency-Key are accepted. Responses are bounded to1MiB and calls have
a total deadline covering body receipt. Invalid adapter responses and permanent statuses
fail without retry; connection failures/timeouts and408/429/500/502/503/504 retry within
the persisted budget. Default3 attempts, backoff1s then2s; valid Retry-After on429/503 may
extend the durable deadline. A storage-outage recovery is not a fresh unkeyed action.

Run/job/step/attempt updates are committed together under owner/generation/lease guards.
Expired workers cannot persist outcomes. A restarted worker marks abandoned attempts
`uncertain`, schedules recovery with the original request/key and consumes the same retry
budget. This gives replay safety against the idempotency-aware mock world; arbitrary HTTP
receivers can still duplicate effects after an uncertain outcome. Operate only one active
worker process; stop the old process before starting its replacement.

Phase5 implements AI and approval execution, approval decision routes and cancellation as
documented below. Order actions require an approved human record at an earlier sequence
in the same run on every attempt; absent evidence fails `approval_required` before send.
Run-read/trace UI remains Phase6. No optional schedules, compiler or wall-clock/token caps.

Tests: [pure values](backend/src/test/java/com/relay/engine/EngineValuesTest.java),
[transport](backend/src/test/java/com/relay/engine/HttpTransportTest.java),
[real MySQL trigger/engine](backend/src/test/java/com/relay/engine/EngineMySqlTest.java),
[packaged worker crash + real mock-world replay](backend/src/test/java/com/relay/engine/CrashRecoveryMySqlTest.java).
Actual commands and results: [Phase4 verification](docs/phase4-verification.txt).

## Human approvals and cancellation — Phase5

All four routes below require `Authorization: Bearer <RELAY_DEMO_TOKEN>` and API mode.
There are no per-user roles: the authenticated shared demo token maps to `demo-operator`.
Client identity headers and AI output cannot set the actor. Management CORS rules above
apply. IDs are opaque existing identifiers, not names; missing IDs return404.
Decision/cancel POST requests accept **no body**, including no `{}` or whitespace body;
no Content-Type is required. They accept no decision, actor, or state fields and define no
query parameters. Any nonempty body returns400 without changing state.

### GET /approvals

Query: optional `status`, default `pending`; allowed exact values `pending`, `approved`,
`rejected`, `closed`. Empty status uses the default; other values return400. There is no
pagination in this contract. HTTP200 is an array ordered by creation time, then ID; an
empty result is `[]`. Pending results include only unclosed requests whose run is still
`waiting_approval`. Reading has no side effects; a listed request can change before a
subsequent decision, so callers must handle409 and refresh.

```sh
curl -H "Authorization: Bearer $RELAY_DEMO_TOKEN" 'http://localhost:8080/approvals?status=pending'
```

Example200 (IDs illustrative):
```json
[{"id":"apr_example","run_id":"run_example","workflow_id":"wf_expense_approval","step_sequence":3,"node_id":"refund_gate","message":"Review refund","status":"pending","created_at":"2026-09-28T10:00:00.000000Z","decided_by":null,"decided_at":null,"closed_at":null}]
```

Each item has string `id`, `run_id`, `workflow_id`, `node_id`, rendered `message`, and `status`, positive
integer `step_sequence`, UTC ISO8601 `created_at` (request time), nullable string `decided_by`, and nullable UTC ISO8601
`decided_at`/`closed_at`. `workflow_id` and `created_at` were added in task6.10 (additive) so the approval inbox can show workflow context and request time; tested in HumanAiMySqlTest.approvalWaitSurvivesRestartAndDecisionsAreAuthenticatedAndFinal. Pending requests have no actor or timestamps. Approved/rejected
requests retain authenticated decision evidence; closed requests have a closure timestamp
and no invented human decision. Treat rendered messages as untrusted text in the UI.

### POST /approvals/{id}/approve and POST /approvals/{id}/reject

```sh
curl -X POST -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/approvals/$APPROVAL_ID/approve"
curl -X POST -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/approvals/$APPROVAL_ID/reject"
```

HTTP200 returns `{"run_id":"run_example","status":"running"}` when approval schedules
the frozen successor. Approval at a final node instead returns `status: "succeeded"`.
Rejection returns `status: "cancelled"`, with run cancellation reason `approval_rejected`.
`run_id` and `status` are strings; no other response fields. Both decisions finish the
approval step as `succeeded`, with output `{"decision":"approved","decided_by":"demo-operator"}`
or `decision: "rejected"`. The pending approval, step result, run status and resumed queue
intent commit atomically. Resumption is asynchronous: HTTP200 does not mean downstream
work has finished. A waiting approval owns no lease and occupies no worker thread.

A decision requires a pending request, a waiting approval step and matching inactive job,
a `waiting_approval` run, and no cancellation request. Repeated identical decisions,
opposite decisions, closed requests, and stale/nonwaiting states return409. A missing
approval returns404. Decisions cannot be undone or transferred to another run. Every
sensitive action and retry checks earlier approved human evidence in its own run; pending,
rejected, closed, later-sequence and wrong-run records do not qualify. Each new approval
node visit still creates a new pending request even when earlier evidence exists.

### POST /runs/{id}/cancel

```sh
curl -X POST -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/runs/$RUN_ID/cancel"
```

HTTP200 `{"run_id":"run_example","status":"cancelled"}` means cancellation committed
immediately: queued work has no step, and ready delay/backoff/waiting-approval work is
stopped. Any pending approval closes with reason `run_cancelled`, `closed_at`, and no
`decided_by` or `decided_at`. The queue becomes inactive; no successor is scheduled.

HTTP202 `{"run_id":"run_example","status":"running"}` means a leased operation may
already be in flight. The first request's authenticated actor and DB timestamp are kept;
repeated requests while cancellation is pending return202. Renewal stops and the bounded
current call may settle. Known successful/failed attempt and step results are preserved,
but the run becomes cancelled without repair, retry or successor. If the owner disappears,
lease recovery marks the abandoned attempt uncertain and cancels without resending it.
Already completed external effects are not reversed. There is no promise of immediate
interruption, nor an extra cancellation status outside the fixed run vocabulary.

Already `succeeded`, `failed` or `cancelled` runs return409, including a repeated request
after cancellation settles. Missing runs return404. Decisions and cancellation lock the
same run first: only a valid serialized transition commits. At a final approval one wins
and the other conflicts. With an approval successor, cancellation can validly follow the
committed approval before downstream dispatch. No partial approval/resume state survives
transaction rollback. On client timeout or409, refresh current approval status before
retrying; read run status with `GET /runs/{runId}` (task6.1).

### Shared errors and verification

| Status | Code | Condition |
| --- | --- | --- |
| 400 | invalid_input | Nonempty decision/cancel body or invalid status filter |
| 401 | unauthorized | Missing, malformed, duplicate or invalid management bearer token |
| 403 | forbidden | Authenticated caller without management authority |
| 404 | not_found | Missing approval/run |
| 409 | conflict | Final/stale state, duplicate decision, conflicting decision or database concurrency conflict |
| 500 | internal_error | Unexpected server/storage failure; no raw exception or payload is returned |

Domain error example: `{"error":{"message":"The resource state conflicts with this request.","code":"conflict"}}`.
Authentication errors use the shared security envelope above. No endpoint accepts an
idempotency key for decisions: repeat decisions deliberately conflict, while repeat
pending cancellation preserves the first request metadata. State and queue changes are
transactional. Storage/network failures are not represented as successful decisions.

Tests: [HumanAiMySqlTest](backend/src/test/java/com/relay/engine/HumanAiMySqlTest.java)
(real HTTP, MySQL, restart, rollback and races),
[ManagementSecurityTest](backend/src/test/java/com/relay/security/ManagementSecurityTest.java)
(shared auth/CORS). Exact executed commands and outcomes are in
[Phase5 verification](docs/phase5-verification.txt). This does not claim a finished approvals
console or run trace API; those remain Phase6.

## Run visibility — task6.1

- **Status:** Implemented and verified (2026-09-28): list/detail/paging in task6.1; trace payload fields and redaction added to `GET /runs/{runId}` in task6.2 (additive — no 6.1 field was removed or renamed). See "Trace payload and redaction — task6.2" below.
- **Requirement/source:** Fixed `GET /runs/{runId}` (status plus `steps[]` with `node_id`/`status`; smoke test polls it); flexible run listing required by the pack's "additional flows" and console views U03/U04 ([console design](docs/CONSOLE.md)).
- **Authentication:** `Authorization: Bearer <RELAY_DEMO_TOKEN>` on both routes; missing, malformed, duplicate or wrong tokens return401 with the shared security envelope. There are no per-run permissions: the single demo operator can read every run.
- **Headers/body:** GET only; no request body or Content-Type is used. Reads have no side effects, no locks and no idempotency concerns; repeat freely.
- **Unknown or repeated query parameters** return400 `invalid_input` (for example `?page=2`, `?status=queued&status=failed`, or `limit` on the detail route). An **empty** value (`?status=`) means "not supplied", matching how forms submit cleared filters.
- **Never exposed:** webhook secrets, the frozen definition snapshot, execution policy, frozen dispatch requests and AI request bodies. Trigger input and step payloads are returned only after redaction (task6.2). Response bodies are safe DTOs, never JPA entities.

### GET /runs

Lists runs newest first, ordered by `created_at DESC, run_id DESC` (the ID breaks timestamp ties). Filtering and paging happen in the database, not on the current page.

| Query | Type / default | Rules |
| --- | --- | --- |
| `workflow_id` | string, optional | Exact match; 1–128 Unicode code points, else400. An unknown workflow returns an empty page (200), not404. |
| `status` | string, optional | Exactly one of `queued`, `running`, `waiting_approval`, `succeeded`, `failed`, `cancelled`; anything else (including case variants) →400. |
| `limit` | integer, default25 | 1–100 inclusive; decimal digits only; otherwise400. |
| `cursor` | string, optional | Opaque `next_cursor` from a previous response with the **same filters**. Malformed, non-canonical, over-long or out-of-range cursors →400. Do not construct or parse cursors. |

```sh
curl -H "Authorization: Bearer $RELAY_DEMO_TOKEN" 'http://localhost:8080/runs?workflow_id=wf_expense_approval&status=waiting_approval&limit=25'
curl -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/runs?workflow_id=wf_expense_approval&limit=25&cursor=$NEXT_CURSOR"
```

HTTP200 (IDs/times illustrative):
```json
{"runs":[{"run_id":"run_example2","workflow_id":"wf_expense_approval","status":"waiting_approval","trigger_type":"manual","current_node_id":"approve_large","steps_executed":2,"max_steps":20,"created_at":"2026-09-28T10:00:02.123456Z","started_at":"2026-09-28T10:00:02.300000Z","finished_at":null}],
 "next_cursor":"MTc1OTA1MzYwMjEyMzQ1NjpydW5fZXhhbXBsZTI"}
```

Each item has exactly these ten fields: strings `run_id`, `workflow_id`, `status`, `trigger_type` (`manual`/`webhook`); nullable string `current_node_id` (next/active node; null once finished at an end node); integer `steps_executed`; nullable integer `max_steps` read from the frozen per-run definition snapshot; UTC ISO8601 `created_at` and nullable `started_at`/`finished_at` (null means not started/not finished). `max_steps` is normally present for valid accepted runs; null is returned truthfully for legacy/corrupt snapshots that lack the limit. `next_cursor` is null on the last page — including when the final page is exactly full. No total count is provided.

**Continuation semantics:** keyset paging from the last returned row. Runs accepted after the first page are newer than the cursor, so they never appear in or shift later pages; reload the first page (no cursor) to see them. A run's status can change between page reads, so a status-filtered walk reflects each page's read time. Runs are never deleted, so pages have no gaps.

### GET /runs/{runId}

Returns one run with its logical steps ordered by `sequence`. Run fields and the step page are read in one read-only transaction, so they describe one consistent moment.

| Query | Type / default | Rules |
| --- | --- | --- |
| `steps_after` | integer, default0 | Return steps with `sequence` greater than this; 0–18 decimal digits, otherwise400. |
| `steps_limit` | integer, default500 | 1–500 inclusive, otherwise400. |

Because `limits.max_steps` may be as large as 2147483647, steps are paged rather than silently truncated. Runs within the default page (500 steps, which covers every seed workflow) are complete in one response. When `steps_next_after` is non-null, request again with `steps_after=<that value>` until it is null.

```sh
curl -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/runs/$RUN_ID"
curl -H "Authorization: Bearer $RELAY_DEMO_TOKEN" "http://localhost:8080/runs/$RUN_ID?steps_after=500&steps_limit=500"
```

HTTP200 for a run stopped by the step cap (IDs/times illustrative):
```json
{"run_id":"run_example","workflow_id":"wf_runaway","status":"failed","trigger_type":"manual","current_node_id":"b",
 "steps_executed":5,"max_steps":5,"created_at":"2026-09-28T10:00:00.000001Z","started_at":"2026-09-28T10:00:00.100000Z","finished_at":"2026-09-28T10:00:01.200000Z",
 "error":{"code":"max_steps","node_id":"b"},"cancel_requested_at":null,"cancel_requested_by":null,"cancellation_reason":null,
 "steps":[{"sequence":1,"node_id":"a","node_type":"delay","status":"succeeded","wait_reason":null,"attempt_count":1,"selected_next_node_id":"b","resume_at":"2026-09-28T10:00:00.100001Z","started_at":"2026-09-28T10:00:00.100000Z","finished_at":"2026-09-28T10:00:00.150000Z","duration_ms":50}],
 "steps_next_after":null}
```

Run fields: the ten list fields (including `max_steps` from the frozen snapshot, so later workflow edits do not change it) plus nullable `error` object `{code, node_id}` for engine-terminated runs (for example `max_steps`, and the node that could not be admitted); `cancel_requested_at`, `cancel_requested_by` (`demo-operator`) and `cancellation_reason` (`operator_cancelled` or `approval_rejected`), all null when not cancelled; `steps` and `steps_next_after`.

Step fields: `sequence` (positive, gap-free per run; loop visits get new sequences), `node_id`, `node_type`, `status` (`running`, `waiting`, `succeeded`, `failed`, `cancelled`), nullable `wait_reason` (`delay`, `retry`, `approval`; only while waiting), `attempt_count` (prepared handler invocations for this visit, including retries, schema repair and crash recovery; every executed node has at least1, a delay wake-up or approval decision does not add one, and0 only means the first invocation has not been prepared), nullable `selected_next_node_id` (the branch actually taken; null at an end node or when unfinished), nullable `resume_at`, `started_at`, `finished_at`, `duration_ms`. A queued run returns `"steps":[]`.

Polling: the run is asynchronous after trigger acceptance; poll this route until `status` is `succeeded`, `failed` or `cancelled`. `waiting_approval` does not change until a human decision or cancellation.

### Trace payload and redaction — task6.2

`GET /runs/{runId}` additionally returns (all nullable unless stated):

| Level | Field | Meaning |
| --- | --- | --- |
| run | `workflow_name`, `entry` | From the run's frozen snapshot, so later draft edits never change the trace's labels. |
| run | `input` | Trigger input (manual `input` object or webhook body), redacted. |
| run | `ai_tokens_used`, `ai_usage_complete` (boolean) | Known prompt+completion total across AI attempts; `false` means some usage is unknown (e.g. a crashed call). Null is "unknown", not zero. |
| step | `resolved_input` | Parameters after template resolution, redacted. Null = not resolved yet. |
| step | `output` | Successful output (redacted); approval steps show `{"decision","decided_by"}`. |
| step | `error` | Engine error object, e.g. `{"code":"http_503","cause":"transport_retry","retry_number":1,"delay_ms":1000}` or `{"code":"max_steps","node_id":"b","prepared_attempts":0}`. |
| step | `idempotency_key` | Engine-generated `run_id:sequence` key for non-GET external actions; stable across retries, new per loop visit. |
| step | `ai_repair_count` (0–1), `tokens_prompt`, `tokens_completion`, `ai_usage_complete` (boolean) | AI schema-repair use and per-step usage. |
| step | `retry_due_at` | When `wait_reason` is `retry`, the scheduled next attempt time. |
| step | `approval` | Object `{id,status,message,decided_by,decided_at,closed_at,close_reason}` for approval steps, else null. `closed` with `close_reason` `run_cancelled` means cancellation closed it — not a rejection. |
| step | `attempts[]` | Ordered by `attempt_no`: `status` (`running`, `succeeded`, `failed`, `uncertain` = remote outcome unknown), `cause` (`initial`, `transport_retry`, `schema_repair`, `recovery`), redacted `error`/`output`, `provider`/`model` (AI only), `tokens_prompt`/`tokens_completion`, `started_at`, `finished_at`, `duration_ms` (null while running/uncertain). Invalid AI text is not retained, so a failed schema attempt has an error code and null output. |

Example step (illustrative):
```json
{"sequence":1,"node_id":"call","node_type":"http_request","status":"waiting","wait_reason":"retry","attempt_count":1,
 "resolved_input":{"method":"POST","url":"http://127.0.0.1:9210/x?api_key=[REDACTED]&ok=1","headers":{"X-Api-Key":"[REDACTED]","X-Trace":"visible"},"body":{"note":"echo [REDACTED]"}},
 "output":null,"error":{"code":"http_503","cause":"transport_retry","retry_number":1,"delay_ms":1000},
 "idempotency_key":"run_example:1","ai_repair_count":0,"tokens_prompt":null,"tokens_completion":null,"ai_usage_complete":true,
 "retry_due_at":"2026-09-28T10:00:01.000000Z","approval":null,
 "attempts":[{"attempt_no":1,"status":"failed","cause":"initial","error":{"code":"http_503","cause":"transport_retry","retry_number":1,"delay_ms":1000},"output":null,"provider":null,"model":null,"tokens_prompt":null,"tokens_completion":null,"started_at":"2026-09-28T10:00:00.000100Z","finished_at":"2026-09-28T10:00:00.020000Z","duration_ms":19}]}
```

**Redaction rules** (applied to `input`, `resolved_input`, `output`, `error`, attempt `error`/`output` and approval `message`; the stored data is unchanged):
1. Any object key whose lowercase, letters-and-digits-only form contains `authorization`, `secret`, `token`, `password`, `passwd`, `apikey`, `cookie`, `credential`, `privatekey` or `signature` has its whole value replaced by `"[REDACTED]"` at any depth (e.g. `X-Api-Key`, `client_secret`, `session_token`). This deliberately over-redacts user keys such as `token_count`.
2. The run's webhook secret, when at least 8 characters, is replaced by `[REDACTED]` wherever it appears inside strings (e.g. if a caller echoes it into the body). Shorter secrets are only protected by rule 1 and by never returning the snapshot.
3. Absolute `http(s)` URL strings lose user-info and have sensitive query-parameter values (same name rule) replaced.
The engine already rejects workflow `Authorization`/`Cookie` headers, and AI provider credentials are never stored in traces.

Limits: a step page (≤500 steps) returns all attempts for those steps; attempts per step are bounded by the retry budget. Large outputs (up to the engine's payload limit) are returned in full; use smaller `steps_limit` pages for very large traces.

| 6.2 case | Input / action | Expected | Test | Actual (2026-09-28) |
| --- | --- | --- | --- | --- |
| HTTP retry trace | `http_request` with `X-Api-Key` header, `api_key` query, secret echoed in webhook body; 503 retryable outcome | Header/query/secret redacted; `X-Trace` visible; waiting `retry` with `retry_due_at`; failed attempt with `http_503`; idempotency key `run:1`; no dispatch request | RunQueryMySqlTest.traceIncludesRedactedPayloadAttemptsUsageAndApprovalEvidence | Pass |
| AI + approval | Mock-http AI outcome with 7+3 tokens and a `session_token` field, then approval approved | Output redacted field; step/attempt tokens, provider/model; run total 10 complete; approval evidence with decider/time and rendered message | same | Pass |
| Queued run | Fresh run with sensitive input key | Input shown redacted, zero tokens, no steps | same | Pass |
| Loop steps | 6.1 loop | Every step has one attempt, resolved input and output (`{}` delay, `{"result":false}` condition) | detailReturnsCompleteOrderedStepsAcrossPages | Pass |
| Redaction unit rules | Key variants, non-sensitive keys, nesting, arrays, immutability, secret substrings, short secrets, URLs, malformed escapes | As rules above | [TraceRedactorTest](backend/src/test/java/com/relay/api/TraceRedactorTest.java) | Pass |

### Errors, edge cases and tests

| Status | Code | Condition |
| --- | --- | --- |
| 400 | invalid_input | Invalid/unknown/repeated query parameter, bad limit, status or cursor. Validated before lookup, so a bad parameter on a missing run is400. |
| 401 | unauthorized | Missing/invalid management bearer token |
| 404 | not_found | No run with this ID (including IDs longer than 128 code points) |
| 500 | internal_error | Unexpected storage failure; no raw exception text is returned |

| Case | Input / action | Expected result | Test | Actual (2026-09-28) |
| --- | --- | --- | --- | --- |
| Authentication | No token, wrong token, Basic scheme on both routes | 401 | RunQueryMySqlTest.routesRequireManagementAuthentication | Pass |
| Ordering and ties | Five runs across two workflows, two forced to identical `created_at` | Matches `created_at DESC, run_id DESC` exactly | listFiltersAndPagesStablyServerSide | Pass |
| Filters | workflow only; workflow+status (cancelled, queued); unknown workflow; empty status | Exact subsets; unknown → `{"runs":[],"next_cursor":null}`; empty = unfiltered | same | Pass |
| Frozen cap in list | Valid snapshot and a legacy/corrupt snapshot without `limits.max_steps` | `max_steps` is the frozen cap or null; all existing fields unchanged | same; RunQueryTest serialization | Pending task6.13 verification |
| Paging | limit1 walk; limit equal to count | Complete, disjoint, ordered; exact-fit page has null cursor | same | Pass |
| Concurrent insert | New run accepted between page 1 and page 2 | Page 2 continues without duplicate/shift; new run appears on a fresh first page | same | Pass |
| Invalid list input | limit 0/101/x, status `pending`, repeated status, bad/garbage/far-future cursor, unknown param, 129-char workflow | 400 `invalid_input` | same + RunQueryTest | Pass |
| Queued detail | Run not yet claimed | `steps: []`, `steps_next_after: null`, `max_steps` from snapshot, `current_node_id` = entry | detailReturnsCompleteOrderedStepsAcrossPages | Pass |
| Loop + cap | delay→condition loop with `max_steps` 5 | `failed`, `error.code` `max_steps`, steps a,b,a,b,a with sequences 1–5 and chosen branch | same | Pass |
| Step paging | `steps_limit=2` walk; `steps_after=5`; `steps_limit=5` | Every step exactly once; empty after end; exact fit has null continuation | same | Pass |
| Waiting/cancelled | Approval run, then cancel | `waiting`/`approval` step with null `finished_at`; after cancel: run and step `cancelled`, requester `demo-operator`, reason `operator_cancelled` | same | Pass |
| Missing run | Unknown ID; 129-character ID | 404 `not_found` | same | Pass |
| Invalid detail input | steps_limit 0/501, steps_after -1/x, `limit` on detail; bad param on missing run | 400 `invalid_input` | same + RunQueryTest | Pass |
| No secret/snapshot leakage | Webhook secret and sentinel input in every tested response | Not present; no `definition_snapshot`/`execution_policy` keys | every `ok()` call | Pass |

Tests: [RunQueryMySqlTest](backend/src/test/java/com/relay/api/RunQueryMySqlTest.java) (real HTTP, MySQL Testcontainers) and [RunQueryTest](backend/src/test/java/com/relay/api/RunQueryTest.java) (cursor codec and parameter boundaries). Commands and outcomes: [Phase6 verification](docs/phase6-verification.txt). Queries use existing indexes `ix_runs_created`, `ix_runs_workflow`, `ix_runs_status` and the steps primary key; no schema change.

## AI execution — Phase5, worker-only

No provider key or provider invocation route is exposed to the browser. `AiProvider`
prepares a frozen provider/model/schema/prompt request; authorization is attached only in
server memory at dispatch and is absent from persisted requests. The rendered prompt is
untrusted input, separated from fixed provider instructions. No tool calling is enabled;
model fields cannot create approval records, change workflow edges or authorize actions.

Supported modes: `openrouter` (fixed HTTPS chat-completions endpoint and provider-prefixed model), `mock-http` (supplied localhost chat-completions protocol, default model
`alpha-small`) and `openai` (fixed HTTPS Responses endpoint, explicitly configured model).
Changing deployment provider/origin blocks an existing frozen request rather than redirecting
it; a model change does not rewrite an already prepared request. See [setup](docs/SETUP.md#phase5-ai-and-human-control).

Successful provider text must be one strict JSON value, without prose/fences/duplicate keys
or trailing content, and must match the frozen local Draft2020-12 output schema before
being stored as successful step output. First invalid JSON/schema output schedules exactly
one persisted corrective request; second invalid output fails `invalid_ai_json` or
`invalid_ai_schema`. Transport retries and uncertain recovery share the existing per-step
budget; schema repair adds at most one invocation, for default maximum4 attempts. Restarts
preserve both budgets and the exact repair request. No hidden model escalation or fallback.
The unchanged supplied mock returns prose and therefore fails schema validation after one
repair; successful branch tests use clearly identified controlled JSON fixtures.

Each attempt stores provider/model, DB timestamps/duration and reported prompt/completion
usage. Known usage is aggregated once across all attempts, including failed validation.
Missing, uncertain or unrepresentable totals remain nullable with `ai_usage_complete=false`;
missing usage is never reported as zero-cost. The real provider probe is explicit and uses
only a synthetic prompt; ordinary tests never make billable calls. Timeouts cover the full
response with a1MiB limit; provider error bodies are not exposed. Transport classification
matches the Phase4 policy above, including Retry-After and no redirect following.

User clarification2026-09-28 resolves D01: preserve the supplied graph; only a
`refund_request` classification selects approval, while `complaint`/`question` selects
notification. Both supplied injection payloads are tested with all three controlled
classifications. This verifies engine invariants, not a guarantee about a real model's
classification or generated summary. No unapproved sensitive action can dispatch.

### Task5.9 billing error correction (2026-09-28)

OpenAI AI calls distinguish permanent billing/quota429 from temporary rate-limit429.
Only these fixed provider codes are retained, prefixed `ai_`: `credit_balance_exhausted`,
`organization_spend_limit_exceeded`, `project_spend_limit_exceeded`,
`organization_usage_limit_exceeded`, and `insufficient_quota`. A remaining
`error.type=insufficient_quota` maps to `ai_insufficient_quota`. These outcomes fail the
step/run without retry or schema repair; the job is inactive and unavailable usage remains
explicitly incomplete. Provider messages, raw error bodies and arbitrary codes are never
persisted or exposed. Temporary/unknown/malformed429 retains `http_429`, bounded retries
and Retry-After. Non-AI and mock-provider behavior is unchanged. No route changes.

Regression coverage: HttpTransportTest (real loopback HTTP, sanitized errors, malformed/
unknown errors, retry distinction and provider isolation); HumanAiMySqlTest (one attempt,
failed run, inactive queue and no repair). Exact results are in Phase5 evidence. Correcting
error reporting does not restore an exhausted provider balance or complete live inference.
Source: [official OpenAI error codes](https://developers.openai.com/api/docs/guides/error-codes).

### OpenRouter provider — user-authorized task5.9 amendment

Set `RELAY_AI_MODE=openrouter`, `RELAY_AI_MODEL=openai/gpt-5-mini` (or an explicitly
selected available provider/model), and a server-only `RELAY_AI_API_KEY` issued by
OpenRouter. Requests go only to `https://openrouter.ai/api/v1/chat/completions`.
`RELAY_AI_BASE_URL` cannot redirect real-provider credentials. Direct OpenAI and mock
modes remain supported. Recognizable cross-provider key mismatches fail before dispatch
with `ai_credential_provider_mismatch`; missing OpenRouter model prefix fails with
`ai_model_prefix_required`. Keys are validated again at dispatch and never persisted.

The frozen request contains system instructions/schema and an untrusted user message,
`model` and `stream=false`; no tools, plugins or model fallback list is sent. OpenRouter
may route internally; Relay's attempt count tracks Relay invocations, not gateway-internal
work. Local strict JSON/schema checks and one persisted repair remain authoritative. The
chat response must have one completed choice with textual content and no refusal, tool
calls or top-level error. Null/empty tool-call fields mean no call. Prompt/completion
usage is aggregated with the existing completeness rules; the requested prefixed model
and provider=openrouter are persisted as attempt metadata.

OpenRouter HTTP402 becomes nonretryable `ai_payment_required`; HTTP401/403 remain
permanent access failures and temporary429 retains bounded retries/Retry-After. Body
content is never exposed. Successful HTTP200 with an error envelope fails safely as
`invalid_provider_response`. Switching the deployment provider never replays an existing
frozen request at the new provider; start a new run after switching.
No public route or node-catalog/seed changes. See
[OpenRouter chat protocol](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion),
[setup](docs/SETUP.md) and [verification](docs/phase5-verification.txt).
