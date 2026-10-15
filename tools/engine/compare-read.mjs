/**
 * Reference reader.
 *
 * Reads every PSD in tools/engine/fixtures with `ag-psd` (an independent,
 * widely used implementation) and writes fixtures/expected.json: document size,
 * every layer's name/kind/size and an FNV-1a digest of its pixels.
 *
 * The Kotlin engine then reads the same files and must produce identical
 * digests — that is what proves the hand-written decoder is correct on files
 * this project did not create.
 *
 *   node tools/engine/compare-read.mjs
 */
import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { eachLayer, fixtureDir, layerSummary, readPsdBuffer, writePng } from './common.mjs';

mkdirSync(fixtureDir, { recursive: true });
if (!existsSync(fixtureDir)) {
  console.log(`no fixture directory at ${fixtureDir}`);
  process.exit(0);
}

const files = readdirSync(fixtureDir).filter((f) => /\.(psd|psb)$/i.test(f)).sort();
if (!files.length) {
  console.log(`no PSD fixtures in ${fixtureDir} — run tools/engine-test.sh to fetch them`);
  process.exit(0);
}

const documents = {};
let failures = 0;

for (const file of files) {
  const bytes = readFileSync(resolve(fixtureDir, file));
  let psd;
  try {
    psd = readPsdBuffer(bytes);
  } catch (error) {
    console.log(`  FAIL ${file}: reference reader refused it — ${error.message}`);
    failures++;
    continue;
  }
  const layers = [];
  const texts = [];
  eachLayer(psd.children, (layer) => {
    const summary = layerSummary(layer);
    if (summary.text) texts.push(summary.text);
    layers.push(summary);
  });
  documents[file] = {
    width: psd.width,
    height: psd.height,
    bitsPerChannel: psd.bitsPerChannel ?? 8,
    colorMode: psd.colorMode ?? 3,
    layers,
    texts,
  };
  console.log(`  ${file}: ${psd.width}x${psd.height}, ${layers.length} layers, ${texts.length} text layers`);

  // A composite dump makes visual inspection possible without Photoshop.
  if (psd.imageData) {
    const out = resolve(fixtureDir, `reference-${file.replace(/\.[^.]+$/, '')}.png`);
    const rgba = new Uint8ClampedArray(
      psd.imageData.data.buffer,
      psd.imageData.data.byteOffset,
      psd.imageData.data.length,
    );
    writePng(out, rgba, psd.imageData.width, psd.imageData.height);
  }
}

writeFileSync(resolve(fixtureDir, 'expected.json'), `${JSON.stringify({ documents }, null, 2)}\n`);
console.log(`\nwrote ${resolve(fixtureDir, 'expected.json')}`);
process.exit(failures ? 1 : 0);
