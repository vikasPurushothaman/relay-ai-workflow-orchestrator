import { ApiError } from './api.ts';

/** Response parsers reject unexpected shapes instead of rendering partial or invented data. */
export const RUN_STATUSES = ['queued', 'running', 'waiting_approval', 'succeeded', 'failed', 'cancelled'] as const;
export type RunStatus = typeof RUN_STATUSES[number];
export const TERMINAL: ReadonlySet<string> = new Set(['succeeded', 'failed', 'cancelled']);
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export type WorkflowSummary = { id: string; name: string; status: 'draft' | 'published'; trigger_type: string; updated_at: string | null };
export type WorkflowDetail = {
  id: string; name: string; description: string | null; status: 'draft' | 'published'; definition: Record<string, Json>;
  secret_configured: boolean; created_at: string | null; updated_at: string | null; published_at: string | null;
  published_definition: Record<string, Json> | null; published_secret_configured: boolean;
};
export type RunSummary = {
  run_id: string; workflow_id: string; status: RunStatus; trigger_type: string; current_node_id: string | null;
  steps_executed: number; max_steps: number | null; created_at: string; started_at: string | null; finished_at: string | null;
};
export type RunPage = { runs: RunSummary[]; next_cursor: string | null };
export type Attempt = {
  attempt_no: number; status: string; cause: string; error: Json; output: Json; provider: string | null; model: string | null;
  tokens_prompt: number | null; tokens_completion: number | null; started_at: string | null; finished_at: string | null; duration_ms: number | null;
};
export type ApprovalEvidence = { id: string; status: string; message: string; decided_by: string | null; decided_at: string | null; closed_at: string | null; close_reason: string | null };
export type Step = {
  sequence: number; node_id: string; node_type: string; status: string; wait_reason: string | null; attempt_count: number;
  selected_next_node_id: string | null; resume_at: string | null; started_at: string | null; finished_at: string | null; duration_ms: number | null;
  resolved_input: Json; output: Json; error: Json; idempotency_key: string | null; ai_repair_count: number;
  tokens_prompt: number | null; tokens_completion: number | null; ai_usage_complete: boolean; retry_due_at: string | null;
  approval: ApprovalEvidence | null; attempts: Attempt[];
};
export type RunDetail = RunSummary & {
  workflow_name: string | null; entry: string | null; max_steps: number | null; error: { code: string; node_id: string | null } | null;
  cancel_requested_at: string | null; cancel_requested_by: string | null; cancellation_reason: string | null;
  input: Json; ai_tokens_used: number | null; ai_usage_complete: boolean; steps: Step[]; steps_next_after: number | null;
};
export type Approval = {
  id: string; run_id: string; workflow_id: string | null; step_sequence: number; node_id: string; message: string; status: string;
  created_at: string | null; decided_by: string | null; decided_at: string | null; closed_at: string | null;
};

const bad = (): never => { throw new ApiError('format'); };
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : bad());
const str = (v: unknown): string => (typeof v === 'string' && v.length > 0 ? v : bad());
const optStr = (v: unknown): string | null => (v === null || v === undefined ? null : typeof v === 'string' ? v : bad());
const int = (v: unknown): number => (typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : bad());
const optInt = (v: unknown): number | null => (v === null || v === undefined ? null : int(v));
const bool = (v: unknown): boolean => (typeof v === 'boolean' ? v : bad());
const oneOf = <T extends string>(v: unknown, values: readonly T[]): T => (values.includes(v as T) ? v as T : bad());
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : bad());
const json = (v: unknown): Json => (v === undefined ? null : v as Json);

export function parseWorkflows(value: unknown): WorkflowSummary[] {
  const rows = Array.isArray(value) ? value : arr(obj(value).workflows);
  return rows.map(r => { const o = obj(r); return { id: str(o.id), name: typeof o.name === 'string' ? o.name : o.id as string,
    status: oneOf(o.status, ['draft', 'published'] as const), trigger_type: optStr(o.trigger_type) ?? 'unknown', updated_at: optStr(o.updated_at) }; });
}
export function parseWorkflow(value: unknown): WorkflowDetail {
  const o = obj(value);
  const def = (v: unknown) => (v === null || v === undefined ? null : obj(v) as Record<string, Json>);
  return { id: str(o.id), name: typeof o.name === 'string' ? o.name : str(o.id), description: optStr(o.description),
    status: oneOf(o.status, ['draft', 'published'] as const), definition: def(o.definition) ?? bad(), secret_configured: o.secret_configured === true,
    created_at: optStr(o.created_at), updated_at: optStr(o.updated_at), published_at: optStr(o.published_at),
    published_definition: def(o.published_definition), published_secret_configured: o.published_secret_configured === true };
}
function summary(o: Record<string, unknown>): RunSummary {
  return { run_id: str(o.run_id), workflow_id: str(o.workflow_id), status: oneOf(o.status, RUN_STATUSES), trigger_type: str(o.trigger_type),
    current_node_id: optStr(o.current_node_id), steps_executed: int(o.steps_executed), max_steps: optInt(o.max_steps), created_at: str(o.created_at),
    started_at: optStr(o.started_at), finished_at: optStr(o.finished_at) };
}
export function parseRunPage(value: unknown): RunPage {
  const o = obj(value);
  return { runs: arr(o.runs).map(r => summary(obj(r))), next_cursor: optStr(o.next_cursor) };
}
function attempt(v: unknown): Attempt {
  const o = obj(v);
  return { attempt_no: int(o.attempt_no), status: str(o.status), cause: str(o.cause), error: json(o.error), output: json(o.output),
    provider: optStr(o.provider), model: optStr(o.model), tokens_prompt: optInt(o.tokens_prompt), tokens_completion: optInt(o.tokens_completion),
    started_at: optStr(o.started_at), finished_at: optStr(o.finished_at), duration_ms: optInt(o.duration_ms) };
}
function step(v: unknown): Step {
  const o = obj(v);
  const a = o.approval === null || o.approval === undefined ? null : obj(o.approval);
  return { sequence: int(o.sequence), node_id: str(o.node_id), node_type: str(o.node_type), status: str(o.status), wait_reason: optStr(o.wait_reason),
    attempt_count: int(o.attempt_count), selected_next_node_id: optStr(o.selected_next_node_id), resume_at: optStr(o.resume_at),
    started_at: optStr(o.started_at), finished_at: optStr(o.finished_at), duration_ms: optInt(o.duration_ms),
    resolved_input: json(o.resolved_input), output: json(o.output), error: json(o.error), idempotency_key: optStr(o.idempotency_key),
    ai_repair_count: optInt(o.ai_repair_count) ?? 0, tokens_prompt: optInt(o.tokens_prompt), tokens_completion: optInt(o.tokens_completion),
    ai_usage_complete: bool(o.ai_usage_complete), retry_due_at: optStr(o.retry_due_at),
    approval: a && { id: str(a.id), status: str(a.status), message: typeof a.message === 'string' ? a.message : '', decided_by: optStr(a.decided_by),
      decided_at: optStr(a.decided_at), closed_at: optStr(a.closed_at), close_reason: optStr(a.close_reason) },
    attempts: o.attempts === undefined ? [] : arr(o.attempts).map(attempt) };
}
export function parseRun(value: unknown): RunDetail {
  const o = obj(value);
  const e = o.error === null || o.error === undefined ? null : obj(o.error);
  return { ...summary(o), workflow_name: optStr(o.workflow_name), entry: optStr(o.entry), max_steps: optInt(o.max_steps),
    error: e && { code: str(e.code), node_id: optStr(e.node_id) }, cancel_requested_at: optStr(o.cancel_requested_at),
    cancel_requested_by: optStr(o.cancel_requested_by), cancellation_reason: optStr(o.cancellation_reason), input: json(o.input),
    ai_tokens_used: optInt(o.ai_tokens_used), ai_usage_complete: bool(o.ai_usage_complete),
    steps: arr(o.steps).map(step), steps_next_after: optInt(o.steps_next_after) };
}
export function parseApprovals(value: unknown): Approval[] {
  const rows = Array.isArray(value) ? value : arr(obj(value).approvals);
  return rows.map(r => { const o = obj(r); return { id: str(o.id), run_id: str(o.run_id), workflow_id: optStr(o.workflow_id),
    step_sequence: int(o.step_sequence), node_id: str(o.node_id), message: typeof o.message === 'string' ? o.message : '', status: str(o.status),
    created_at: optStr(o.created_at), decided_by: optStr(o.decided_by), decided_at: optStr(o.decided_at), closed_at: optStr(o.closed_at) }; });
}
export function parseDecision(value: unknown): { run_id: string; status: RunStatus } {
  const o = obj(value); return { run_id: str(o.run_id), status: oneOf(o.status, RUN_STATUSES) };
}

/** Merge continuation pages; the last page's run fields are newest, steps are keyed by sequence. */
export function mergeRunPages(pages: RunDetail[]): RunDetail {
  if (pages.length === 0) bad();
  const bySeq = new Map<number, Step>();
  for (const p of pages) for (const s of p.steps) bySeq.set(s.sequence, s);
  const last = pages[pages.length - 1];
  return { ...pages[0], steps: [...bySeq.values()].sort((a, b) => a.sequence - b.sequence), steps_next_after: last.steps_next_after };
}

export class TraceIncompleteError extends ApiError {
  constructor() {
    super('format');
    this.message = 'The run trace is incomplete because its page continuation was invalid or exceeded the safety limit.';
  }
}

export async function loadRunTrace(runId: string, request: (path: string) => Promise<unknown>, options: { signal?: AbortSignal; maxPages?: number } = {}): Promise<RunDetail> {
  const pages: RunDetail[] = [];
  const seen = new Set<number>();
  let after: number | null = 0;
  const maxPages = options.maxPages ?? 2_000;
  if (!Number.isInteger(maxPages) || maxPages < 1) throw new ApiError('configuration');
  while (after !== null) {
    options.signal?.throwIfAborted();
    if (pages.length >= maxPages) throw new TraceIncompleteError();
    const page = parseRun(await request(`/runs/${encodeURIComponent(runId)}${after ? `?steps_after=${after}` : ''}`));
    pages.push(page);
    const next = page.steps_next_after;
    if (next !== null) {
      if (next <= after || seen.has(next)) throw new TraceIncompleteError();
      seen.add(next);
    }
    after = next;
  }
  return mergeRunPages(pages);
}

export const STATUS_LABEL: Record<string, string> = {
  queued: 'Queued', running: 'Running', waiting_approval: 'Waiting for approval', succeeded: 'Succeeded', failed: 'Failed', cancelled: 'Cancelled',
  waiting: 'Waiting', uncertain: 'Outcome unknown', pending: 'Pending', approved: 'Approved', rejected: 'Rejected', closed: 'Closed',
  draft: 'Draft', published: 'Published',
};
export const statusLabel = (s: string) => STATUS_LABEL[s] ?? s;

/** Full UTC timestamp for display; null uses the caller's wording (e.g. "Not started"). */
export function formatTime(value: string | null, missing = 'Not recorded'): string {
  if (!value) return missing;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toISOString().replace('T', ' ').replace(/\.(\d{3})Z$/, '.$1') + ' UTC';
}
export function formatDuration(ms: number | null): string {
  if (ms === null) return 'Unknown';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  const m = Math.floor(ms / 60_000); return `${m} min ${Math.round((ms % 60_000) / 1000)} s`;
}
export function formatTokens(prompt: number | null, completion: number | null, complete = true): string {
  if (prompt === null && completion === null) return 'Unavailable';
  const total = (prompt ?? 0) + (completion ?? 0);
  const parts = `${total} (prompt ${prompt ?? 'unknown'}, completion ${completion ?? 'unknown'})`;
  return complete && prompt !== null && completion !== null ? parts : `${parts} — known usage; incomplete`;
}
export function cancellationText(run: Pick<RunDetail, 'status' | 'cancellation_reason' | 'cancel_requested_at'>): string | null {
  if (run.cancellation_reason === 'approval_rejected') return 'Cancelled because an approval was rejected.';
  if (run.status === 'cancelled') return 'Cancelled by an operator.';
  if (run.cancel_requested_at) return 'Cancellation requested — the current step may finish, then no further work starts.';
  return null;
}

export type Edge = { label: string; target: string | null; exists: boolean };
/** Edges in catalog order; explicit null is "End". Unknown targets are flagged, never dropped. */
export function nodeEdges(node: Record<string, Json>, ids: ReadonlySet<string>): Edge[] {
  const keys = node.type === 'condition' || 'on_true' in node || 'on_false' in node ? ['on_true', 'on_false'] : ['next'];
  return keys.filter(k => k in node).map(k => {
    const v = node[k];
    const target = typeof v === 'string' ? v : null;
    return { label: k === 'next' ? 'Next' : k === 'on_true' ? 'If true' : 'If false', target, exists: target === null || ids.has(target) };
  });
}

/** Validates console URL filters before any request is made. */
export function runFilters(params: URLSearchParams): { workflow: string | null; status: string | null; error: string | null } {
  const workflow = params.get('workflow') || null, status = params.get('status') || null;
  if (status !== null && !(RUN_STATUSES as readonly string[]).includes(status)) return { workflow, status, error: `Unknown status filter “${status}”.` };
  if (workflow !== null && [...workflow].length > 128) return { workflow, status, error: 'Workflow filter is too long.' };
  return { workflow, status, error: null };
}
export function runsQuery(f: { workflow: string | null; status: string | null }, cursor: string | null, limit = 25): string {
  const q = new URLSearchParams({ limit: String(limit) });
  if (f.workflow) q.set('workflow_id', f.workflow);
  if (f.status) q.set('status', f.status);
  if (cursor) q.set('cursor', cursor);
  return `/runs?${q}`;
}
