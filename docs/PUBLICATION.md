# Manual publication guide

This is the owner's checklist for publishing Relay as the public GitHub repository the capstone asks for ("Public GitHub repository link", PDF p4). **Only the owner runs the publication steps.** Automated tooling and the coding agent never push, upload or change the remote. They also never merge branches for publication or change repository visibility. The agent can run the read-only checks in step 1 and help interpret their output.

Run every command from the repository root.

## Repository state (recorded 2026-10-03, task 9.1)

| Item | State |
| --- | --- |
| Remote `origin` | `https://github.com/vikasPurushothaman/relay-ai-workflow-orchestrator.git` (created by the owner) |
| `origin/main` and local `main` | `987eafd`: initial commit only (before Phases 7 and 8) |
| `origin/PHASE07` | `d4fc65f`: Phase 7 complete |
| `origin/PHASE08`, local `UIENCH` | `5f52b12`: Phase 8 complete (application-ready milestone) |

GitHub shows the default branch first, so a reviewer opening the repository link currently sees the initial commit. Step 3 brings `main` up to date. `main` is an ancestor of `UIENCH`, so this is a fast-forward: no merge commit and no conflicts. Re-check this table with `git log --oneline --graph --all` before you start, because later Phase 9 work adds commits.

## 1. Pre-publication checks

1. Commit any outstanding work on your working branch (currently `UIENCH`), then confirm the tree is clean:

   ```bash
   git status --short
   ```

   Expected: no output.

2. Credentials and machine paths. This compares the real local secret values with the files and the full git history without printing them, confirms that sensitive paths are ignored and were never committed, and checks that no file needed to run contains a machine-specific path:

   ```bash
   python3 scripts/check_no_secrets.py
   ```

   Expected: exit status 0. If it fails, do not publish. Fix the reported file. If a secret ever reached a commit, rotate that secret first (for example, the AI provider key) and rewrite the history before publishing.

3. The checklist and requirements still agree:

   ```bash
   python3 scripts/check_completion.py
   ```

   Expected: exit status 0.

4. Optional, recommended after any code change: run the full automated suites listed in the [README](../README.md#testing).

### Never commit these

The following local files hold credentials, generated output or machine state. All of them are already in [`.gitignore`](../.gitignore). Never force-add them (`git add -f`):

| Path | Contents |
| --- | --- |
| `.env` | local configuration |
| `backend/.env` | local backend configuration |
| `backend/.env.ai` | real AI provider key |
| `frontend/.env.local` | local frontend configuration |
| `local-data/` | MySQL passwords, the demo token, local notes |
| `backend/build/` | compiled backend and jar |
| `frontend/dist/` | built console |
| `frontend/node_modules/` | installed packages |
| `tmp/` | scratch files |
| `full.log` | runtime log (all `*.log` files are ignored) |

Only `*.env.example` files are meant to be published.

## 2. Decide the pack licence (D04)

The repository contains an unchanged copy of the supplied capstone pack in [`docs/source-review/pack/`](source-review/pack/README.md). It came from `airtribe-projects/relay-capstone`, pinned commit `fe30f4a…`, with hashes in [pack-provenance.json](source-review/pack-provenance.json). The copy contains the mock world, mock provider, smoke test, duplication checker, catalog, seeds and payloads. The demo, the verification drivers and several checks use these files. The repository also contains the extracted brief text ([brief.txt](source-review/brief.txt)). Neither source carries an explicit licence grant. Attribution and provenance are preserved, but that is not permission to redistribute.

This is the owner's decision. The agent does not make it:

- **Option A: publish the copy as it is.** Choose this if the course allows, or expects, submissions to include the provided pack. Keep the attribution and provenance files unchanged.
- **Option B: ask first.** Ask the course team for the redistribution terms before you make the repository public. If they do not allow it, do not publish yet. Ask for a planned task that replaces the copied files with a documented download of the pinned upstream commit, because the demo and checks need those files.

Record the decision in the "Blockers and open decisions" section of [PROJECT_PLAN.md](../PROJECT_PLAN.md).

## 3. Update `main` (owner runs these)

Update `main` with your finished branch. `--ff-only` refuses to continue if `main` has diverged, so nothing is merged by surprise:

```bash
git switch main
```

```bash
git merge --ff-only UIENCH
```

```bash
git log --oneline -3
```

Expected: the top commit is the same as on `UIENCH`. Then publish `main` yourself:

```bash
git push origin main
```

If `git merge --ff-only` fails, stop. It means `main` has commits that are not on `UIENCH`. Compare them with `git log --oneline UIENCH..main` before deciding how to combine the two.

The `PHASE07` and `PHASE08` branches on the remote are earlier snapshots. You can keep them for history, but the submission should point to `main`.

## 4. Repository settings (owner, on GitHub)

1. **Settings → General → Danger Zone → Change repository visibility**: the repository must be **Public**.
2. **Settings → General → Default branch**: confirm it is `main`.
3. Open the repository in a private browser window where you are not signed in. Check that the README renders and that the latest commit on `main` matches your local `git log -1 --oneline`.

## 5. After publishing

- **Task 9.3** verifies setup from a fresh clone of the published `main`, following the [README](../README.md) and the [demo script](DEMO.md). Give the agent the public link. The agent records it and runs the local clean-copy verification; it does not push.
- **Task 9.5** packages the submission: the repository link (`https://github.com/vikasPurushothaman/relay-ai-workflow-orchestrator`), README, [verification report](VERIFICATION_REPORT.md), [demo instructions](DEMO.md) and the explainer video (task 9.4).
- If you change anything after publishing, run step 1 again before each push.
