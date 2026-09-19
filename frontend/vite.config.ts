import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Phase 4 shell: static SPA. Dev server on 5173; backend (8080) is NOT
// proxied yet — API wiring arrives in a later phase (see src/api/client.ts).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Globals on so @testing-library/react auto-cleans the DOM between tests.
    globals: true,
  },
});
