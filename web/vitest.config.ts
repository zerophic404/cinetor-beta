import { defineConfig } from 'vitest/config';

// Pure-TS unit tests: no SvelteKit/PWA plugins needed.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node'
  }
});
