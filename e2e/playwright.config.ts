import { defineConfig, devices } from '@playwright/test'

// The tests drive the whole stack through the browser, so `docker compose up --build -d --wait`
// must already be running. Both addresses can be overridden for another environment
// (BASE_URL here, MAILPIT_URL in support/mailpit.ts).
export default defineConfig({
  testDir: './tests',
  // The tests use unique data and don't share state, so they can run side by side
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:3000',
    // Traces and screenshots are kept only for failed tests, so passing runs stay small
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
