/**
 * Independent writer verifier.
 *
 * Takes the PSD files the Kotlin engine wrote (build/engine-test/*.psd) together
 * with the expectations it recorded in expected.json, reads them with `ag-psd`
 * and checks that everything survived the trip: layer names, stacking, opacity,
 * blend modes, pixel digests and — most importantly — that the type blocks the
 * engine generates are valid, so Photoshop can re-render edited text.
 *
 * It also dumps the composites the independent reader produces as PNGs, which
 * makes the result inspectable without Photoshop installed.
 *
 *   node tools/engine/verify-write.mjs
 */
import { existsSync, readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { digestRgba, eachLayer, layerSummary, outputDir, readPsdBuffer, writePng } from './common.mjs';

let checks = 0;
let failures = 0;
const check = (label, ok, detail = '') => {
  checks++;
  if (ok) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`);
  }
};

const expectationFile = resolve(outputDir, 'expected.json');
if (!existsSync(expectationFile)) {
  console.log(`no expectations at ${expectationFile} — run the Kotlin engine test first`);
  process.exit(0);
}

const expectations = JSON.parse(readFileSync(expectationFile, 'utf8'));
const pngDir = resolve(outputDir, 'png');
mkdirSync(pngDir, { recursive: true });

for (const [file, expected] of Object.entries(expectations.documents)) {
  const path = resolve(outputDir, file);
  if (!existsSync(path)) {
    check(`${file}: exists`, false, path);
    continue;
  }
  console.log(`\n=== ${file} (written by the Kotlin engine, read by ag-psd) ===`);

  let psd;
  try {
    psd = readPsdBuffer(readFileSync(path));
  } catch (error) {
    check(`${file}: independent reader accepts the file`, false, error.message);
    continue;
  }
  check(`${file}: independent reader accepts the file`, true);

  const found = [];
  eachLayer(psd.children, (layer) => found.push(layerSummary(layer, true)));
  check(
    `${file}: layer count`,
    found.length === expected.layers.length,
    `ag-psd found ${found.length}, engine wrote ${expected.layers.length}`,
  );

  for (const want of expected.layers) {
    const have = found.find((layer) => layer.name === want.name && (want.kind === 'text' || layer.kind === want.kind));
    if (!have) {
      check(`${file}: layer '${want.name}' (${want.kind})`, false, `not found in ${found.map((l) => `${l.name}/${l.kind}`).join(', ')}`);
      continue;
    }
    if (want.kind === 'group') {
      check(`${file}: group '${want.name}'`, true);
      continue;
    }
    if (want.kind === 'text') {
      check(`${file}: text '${want.name}' = "${want.text}"`, have.text === want.text, `read "${have.text}"`);
      // The type block is written together with rasterised pixels, so viewers
      // that do not implement text layers still show the right thing.
      const image = have.image;
      check(
        `${file}: text '${want.name}' also carries rendered pixels`,
        !!image && image.width > 0 && image.height > 0 && image.digest !== null && image.digest !== '0'.repeat(16),
        image ? `${image.width}x${image.height} ${image.digest}` : 'no imageData',
      );
      continue;
    }
    check(
      `${file}: '${want.name}' pixels and size`,
      have.width === want.width && have.height === want.height && have.digest === want.digest,
      `${have.width}x${have.height} ${have.digest} vs ${want.width}x${want.height} ${want.digest}`,
    );
    if (want.opacity !== undefined) {
      const layer = findLayer(psd.children, want.name);
      if (layer) {
        const opacity = Math.round((layer.opacity ?? 1) * 255);
        check(`${file}: '${want.name}' opacity`, Math.abs(opacity - want.opacity) <= 2, `${opacity} vs ${want.opacity}`);
        check(`${file}: '${want.name}' blend mode`, (layer.blendMode ?? 'normal') === want.blend, `${layer.blendMode} vs ${want.blend}`);
        check(
          `${file}: '${want.name}' visibility`,
          !layer.hidden === want.visible,
          `hidden=${layer.hidden} expected visible=${want.visible}`,
        );
      }
    }
  }

  // Text layers must be re-renderable: the type block has to parse and carry the
  // font the engine recorded.
  for (const want of expected.layers.filter((layer) => layer.kind === 'text')) {
    const layer = findLayer(psd.children, want.name);
    if (!layer || !layer.text) {
      check(`${file}: '${want.name}' carries a type block ag-psd understands`, false);
      continue;
    }
    check(`${file}: '${want.name}' type block parses`, true);
    const fontName = layerSummary(layer, true).fontName ?? '';
    if (want.edited) {
      // The engine rewrote this type block, so the font it wrote must be in it.
      check(`${file}: '${want.name}' font survives an edit`, fontName === want.font, `${fontName} vs ${want.font}`);
    } else {
      check(`${file}: '${want.name}' keeps a font reference`, fontName.length > 0, '(none)');
    }
  }

  // The merged image the engine wrote must match the engine's own composite.
  if (expected.merged && psd.imageData) {
    const image = psd.imageData;
    const data = image.data;
    const rgba = new Uint8ClampedArray(data.buffer, data.byteOffset, data.length);
    const digest = digestRgba(rgba, image.width, image.height, 4);
    check(
      `${file}: merged image matches the engine composite`,
      digest === expected.merged,
      `${digest} vs ${expected.merged}`,
    );
  }

  // Dump the composite as the independent reader sees it.
  if (psd.imageData) {
    const rgba = new Uint8ClampedArray(
      psd.imageData.data.buffer,
      psd.imageData.data.byteOffset,
      psd.imageData.data.length,
    );
    const out = resolve(pngDir, `${file.replace(/\.[^.]+$/, '')}.png`);
    writePng(out, rgba, psd.imageData.width, psd.imageData.height);
    console.log(`  -> wrote ${out} (${psd.imageData.width}x${psd.imageData.height})`);
  } else {
    check(`${file}: merged (composite) image present`, false, 'ag-psd found no imageData');
  }
}

function findLayer(children, name) {
  for (const layer of children ?? []) {
    if (layer.name === name) return layer;
    if (layer.children) {
      const found = findLayer(layer.children, name);
      if (found) return found;
    }
  }
  return null;
}

writeFileSync(resolve(outputDir, 'verify-write.json'), `${JSON.stringify({ checks, failures }, null, 2)}\n`);
console.log();
if (failures === 0) {
  console.log(`ALL WRITER CHECKS PASSED (${checks} checks)`);
} else {
  console.log(`WRITER CHECKS FAILED: ${checks - failures} passed, ${failures} failed`);
  process.exit(1);
}
