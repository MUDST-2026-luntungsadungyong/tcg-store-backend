import { defineConfig } from '@playwright/test';

const environment = process.env.TCG_SMOKE_ENVIRONMENT;
const frontendUrl = process.env.TCG_FRONTEND_URL;

if (!['staging', 'production'].includes(environment)) {
  throw new Error('Set TCG_SMOKE_ENVIRONMENT to staging or production.');
}
if (!frontendUrl) {
  throw new Error('Set TCG_FRONTEND_URL to the already deployed frontend URL.');
}
const parsed = new URL(frontendUrl);
if (environment !== 'staging' && parsed.protocol !== 'https:') {
  throw new Error('Production browser acceptance requires an HTTPS frontend URL.');
}
if (environment === 'staging' && parsed.protocol !== 'https:') {
  throw new Error('Staging browser acceptance requires an HTTPS frontend URL.');
}

export default defineConfig({
  testDir: '.',
  testMatch: 'frontend_smoke.spec.js',
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: parsed.origin,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    extraHTTPHeaders: { 'Cache-Control': 'no-cache' },
  },
});
