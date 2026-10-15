/**
 * Runs the whole verification suite: PSD round-trip checks first, then the
 * headless UI smoke test. Used by `npm run check` and by CI.
 */
import { existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const run = (script) => execFileSync(process.execPath, [resolve(root, 'tools', script)], { stdio: 'inherit', cwd: root });

if (!existsSync(resolve(root, 'samples/poster.psd')) || !existsSync(resolve(root, 'samples/flat.psd'))) {
  console.log('generating sample documents…');
  run('make-samples.mjs');
}

run('roundtrip.mjs');
run('ui-smoke.mjs');

// Optional: files written by other software (Photoshop and friends). The corpus
// is not committed — see the header of tools/real-psd-test.mjs to fetch it.
const corpus = process.env.PHOTO_CC_PSD_CORPUS ?? '/tmp/realpsd';
if (existsSync(corpus)) run('real-psd-test.mjs');
else console.log(`skipping the external PSD corpus (${corpus} not found)`);

console.log('verification complete.');
