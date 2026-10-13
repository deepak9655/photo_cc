/**
 * Bundles the browser entry (src/main.ts) for the jsdom smoke test.
 * CSS imports are dropped — jsdom does not need them.
 */
import { build } from 'esbuild';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export async function buildUi() {
  await build({
    entryPoints: [resolve(root, 'src/main.ts')],
    bundle: true,
    format: 'esm',
    platform: 'browser',
    target: 'es2022',
    outfile: resolve(root, 'tools/.build/ui.mjs'),
    loader: { '.css': 'empty' },
    logLevel: 'warning',
  });
  return resolve(root, 'tools/.build/ui.mjs');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  buildUi().then((f) => console.log('built', f));
}
