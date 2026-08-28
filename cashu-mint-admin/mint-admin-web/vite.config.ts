import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "path";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  server: {
    port: 3000,
    proxy: {
      "/admin": {
        target: process.env.VITE_API_BASE_URL || "http://localhost:7778",
        changeOrigin: true,
      },
      // The NAP handshake and session endpoint live here.
      "/api/v1/auth": {
        target: process.env.VITE_API_BASE_URL || "http://localhost:7778",
        changeOrigin: true,
      },
    },
  },
});
