# Console demo: start runs by API, follow them in the console

Task 6.7 (2026-09-28). The console is **read-and-operate**: it shows workflows, runs, traces and approvals, and lets an operator approve, reject or cancel. Creating, editing, publishing and **starting** runs are done through the API; there is deliberately no trigger button. Commands below were executed against a live stack on 2026-09-28 (results in [Phase6 verification](phase6-verification.txt)).

## 1. Start the stack

Follow [SETUP.md](SETUP.md): MySQL (Compose), then the API (`RELAY_MODE=api`, it migrates and loads the four seed workflows as Published), the worker (`RELAY_MODE=worker`), and the supplied mock world (`python3 scripts/run_mock.py world`). For AI nodes use either the supplied mock provider (`python3 scripts/run_mock.py provider`, `RELAY_AI_MODE=mock-http`) or the configured real provider. Then the console:

```sh
cd frontend && npm run dev        # http://127.0.0.1:5173/console/workflows
```

Open the console, enter your `RELAY_DEMO_TOKEN` in **API access** and press Enter. The token stays in memory only; reloading asks again.

## 2. Start runs (API)

```sh
export API=http://localhost:8080          # your API origin
export RELAY_DEMO_TOKEN=...               # the same token the API was started with
# Webhook secrets are fixture values in docs/source-review/pack/data/seed_workflows.json
# (trigger.secret of each workflow). Read them from there; do not paste them into shared docs.
export EXPENSE_SECRET=...                 # wf_expense_approval trigger.secret
export TRIAGE_SECRET=...                  # wf_support_triage trigger.secret
```

| Scenario | Command | Expected result (verified) |
| --- | --- | --- |
| Large expense → paused approval | `curl -X POST "$API/hooks/wf_expense_approval" -H "Content-Type: application/json" -H "X-Relay-Secret: $EXPENSE_SECRET" -d '{"employee_email":"dev1@example.com","amount_usd":250,"description":"Conference ticket"}'` | `202 {"run_id":"run_…"}`; run becomes **Waiting for approval** at `finance_gate` |
| Small expense → automatic branch | same with `{"employee_email":"dev2@example.com","amount_usd":40,"description":"Team lunch"}` | Succeeds via `auto_ok`, no human step |
| Wrong webhook secret | same with `-H "X-Relay-Secret: wrong"` | `401 invalid_webhook_secret`, no run created |
| Runaway loop → step cap | `curl -X POST "$API/workflows/wf_runaway/trigger" -H "Authorization: Bearer $RELAY_DEMO_TOKEN" -H "Content-Type: application/json" -d '{"input":{}}'` | Fails with **Step cap reached (max_steps)**, 12 of 12 steps |
| Durable delay (cancel demo) | `curl -X POST "$API/workflows/wf_slow_fulfillment/trigger" -H "Authorization: Bearer $RELAY_DEMO_TOKEN" -H "Content-Type: application/json" -d '{"input":{"order_id":"ord_2003","customer_email":"lena@example.com"}}'` | Waits ~20 s in `pack_delay`; cancel it from the console before it resumes |
| Support triage (AI → branch) | `curl -X POST "$API/hooks/wf_support_triage" -H "Content-Type: application/json" -H "X-Relay-Secret: $TRIAGE_SECRET" -d '{"order_id":"ord_2002","customer_email":"arjun@example.com","message":"The bluetooth speaker stopped working after two days. I have tried resetting it. I want my money back please."}'` | With the real provider: classifies `refund_request` and pauses at `refund_gate`. With the supplied mock provider the model replies in prose, so the run **fails** with `invalid_ai_json` after one schema repair — the trace shows both attempts. |

Each response is `202` with a `run_id`. A `202` means *accepted*, not finished.

## 3. Follow and operate in the console

1. **Runs** lists the new run first (newest first). Filter by workflow or status; the filter is in the URL, so Back/Forward work. Or open `/console/runs/<run_id>` directly.
2. **Run detail** refreshes every 2 s until the run is finished. Each step shows status, branch taken, timing and **Inspect** for resolved input, output, error, attempts (cause, provider/model, tokens) and approval evidence. Secrets are shown as `[REDACTED]`.
3. For a waiting run, follow the approval link (or open **Approvals**). **Approve** resumes the run; **Reject** ends it as cancelled. Each asks for confirmation (Escape dismisses) and sends exactly one request.
4. **Cancel run** is available while a run is queued, running or waiting. It stops future work; the current step may finish.
5. **Workflows** shows each definition (frozen published version), its nodes in definition order and their branches (`If true` / `If false` / `Next` / `End`).

## 4. Automated live check

With the stack running, from `frontend/`:

```sh
RELAY_E2E_API=http://localhost:8080 RELAY_E2E_TOKEN="$RELAY_DEMO_TOKEN" RELAY_E2E_EXPENSE_SECRET="$EXPENSE_SECRET" \
  npx playwright test -c playwright.live.config.ts
```

The API must allow the preview origin: include `http://127.0.0.1:4173` in `RELAY_ALLOWED_ORIGINS`. The run builds the console for `RELAY_E2E_API`; rebuild afterwards with `npm run build` for your normal API origin. It creates real runs and mock-world effects; it never edits the database. Without the two variables every test is skipped.
