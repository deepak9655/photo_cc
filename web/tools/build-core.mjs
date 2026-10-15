/**
 * Bundles src/core for the Node test harness (esbuild is already a dependency
 * of vite, so no extra tooling is needed).
 */
import { build } from 'esbuild';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export async function buildCore() {
  await build({
    entryPoints: [resolve(root, 'src/core/index.ts')],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2022',
    outfile: resolve(root, 'tools/.build/core.mjs'),
    logLevel: 'warning',
  });
  return resolve(root, 'tools/.build/core.mjs');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  buildCore().then((f) => console.log('built', f));
}
