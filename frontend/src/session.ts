import { ApiError } from './api.ts';
import type { createApiClient } from './api.ts';

type Transport = ReturnType<typeof createApiClient>;
type Options = NonNullable<Parameters<Transport>[1]>;
export type SessionState = Readonly<{ phase: 'disconnected' | 'connecting' | 'connected'; message: string }>;

export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'The request could not be completed.';
  if (error.kind === 'http') {
    switch (error.status) {
      case 400: case 422: return 'The request is invalid.';
      case 401: return 'Your token was not accepted. Enter a valid management token.';
      case 403: return 'Access denied. Check the allowed browser origin and your access.';
      case 404: return 'The requested resource or API was not found.';
      case 409: return 'The resource changed. Refresh before trying again.';
      case 429: return 'Too many requests. Wait before trying again.';
      default: return 'The API could not complete the request. Try again later.';
    }
  }
  return error.message;
}

function workflowList(value: unknown): boolean {
  const rows = Array.isArray(value) ? value : value && typeof value === 'object' && 'workflows' in value ? value.workflows : undefined;
  return Array.isArray(rows) && rows.every(row => row && typeof row === 'object'
    && typeof row.id === 'string' && row.id.length > 0 && ['draft', 'published'].includes(row.status));
}

/** Memory-only credentials and request generation; never owns a persistent cache. */
export class ManagementSession {
  #token = '';
  #generation = 0;
  #requests = new Set<AbortController>();
  #listeners = new Set<() => void>();
  #state: SessionState = { phase: 'disconnected', message: '' };
  #transport: Transport;
  constructor(transport: Transport) { this.#transport = transport; }
  snapshot = (): SessionState => this.#state;
  subscribe = (listener: () => void) => { this.#listeners.add(listener); return () => { this.#listeners.delete(listener); }; };
  #publish(state: SessionState) { this.#state = state; this.#listeners.forEach(listener => listener()); }
  disconnect(message = '') {
    this.#generation++;
    this.#token = '';
    this.#requests.forEach(controller => controller.abort());
    this.#requests.clear();
    this.#publish({ phase: 'disconnected', message });
  }
  async connect(token: string): Promise<void> {
    this.disconnect();
    if (!/^[A-Za-z0-9\-._~+/]+=*$/.test(token)) {
      this.#publish({ phase: 'disconnected', message: 'Enter a management token without whitespace.' }); return;
    }
    const generation = this.#generation;
    const controller = new AbortController();
    this.#requests.add(controller);
    this.#publish({ phase: 'connecting', message: 'Checking API access…' });
    try {
      const result = await this.#transport('/workflows', { token, signal: controller.signal });
      if (generation !== this.#generation) return;
      if (!workflowList(result)) throw new ApiError('format');
      this.#token = token;
      this.#publish({ phase: 'connected', message: 'API access confirmed.' });
    } catch (error) {
      if (generation === this.#generation) this.disconnect(errorMessage(error));
    } finally { this.#requests.delete(controller); }
  }
  async request(path: string, options: Omit<Options, 'token'> = {}): Promise<unknown> {
    if (this.#state.phase !== 'connected') throw new ApiError('http', 401);
    const generation = this.#generation;
    const controller = new AbortController();
    const abort = () => controller.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) abort();
    this.#requests.add(controller);
    try {
      const result = await this.#transport(path, { ...options, token: this.#token, signal: controller.signal });
      if (generation !== this.#generation) throw new ApiError('aborted');
      return result;
    } catch (error) {
      if (generation !== this.#generation) throw new ApiError('aborted');
      if (error instanceof ApiError && error.status === 401) this.disconnect(errorMessage(error));
      throw error;
    } finally {
      options.signal?.removeEventListener('abort', abort);
      this.#requests.delete(controller);
    }
  }
}
