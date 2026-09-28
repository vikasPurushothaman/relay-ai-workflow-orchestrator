import { apiOrigin, createApiClient } from './api.ts';

// Future views call this after access setup. No request or token storage occurs on import.
export function relayClient() {
  const configuredOrigin = apiOrigin(import.meta.env.VITE_RELAY_API_BASE_URL);
  return createApiClient(import.meta.env.DEV ? window.location.origin : configuredOrigin);
}
