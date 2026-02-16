import { readdirSync, statSync } from "node:fs";
import { join, extname } from "node:path";
import { execFileSync } from "node:child_process";

const distDir = join(import.meta.dirname, "..", "dist", "assets");

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  return kb < 1024 ? `${kb.toFixed(1)} KB` : `${(kb / 1024).toFixed(2)} MB`;
}

function gzipSize(filePath) {
  try {
    const compressed = execFileSync("gzip", ["-c", filePath]);
    return compressed.length;
  } catch {
    return null;
  }
}

try {
  const files = readdirSync(distDir);
  const entries = files
    .filter((f) => [".js", ".css"].includes(extname(f)))
    .map((f) => {
      const filePath = join(distDir, f);
      const raw = statSync(filePath).size;
      const gz = gzipSize(filePath);
      return { name: f, raw, gz };
    })
    .sort((a, b) => b.raw - a.raw);

  console.log("\n=== Bundle Analysis ===\n");
  console.log(
    "File".padEnd(50),
    "Size".padStart(10),
    "Gzip".padStart(10),
  );
  console.log("-".repeat(70));

  let totalRaw = 0;
  let totalGz = 0;

  for (const entry of entries) {
    totalRaw += entry.raw;
    totalGz += entry.gz ?? 0;
    console.log(
      entry.name.padEnd(50),
      formatBytes(entry.raw).padStart(10),
      (entry.gz ? formatBytes(entry.gz) : "n/a").padStart(10),
    );
  }

  console.log("-".repeat(70));
  console.log(
    "Total".padEnd(50),
    formatBytes(totalRaw).padStart(10),
    formatBytes(totalGz).padStart(10),
  );
  console.log();
} catch (err) {
  console.error(
    "No dist/assets directory found. Run 'npm run build' first.",
  );
  process.exit(1);
}
