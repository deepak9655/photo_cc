/**
 * Fetches a corpus of real Photoshop files for the engine tests.
 *
 * The fixtures are third-party sample files (the `ag-psd` test suite), so they
 * are *not* stored in this repository — this script pulls the interesting ones
 * into tools/engine/fixtures/ (git-ignored) on demand.
 *
 *   node tools/engine/fetch-fixtures.mjs [destination]
 *
 * Only the GitHub REST API is used (no scraping) and no token is required.
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const destination = resolve(process.argv[2] ?? 'tools/engine/fixtures');
const repo = 'Agamnentzar/ag-psd';
const api = `https://api.github.com/repos/${repo}`;

/** Which files are worth having, in the order they are wanted. */
const wanted = [
  'text/src.psd',
  'text-box/src.psd',
  'layer-mask/src.psd',
  'masks/src.psd',
  'shapes/src.psd',
  'stroke/src.psd',
  'gradient-mode/src.psd',
  'group-blend/src.psd',
  'vector-and-mask/src.psd',
  'adjustments/src.psd',
  'multiple-strokes-test.psd',
];

/** test/read-write/text/src.psd → text-src.psd */
function fixtureName(path) {
  const parts = path.split('/');
  parts.shift(); // "test"
  if (parts[0] === 'read-write' || parts[0] === 'write' || parts[0] === 'read') parts.shift();
  return parts.join('-');
}

/**
 * Reads a GitHub API endpoint. `curl` is preferred because it uses the system
 * certificate store (sandboxes and corporate networks often intercept TLS in a
 * way Node's own agent rejects), with `fetch` as the fallback.
 */
async function apiJson(url) {
  try {
    const output = execFileSync('curl', ['-sSL', '--max-time', '60', '-H', 'accept: application/vnd.github+json', url], {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    });
    const parsed = JSON.parse(output);
    if (parsed && parsed.message && !parsed.content && !parsed.tree) {
      throw new Error(parsed.message);
    }
    return parsed;
  } catch (curlError) {
    const response = await fetch(url, {
      headers: { accept: 'application/vnd.github+json', 'user-agent': 'photo-cc-engine-test' },
    });
    if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${url} (${curlError.message})`);
    return response.json();
  }
}

mkdirSync(destination, { recursive: true });

const tree = await apiJson(`${api}/git/trees/master?recursive=1`);
const available = new Map(
  (tree.tree ?? [])
    .filter((entry) => entry.type === 'blob' && /\.psd$/i.test(entry.path))
    .map((entry) => [entry.path, entry.size ?? 0]),
);

let fetched = 0;
for (const relative of wanted) {
  const path = [...available.keys()].find((candidate) => candidate.endsWith(relative));
  if (!path) {
    console.log(`  (not in the repository any more: ${relative})`);
    continue;
  }
  const name = fixtureName(path);
  const target = resolve(destination, name);
  if (existsSync(target)) {
    continue;
  }
  const contents = await apiJson(`${api}/contents/${path}`);
  const bytes = Buffer.from(contents.content ?? '', 'base64');
  if (!bytes.length) {
    console.log(`  (empty download: ${path})`);
    continue;
  }
  writeFileSync(target, bytes);
  console.log(`  ${name} (${(bytes.length / 1024).toFixed(1)} KB) from ${dirname(path)}`);
  fetched++;
}

console.log(fetched ? `fetched ${fetched} file(s) into ${destination}` : `fixtures already present in ${destination}`);
