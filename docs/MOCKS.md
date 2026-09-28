# Local capstone mocks and fixtures

Task 2.8 integrates the unchanged Airtribe Relay capstone pack already stored at
[source snapshot](source-review/pack/README.md). Its commit is
`fe30f4adc2e30ae3b6175ab363a20019f8944da1`; all 16 hashes and the original archive
source are retained in [provenance](source-review/pack-provenance.json). No LICENSE,
COPYING or explicit license grant was present. Attribution and source text are
preserved; no redistribution permission is inferred and nothing is published.
There is one canonical fixture copy, not a fork of the pack.

## Start and stop

Requires Python 3.9+ standard library only. From the project root, in two terminals:

```sh
python3 scripts/run_mock.py world
```

```sh
python3 scripts/run_mock.py provider
```

The project launcher verifies every pinned file before importing the unchanged
handler. World binds 127.0.0.1:9210; provider binds 127.0.0.1:9001 and serves
alpha-small/alpha-large. Unlike the upstream command-line entry points, the wrapper
binds only loopback. Press Ctrl-C to stop each process. An occupied port fails
without stopping its owner. `--port` accepts 0–65535; zero selects a free test port
and prints the actual address. Keep standard ports for the unchanged seed URLs.

```sh
curl --max-time 5 http://127.0.0.1:9210/health
curl --max-time 5 http://127.0.0.1:9001/health
```

Expected 200: world `{"status":"ok","service":"mock-world"}` and provider
`{"status":"ok","name":"alpha"}`. Health remains UP during injected failures;
it reports mock process reachability, not successful operation or Relay readiness.
No mocks are started automatically with Java, MySQL or frontend.

## Fixture and verification inventory

| Canonical local path under docs/source-review/pack/ | Use |
| --- | --- |
| data/node_catalog.json | Seven node types and trigger definitions; optional triggers remain outside roadmap |
| data/seed_workflows.json | Four original workflows; unchanged node URLs, definitions and demo secrets |
| data/sample_payloads.jsonl | Eight supplied inputs, including two injection cases |
| data/nl_eval.jsonl | Fifteen optional compiler cases, retained for provenance only |
| scripts/validate_pack.py | Offline structural fixture validation |
| scripts/smoke_test.py | Future live Relay API/workflow acceptance |
| scripts/duplication_check.py | Ledger duplicate detection after a controlled engine crash drill |

Run now:

```sh
python3 docs/source-review/pack/scripts/validate_pack.py
python3 scripts/check_local_mocks.py
```

The project checker starts only owned ephemeral loopback processes and stops them
on completion. It validates integrity (including rejected source drift), CLI inputs,
all world side-effect paths, replay, validation/business errors, failure/reset,
ledger filtering, positive/negative duplicate detection, fresh-process reset, and
provider authentication/model/body/prose/usage/failure recovery. It does not touch
standard-port user mocks. Actual results: [verification](mock-services-verification.txt).

Future commands, after API/worker, schema, adapters and fixtures are implemented:

```sh
python3 docs/source-review/pack/scripts/smoke_test.py --url http://localhost:8080 --token '<local-demo-token>'
python3 docs/source-review/pack/scripts/duplication_check.py --url http://localhost:9210
```

These scripts' help/fixture parsing work now; full Relay smoke/crash acceptance
has NOT run. Use the [evaluation guide](source-review/pack/docs/EVALUATION_GUIDE.md)
for the drill. The duplicate checker can exit0 for an empty ledger and merely warns
about missing idempotency keys: require expected executed actions and a completed
run in addition to its exit status. Deliberately repeated identical business payloads
can also be flagged. D01 injection-branch ambiguity remains unresolved.

## Local mock HTTP reference

These are supplied test-double endpoints, separate from the Relay platform API.
They need no Relay management token and create no real email/order/provider action.
Responses are JSON; world errors use `{"error":{"message":"..."}}`; provider
errors add `type` inside error. Mock-generated IDs/timestamps vary by request.
No pagination beyond world ledger `since` filtering. No persistent state or queue.
Full implementation contracts: [world source](source-review/pack/scripts/mock_world.py)
and [provider source](source-review/pack/scripts/mock_provider.py).

| Method/path | Input and behavior |
| --- | --- |
| GET /health (both) | Public; 200 process status, no mutation |
| GET /admin/config (both) | Public; 200 mode/fail_rate/latency_ms configuration |
| POST /admin/config (both) | Public JSON object; optional mode, fail_rate, latency_ms; 200 resulting configuration; affects subsequent business calls, not health/admin |
| GET /admin/ledger?since=N (world) | Public; entries with seq>N, default0; 200 `{entries:[],count:0}`; noninteger since400 |
| POST /admin/reset (world) | Public JSON `{}`; 200 `{"status":"reset"}`; clears ledger/cache, restores orders and healthy config |
| GET /orders/{id} (world) | Seeded ord_2001/ord_2002/ord_2003; 200 order object or404; ord_2001 remains processing until explicitly changed by an action |
| POST /email/send | Nonempty to/message, optional subject; 200 delivered=true, notification_id |
| POST /chat/message | Nonempty channel/message; 200 delivered=true, notification_id |
| POST /shipments | Required order_id; 201 shipment_id/order_id/status=created; missing400, unknown404 |
| POST /orders/{id}/refund | amount_usd optional, defaults order total; positive numeric <=total; 200 status/ref/reference amount; invalid400, unknown404, already refunded409 |
| POST /orders/{id}/replacement | JSON `{}`; 201 status= replacement_created/reference_id/order_id; unknown404 |
| POST /v1/chat/completions (provider) | Any nonempty Bearer header; model alpha-small or alpha-large and nonempty messages list; 200 OpenAI-style choices and usage; missing auth401, unknown model404, empty messages400 |

All world side effects accept optional `Idempotency-Key`. Sequential successful
replays return the original body/status plus `x-mockworld-replayed: true`; ledger
records each call with replayed/status/key/action/payload/seq/ts. Missing keys execute
every time; failures are not cached. Keys are global across action paths and payloads,
so callers must assign a distinct stable key per logical action. The vendor's lookup,
execution and cache write use separate critical sections: concurrent same-key calls
are not guaranteed atomic. Do not treat this mock as proof of engine concurrency safety.

Malformed JSON returns400 and unknown normal paths404; missing email/chat fields400.
The supplied handlers do not comprehensively validate JSON types or admin values;
non-object bodies and invalid config may terminate a request. Use documented object
shapes; no production validation/security guarantees are asserted. Admin endpoints
are unauthenticated. World business calls support injected down503 or fail_rate500;
provider also supports rate_limited429 with Retry-After:5. Set fail_rate=1 for a
deterministic500. latency_ms delays business responses; clients must set their own
timeouts. A timeout does not undo a side effect. No automatic server retries.
Restart clears all world data/cache/ledger and both mocks' failure configuration.

Example isolated manual side effect (only against your development ledger):

```sh
curl --max-time 5 -i http://127.0.0.1:9210/email/send \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: manual-demo-1' \
  -d '{"to":"demo@example.com","message":"Local test"}'
```

Repeat to see the replay header. Read `/admin/ledger` before/after. Reset only when
intentionally beginning a fresh drill; `/admin/reset` erases evidence for that process.

Provider example (dummy token; never use a real provider credential):

```sh
curl --max-time 5 http://127.0.0.1:9001/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer local-mock-only' \
  -d '{"model":"alpha-small","messages":[{"role":"user","content":"hello"}]}'
```

Completion content is deterministic prose, not schema-valid workflow JSON. Usage
counts whitespace tokens approximately. The provider's stream=true SSE and small-model
[refuse] behavior are preserved upstream utilities, not Relay product features or
validated by this task. Use canned JSON tests for successful schema cases in5.7/5.8;
real-provider integration remains5.9. Backend adapter/config wiring remains later work.

For either mock, inject and restore a failure explicitly:

```sh
curl --max-time 5 http://127.0.0.1:9001/admin/config -H 'Content-Type: application/json' -d '{"mode":"down"}'
curl --max-time 5 http://127.0.0.1:9001/admin/config -H 'Content-Type: application/json' -d '{"mode":"ok","fail_rate":0,"latency_ms":0}'
```

Use9210 for world configuration. Do not reset or reconfigure a mock being used by
another test/run. A mock restart during an engine crash drill loses remote evidence;
restart only the worker for that drill.
