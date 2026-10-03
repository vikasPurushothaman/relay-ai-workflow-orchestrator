# AI provider configuration

Task 8.4, 2026-09-28. How to run Relay's AI nodes against the **mock provider** (default, no credentials) or a **real provider** (OpenRouter or OpenAI), and how to keep credentials out of the repository. This page supersedes the dated Phase 5 notes in [SETUP.md](SETUP.md), which remain as history.

## At a glance

| `RELAY_AI_MODE` | Endpoint | Model | Key | Verification status |
| --- | --- | --- | --- | --- |
| `mock-http` (default) | `RELAY_AI_BASE_URL` + `/v1/chat/completions`, default `http://localhost:9001` | `alpha-small` if blank | not needed (a local mock token is sent) | Verified: transport, auth, usage, 429/503/timeout and malformed output (7.5) |
| `openrouter` | fixed `https://openrouter.ai/api/v1/chat/completions` | required, provider-prefixed, e.g. `openai/gpt-5-mini` | required, starts with `sk-or-v1-` | **Live-verified** with `openai/gpt-5-mini`: live probe (5.9), seed and injection scenarios (7.2, 7.6) |
| `openai` | fixed `https://api.openai.com/v1/responses` | required, no default | required, must not start with `sk-or-v1-` | Adapter implemented and unit-tested; **live generation not verified** (the account returned `credit_balance_exhausted` in 5.9) |

Only the **worker** reads these settings; it calls the provider. The API never calls a provider and needs no AI settings. The AI timeout (`RELAY_AI_TIMEOUT_MS`, default 30000) is frozen into each run by the API when it accepts the run, so set it the same in both processes if you change it.

Only one AI node exists in the seeds: `classify` in `wf_support_triage`. Every other seed runs identically in all three modes.

## Mock provider (default)

No configuration is needed. Start the supplied mock and a worker with no `RELAY_AI_*` variables:

```sh
python3 scripts/run_mock.py provider
```

| Setting | Mock-mode rule |
| --- | --- |
| `RELAY_AI_MODE` | unset or `mock-http` |
| `RELAY_AI_BASE_URL` | an `http` origin on `localhost`, `127.0.0.1` or `[::1]`, no path, credentials or query; anything else fails the step with `invalid_mock_provider_url`. Use `run_mock.py provider --port N` and set this to match. |
| `RELAY_AI_MODEL` | blank → `alpha-small`; the mock also accepts `alpha-large` |
| `RELAY_AI_API_KEY` | leave blank; Relay sends a fixed local mock token. Never put a real key here in mock mode |

**What the mock can show:** the request/response path, authentication, token usage, provider outages, rate limits (`429` with `Retry-After: 5`), slow responses and timeouts, via its `/admin/config` controls ([MOCKS.md](MOCKS.md)).

**What it cannot show:** a successful classification. The supplied mock replies in prose, so a schema-constrained AI node gets one repair request and then fails with `invalid_ai_json`; the trace shows both attempts. This is expected. Successful AI branching is covered by canned JSON fixtures in the automated tests, and live with a real provider.

## Real provider

### 1. Create the private credential file (once)

From the repository root, in your own terminal:

```sh
python3 scripts/configure_ai.py --provider openrouter
```

For direct OpenAI use `--provider openai`. The helper asks for the model ID and then the key at a hidden prompt, validates both (format, provider/key match, OpenRouter model prefix), and writes `backend/.env.ai` with owner-only permissions (0600). It contains exactly `RELAY_AI_MODE`, `RELAY_AI_MODEL` and `RELAY_AI_API_KEY`. It never prints the key, and it **refuses to overwrite** an existing file: to change a setting, edit that file privately.

### 2. Start the worker with it

Stop the running worker first; keep one worker. In the worker terminal, export the database settings from the README as usual, then:

```sh
set -a
source backend/.env.ai
set +a
RELAY_MODE=worker java -jar backend/build/libs/relay-backend-0.1.0.jar
```

Only source a file you created yourself. Nothing else is needed: the endpoint is fixed per mode, and `RELAY_AI_BASE_URL` is ignored, so a real key can never be sent to a configurable address.

### 3. Optional live probe (billable)

```sh
python3 scripts/verify_live_ai.py
```

This sends **one** synthetic request (plus at most one schema repair) through the production adapter and passes only if the provider returns schema-valid JSON with token usage. It reads only the three allowed fields from `backend/.env.ai`, never evaluates the file as shell, drops inherited `RELAY_*` settings, and runs the opt-in Gradle task `liveAiTest` (`--offline`, so build the backend once first). Normal `test` and `testcontainersTest` never call a real provider. The call may be billed.

### What a real provider adds

The `wf_support_triage` classifier returns schema-valid JSON, so runs branch on it: `refund_request` pauses at the approval gate; `complaint` and `question` notify support. Safety does not depend on the model: output is validated against the node's JSON Schema, a second invalid answer fails the step, approval gates and step caps are enforced by the engine, and trigger payloads are passed as untrusted data. See the injection results in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

## Credential handling

- The key lives only in `backend/.env.ai`, which is ignored by git (`.gitignore` rule `.env.*`; only `*.env.example` files are tracked). Check before any commit: `git check-ignore -v backend/.env.ai` must name that rule, and `git status` must not list the file.
- Load it only into the worker's environment. Never put a key in `VITE_*` variables (they are public browser configuration), in `.env.example` files, in source code, in workflow definitions or headers, in shared terminal output, or in chat.
- The key is attached server-side at send time. It is not stored in the database, request snapshots or traces; traces show provider and model only. Error messages from providers are reduced to fixed codes and never echo the key or the raw body.
- To rotate or revoke a key, edit or delete `backend/.env.ai` and restart the worker. Revoke a leaked key at the provider immediately.

## Switching providers

1. Stop the worker.
2. Change the environment: source a different credential file, or unset the `RELAY_AI_*` variables to return to the mock.
3. Start one worker and trigger **new** runs.

A run whose AI request was already prepared under the previous provider is never redirected to the new one: when it is retried or recovered it fails with `ai_provider_configuration_changed`. Start a new run instead.

## Error codes

AI steps fail with one of these codes in the run trace. None of them print or store the key.

| Code | Meaning | What to do |
| --- | --- | --- |
| `invalid_ai_json` | Output was not valid JSON for the node's schema, twice (first answer + one repair) | Expected with the mock; with a real model, check the prompt or model |
| `invalid_provider_response` | The provider's response envelope was not a single plain answer (refusal, tool call, wrong shape) | Check the model; retrying the run may help |
| `invalid_ai_mode` | `RELAY_AI_MODE` is not `mock-http`, `openai` or `openrouter` | Fix the variable, restart the worker |
| `invalid_mock_provider_url` | `RELAY_AI_BASE_URL` is not a loopback `http` origin | Fix the variable, restart the worker |
| `ai_model_required` | Real mode without `RELAY_AI_MODEL`, or a model over 200 characters | Set the model |
| `ai_model_prefix_required` | OpenRouter model without a provider prefix | Use e.g. `openai/gpt-5-mini` |
| `ai_credential_missing_or_invalid` | Real mode without a key, or a key with invalid characters | Recreate `backend/.env.ai` |
| `ai_credential_provider_mismatch` | An OpenRouter key in `openai` mode, or the reverse | Match mode and key |
| `ai_payment_required` | OpenRouter returned 402 | Add credit or raise the key's limit at OpenRouter |
| `ai_credit_balance_exhausted`, `ai_insufficient_quota`, `ai_organization_spend_limit_exceeded`, `ai_project_spend_limit_exceeded`, `ai_organization_usage_limit_exceeded` | OpenAI billing or quota refusal (a 429 that retrying cannot fix) | Fix billing or limits in the OpenAI account; not retried |
| `http_401`, `http_403` | Key rejected or not allowed | Check the key and its access |
| `http_404` | Model or endpoint not found for this account | Check the model ID |
| `http_429`, `http_503`, `http_timeout` | Temporary rate limit, outage or timeout | Retried automatically (3 attempts, honouring `Retry-After`); the run fails `retry_exhausted` if all fail |
| `ai_provider_configuration_changed` | Provider or model changed after the request was prepared | Start a new run |
| `ai_request_too_large` | Prompt plus schema exceed 1 MiB | Shorten the input |

## Limitations

- Direct OpenAI has not produced a successful live answer in this project; only its request/response handling is unit-tested.
- The mock cannot demonstrate AI success; real AI results are single samples, not an accuracy measurement.
- A provider retry after a timeout or lost response may be processed and billed twice; usage that may have been lost is marked incomplete rather than guessed.
- One active worker; provider settings are per worker process, not per workflow.

Related: [README](../README.md), [SETUP.md](SETUP.md) (history of the provider choice), [MOCKS.md](MOCKS.md), [RECOVERY.md](RECOVERY.md) (idempotency assumptions), [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).
