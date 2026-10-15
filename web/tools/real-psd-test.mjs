/**
 * Compatibility test against PSD files authored by other software (these three
 * come from the ag-psd test corpus and were written by Photoshop / other
 * editors). They are not committed; fetch them if you want to run this locally:
 *
 *   gh api repos/Agamnentzar/ag-psd/contents/test/read-write/text/src.psd --jq .content | base64 -d > /tmp/realpsd/text-src.psd
 *   node tools/real-psd-test.mjs
 *
 * The point is to prove the importer survives files this project did not write.
 */
import './dom-shim.mjs';
import { readPsd, initializeCanvas } from 'ag-psd';
import { createCanvas } from '@napi-rs/canvas';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildCore } from './build-core.mjs';

initializeCanvas((w, h) => createCanvas(w, h));
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(root, 'tools/out');
mkdirSync(outDir, { recursive: true });

const dir = process.argv[2] ?? '/tmp/realpsd';
if (!existsSync(dir)) {
  console.log(`no external PSD corpus at ${dir} — skipping (see the comment at the top of this file)`);
  process.exit(0);
}

await buildCore();
const core = await import(resolve(root, 'tools/.build/core.mjs'));
core.setCanvasFactory((w, h) => createCanvas(w, h));

let failures = 0;
let checks = 0;
const check = (label, ok, detail = '') => {
  checks++;
  if (ok) console.log(`  ok   ${label}`);
  else { failures++; console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`); }
};

for (const file of readdirSync(dir).filter((f) => f.endsWith('.psd')).sort()) {
  console.log(`\n=== ${file} ===`);
  const bytes = readFileSync(resolve(dir, file));
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const header = core.probeHeader(buffer);
  check('header parses', !!header, 'not a PSD');
  if (!header) continue;
  console.log(`  ${header.width}x${header.height}, ${header.colorModeName}, ${header.bitsPerChannel}-bit, v${header.version}`);

  let loaded;
  try {
    loaded = core.loadPsdBuffer(buffer, file);
  } catch (error) {
    check('imports without throwing', false, error.message);
    continue;
  }
  check('imports without throwing', true);
  const doc = loaded.session.doc;
  for (const note of loaded.warnings) console.log(`  note: ${note}`);

  let layers = 0, textLayers = 0, groups = 0, raster = 0, kept = 0;
  core.walkLayers(doc.children, (l) => {
    layers++;
    if (l.kind === 'text') textLayers++;
    if (l.kind === 'group') groups++;
    if (l.kind === 'raster') raster++;
    if (l.unsupported) kept++;
  });
  console.log(`  layers: ${layers} (${raster} raster, ${textLayers} text, ${groups} groups, ${kept} kept-but-not-previewed)`);
  check('at least one layer imported', layers > 0, 'no layers');

  const canvas = createCanvas(doc.width, doc.height);
  const renderer = new core.Renderer(doc);
  renderer.composite(canvas, 1);
  const png = canvas.toBuffer('image/png');
  // alpha coverage, not file size: small documents are legitimately tiny
  const rendered = canvas.getContext('2d').getImageData(0, 0, doc.width, doc.height).data;
  let opaque = 0;
  for (let i = 3; i < rendered.length; i += 4) if (rendered[i] > 0) opaque++;
  check('renders visible pixels', opaque > 0, 'fully transparent composite');
  writeFileSync(resolve(outDir, `real-${file.replace(/\.psd$/, '')}.png`), png);

  // round-trip: export, re-parse with the independent ag-psd reader, compare structure
  const exported = core.buildPsdFile(loaded.session, core.flatten(doc), { invalidateText: true });
  const reparsed = readPsd(exported.bytes.buffer.slice(exported.bytes.byteOffset, exported.bytes.byteOffset + exported.bytes.byteLength), { useImageData: true });
  const names = [];
  (function collect(list) { for (const l of list ?? []) { names.push(l.name); collect(l.children); } })(reparsed.children);
  const ours = [];
  core.walkLayers(doc.children, (l) => ours.push(l.name));
  check('every layer survives the round trip', names.length === ours.length && ours.every((n) => names.includes(n)),
    `in=${ours.length} out=${names.length}`);
  const textAfter = [];
  (function collect(list) { for (const l of list ?? []) { if (l.text) textAfter.push(l.text.text); collect(l.children); } })(reparsed.children);
  check('text layers keep their content', textAfter.length === textLayers, `${textAfter.length} vs ${textLayers}`);
  console.log(`  text: ${JSON.stringify(textAfter.slice(0, 3))}`);
}

console.log(`\n${failures === 0 ? 'ALL EXTERNAL PSD CHECKS PASSED' : `${failures} CHECK(S) FAILED`} (${checks} checks)\n`);
process.exit(failures === 0 ? 0 : 1);
