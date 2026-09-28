export type ErrorKind = 'configuration' | 'http' | 'format' | 'network' | 'timeout' | 'aborted';

export class ApiError extends Error {
  readonly kind: ErrorKind;
  readonly status?: number;
  constructor(kind: ErrorKind, status?: number) {
    const messages: Record<ErrorKind, string> = {
      configuration: 'Check the Relay API configuration or request path.',
      http: 'The Relay API could not complete this request.',
      format: 'The Relay API returned an unexpected response format.',
      network: 'Could not reach the Relay API.',
      timeout: 'The request timed out. A submitted action may still have completed.',
      aborted: 'The request was cancelled. A submitted action may still have completed.',
    };
    super(messages[kind]);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
  }
}

export function apiOrigin(value: string | undefined): string {
  try {
    if (!value || value.trim() !== value) throw new Error();
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) throw new Error();
    return url.origin;
  } catch { throw new ApiError('configuration'); }
}

export function pathSegment(value: string): string {
  if (!value || value === '.' || value === '..') throw new ApiError('configuration');
  return encodeURIComponent(value);
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  token?: string;
  body?: unknown;
  signal?: AbortSignal;
};

export function createApiClient(base: string, fetcher: typeof fetch = fetch, timeoutMs = 15_000) {
  const origin = apiOrigin(base);
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60_000) throw new ApiError('configuration');
  return async function request(path: string, options: RequestOptions = {}): Promise<unknown> {
    // Accept root-relative API paths only; normalization must not escape the selected origin.
    if (!/^\/(workflows|runs|approvals|hooks|actuator)(?:\/|\?|$)/.test(path)
      || /[\\#\s]/.test(path)) throw new ApiError('configuration');
    const url = new URL(path, origin);
    if (url.origin !== origin || !/^\/(workflows|runs|approvals|hooks|actuator)(?:\/|$)/.test(url.pathname)) {
      throw new ApiError('configuration');
    }
    const headers = new Headers({ Accept: 'application/json' });
    let body: string | undefined;
    try {
      if (options.token) headers.set('Authorization', `Bearer ${options.token}`);
      if (options.body !== undefined) {
        if (!options.method || options.method === 'GET') throw new Error();
        body = JSON.stringify(options.body);
        headers.set('Content-Type', 'application/json');
      }
    } catch { throw new ApiError('configuration'); }
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    options.signal?.addEventListener('abort', abort, { once: true });
    if (options.signal?.aborted) abort();
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    try {
      const response = await fetcher(url, {
        method: options.method ?? 'GET', body, headers, signal: controller.signal,
        credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer',
      });
      if (!response.ok) throw new ApiError('http', response.status);
      if (response.status === 204) return undefined;
      if (!/^application\/(?:[\w.+-]+\+)?json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) {
        throw new ApiError('format');
      }
      try { return await response.json(); }
      catch { throw new ApiError('format'); }
    } catch (error) {
      if (controller.signal.aborted) throw new ApiError(timedOut ? 'timeout' : 'aborted');
      if (error instanceof ApiError) throw error;
      throw new ApiError('network');
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', abort);
    }
  };
}
