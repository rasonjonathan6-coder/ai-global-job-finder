import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// The web edition is a static SPA. There is no server component: every request the app
// makes goes straight to the job source, and no credential of ours is ever shipped to the
// browser. See docs/web-parity.md for the (optional) proxy stage and its trade-offs.
export default defineConfig({
  base: "./",
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      includeAssets: ["icons/icon-192.png", "icons/icon-512.png", "favicon.svg"],
      manifest: {
        name: "AI Global Job Finder",
        short_name: "Job Finder",
        description:
          "Find real international jobs you can apply for from Madagascar, with matching, eligibility and expat evidence.",
        theme_color: "#0B1F3A",
        background_color: "#0B1F3A",
        display: "standalone",
        orientation: "portrait-primary",
        start_url: "./",
        scope: "./",
        categories: ["business", "productivity"],
        icons: [
          { src: "icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
          { src: "icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
          { src: "icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        globPatterns: ["**/*.{js,css,html,svg,png,woff2}"],
        // Job feeds are live data: never serve them from the SW cache. Only the app shell
        // is cached, and navigation falls back to it so the app opens offline.
        navigateFallback: "index.html",
        runtimeCaching: [
          {
            urlPattern: ({ request }) => request.destination === "document",
            handler: "NetworkFirst",
            options: { cacheName: "app-shell", networkTimeoutSeconds: 4 },
          },
        ],
      },
    }),
  ],
  server: { host: true, port: 12002, allowedHosts: [".prod-runtime.all-hands.dev"] },
  preview: { host: true, port: 4173, allowedHosts: [".prod-runtime.all-hands.dev"] },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
