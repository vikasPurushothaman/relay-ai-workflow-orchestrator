import { defineConfig } from '@playwright/test';
// Task 6.12: drives the built console against a real running stack. Opt-in; see docs/CONSOLE_DEMO.md.
const api = process.env.RELAY_E2E_API ?? 'http://localhost:8080';
export default defineConfig({
  testDir: './tests', testMatch: 'e2e-live.spec.ts', fullyParallel: false, workers: 1, timeout: 120_000,
  use: { baseURL: 'http://127.0.0.1:4173', browserName: 'chromium', screenshot: 'only-on-failure' },
  webServer: { command: 'npm run build && npm run preview', url: 'http://127.0.0.1:4173', reuseExistingServer: false,
    env: { VITE_RELAY_API_BASE_URL: api }, timeout: 120_000 },
});
