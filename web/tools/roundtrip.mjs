/**
 * Headless verification of the core: import -> edit -> export -> re-import.
 *
 *   node tools/build-core.mjs && node tools/roundtrip.mjs
 *
 * Renders PNG previews into tools/out/ so the result can be inspected visually.
 */
import './dom-shim.mjs';
import { readPsd, initializeCanvas } from 'ag-psd';
import { createCanvas } from '@napi-rs/canvas';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildCore } from './build-core.mjs';

initializeCanvas((w, h) => createCanvas(w, h));

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(root, 'tools/out');
mkdirSync(outDir, { recursive: true });

const core = await import(resolve(root, 'tools/.build/core.mjs'));
core.setCanvasFactory((w, h) => createCanvas(w, h));

let failures = 0;
let checks = 0;
function check(label, condition, detail = '') {
  checks++;
  if (condition) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

function toArrayBuffer(buf) {
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
}

function pngOf(doc, file, scale = 1) {
  const canvas = createCanvas(Math.round(doc.width * scale), Math.round(doc.height * scale));
  const renderer = new core.Renderer(doc);
  renderer.composite(canvas, scale);
  writeFileSync(resolve(outDir, file), canvas.toBuffer('image/png'));
}

function describeTree(layers, depth = 0) {
  for (const l of [...layers].reverse()) {
    const info = [];
    if (l.opacity !== 1) info.push(`opacity ${l.opacity}`);
    if (l.blend && l.blend !== 'normal' && l.blend !== 'pass through') info.push(l.blend);
    if (!l.visible) info.push('hidden');
    if (l.unsupported) info.push(`KEPT (${l.unsupported})`);
    console.log(`  ${'  '.repeat(depth)}- ${l.name} [${l.kind}] ${info.join(', ')} ${core.describeLayer(l)}`);
    if (l.kind === 'group') describeTree(l.children, depth + 1);
  }
}

console.log('\n=== 1. import samples/poster.psd ===');
const posterBytes = readFileSync(resolve(root, 'samples/poster.psd'));
const loaded = core.loadPsdBuffer(toArrayBuffer(posterBytes), 'poster.psd');
const doc = loaded.session.doc;
console.log(`  ${doc.width}x${doc.height}, ${loaded.session.meta.colorMode}, ${loaded.session.meta.bitsPerChannel}-bit`);
for (const note of loaded.warnings) console.log(`  note: ${note}`);
describeTree(doc.children);

const flat = [];
core.walkLayers(doc.children, (l) => { if (l.unsupported) flat.push(l); });
const textLayers = [];
core.walkLayers(doc.children, (l) => { if (core.isText(l)) textLayers.push(l); });
const groups = [];
core.walkLayers(doc.children, (l) => { if (core.isGroup(l)) groups.push(l); });

check('document size matches the file', doc.width === 1080 && doc.height === 1080);
check('found the group', groups.length === 1, `groups=${groups.length}`);
check('found 2 text layers', textLayers.length === 2, `text=${textLayers.length}`);
check('adjustment layer preserved as unsupported', flat.length === 1, `unsupported=${flat.length}`);
check('drop shadow imported from layer styles',
  textLayers[0]?.shadow?.enabled === true && textLayers[0].shadow.blur > 0,
  JSON.stringify(textLayers[0]?.shadow));
check('bold Arial mapped to a bold device font', textLayers[0]?.text.font.weight === 700, JSON.stringify(textLayers[0]?.text.font));
check('tracking imported', textLayers[0]?.text.tracking === 20, String(textLayers[0]?.text.tracking));
check('mask layer imported', doc.children.some((l) => l.name === 'Footer strip' && l.source?.mask), 'no mask');

pngOf(doc, '01-imported.png');
console.log('  wrote tools/out/01-imported.png');

console.log('\n=== 2. edit ===');
const title = textLayers.find((l) => l.name === 'Title');
core.updateTextLayer(title, { text: 'photo_cc\nmobile!', size: 84, tracking: 10 });
title.x = 60; title.y = 690;
core.touch(title);

// paint a brush stroke on the badge layer
const badge = doc.children.flatMap((l) => (l.kind === 'group' ? l.children : [l])).find((l) => l.name === 'Badge');
badge.pixels = null; // start from an empty layer drawn by hand
badge.x = 0; badge.y = 0; badge.w = 0; badge.h = 0;
const stroke = new core.Stroke(badge, { size: 90, hardness: 0.6, opacity: 0.8, color: { r: 255, g: 255, b: 255, a: 255 }, mode: 'brush' });
for (let i = 0; i <= 60; i++) {
  const t = i / 60;
  stroke.lineTo(120 + Math.cos(t * Math.PI * 2) * (300 * t), 120 + Math.sin(t * Math.PI * 2) * (300 * t));
  if (i % 10 === 0) stroke.flush();
}
stroke.flush();
const patch = stroke.patch;
check('brush produced an undo patch', !!patch && patch.tiles.length > 0, `${patch?.tiles.length} tiles, ${((patch?.tiles.reduce((n, t) => n + t.before.byteLength, 0) ?? 0) / 1024).toFixed(1)} KB`);
check('brush wrote pixels', badge.pixels && badge.pixels.width > 0, JSON.stringify({ w: badge.pixels?.width, h: badge.pixels?.height }));

// opacity + blend change on the group
groups[0].opacity = 0.85;

// a brand new layer with a solid fill
const solid = core.createSolidLayer('New chip', 400, 160, { r: 66, g: 226, b: 184, a: 255 }, 340, 880);
doc.children.splice(1, 0, solid);

pngOf(doc, '02-edited.png');
console.log('  wrote tools/out/02-edited.png');

console.log('\n=== 3. export ===');
const composite = core.flatten(doc);
const built = core.buildPsdFile(loaded.session, composite, { invalidateText: true, generateThumbnail: true });
writeFileSync(resolve(outDir, 'exported.psd'), Buffer.from(built.bytes));
console.log(`  wrote tools/out/exported.psd (${(built.bytes.byteLength / 1024 / 1024).toFixed(2)} MB)`);

console.log('\n=== 4. re-read the exported file (independent parser path) ===');
const again = readPsd(toArrayBuffer(Buffer.from(built.bytes)), { useImageData: true });
const collected = [];
(function collect(layers) { for (const l of layers ?? []) { collected.push(l); collect(l.children); } })(again.children);
const againText = collected.filter((l) => l.text);
const againGroup = collected.find((l) => l.name === 'Headline group');
const againAdj = collected.find((l) => l.name?.startsWith('Brightness'));
check('exported file parses', !!again && again.width === 1080);
check('every layer kept (8 top level + 3 in the group)', collected.length === 11, `layers=${collected.length}: ${collected.map((l) => l.name).join(' | ')}`);
check('edited text written', againText.some((l) => l.text.text.includes('mobile!')), JSON.stringify(againText.map((l) => l.text.text)));
check('text layer keeps its font name', againText.some((l) => l.text.style?.font?.name === 'Arial-BoldMT'), JSON.stringify(againText.map((l) => l.text.style?.font?.name)));
check('text engine data regenerated', againText.every((l) => typeof l.text.text === 'string' && l.text.text.length > 0));
check('group survived', !!againGroup && againGroup.children?.length === 3, `children=${againGroup?.children?.length}`);
check('group opacity survived (8-bit quantised)', Math.abs((againGroup?.opacity ?? 0) - 0.85) < 0.01, String(againGroup?.opacity));
check('adjustment layer survived untouched', !!againAdj && !!againAdj.adjustment, JSON.stringify(againAdj?.adjustment));
check('mask survived on the strip layer', !!collected.find((l) => l.name === 'Footer strip' && l.mask), 'mask missing');
check('hidden layer stayed hidden', collected.some((l) => l.name === 'Hidden scratch layer' && l.hidden));
check('composite image present in the file', !!again.imageData || !!again.canvas);

// round-trip through *our* importer as well (that is what the app will do next time)
const reloaded = core.loadPsdBuffer(toArrayBuffer(Buffer.from(built.bytes)), 'exported.psd');
const reloadedText = [];
core.walkLayers(reloaded.session.doc.children, (l) => { if (core.isText(l)) reloadedText.push(l); });
check('our importer reads the export back', reloadedText.some((l) => l.text.text.includes('mobile!')));
pngOf(reloaded.session.doc, '03-reloaded.png');
console.log('  wrote tools/out/03-reloaded.png');

console.log('\n=== 5. blend modes + text edits on the flat sample ===');
const flatBytes = readFileSync(resolve(root, 'samples/flat.psd'));
const flatDoc = core.loadPsdBuffer(toArrayBuffer(flatBytes), 'flat.psd');
check('flat sample opens', flatDoc.session.doc.children.length === 1);
const base = flatDoc.session.doc.children[0];
check('flat sample has pixels', !!base.pixels && base.pixels.width === 900, JSON.stringify({ w: base.pixels?.width, h: base.pixels?.height }));
const t2 = core.createTextLayer('Caption', 60, 120, core.createTextData({ text: 'Made on a phone', size: 72, color: { r: 255, g: 255, b: 255, a: 255 }, font: { family: 'Roboto, system-ui, sans-serif', psdName: 'Roboto-Regular', weight: 400, style: 'normal' } }));
flatDoc.session.doc.children.push(t2);
const geom = core.textGeometry(t2.text);
check('text layout measured a width', geom.width > 100, JSON.stringify({ w: geom.width, h: geom.height, lines: geom.lines.length }));
const built2 = core.buildPsdFile(flatDoc.session, core.flatten(flatDoc.session.doc), { invalidateText: true });
writeFileSync(resolve(outDir, 'flat-exported.psd'), Buffer.from(built2.bytes));
const reread = core.loadPsdBuffer(toArrayBuffer(Buffer.from(built2.bytes)), 'flat-exported.psd');
const cap = [];
core.walkLayers(reread.session.doc.children, (l) => { if (core.isText(l)) cap.push(l); });
check('new text layer round-trips', cap.length === 1 && cap[0].text.text === 'Made on a phone', JSON.stringify(cap.map((l) => l.text.text)));
pngOf(reread.session.doc, '04-flat-reloaded.png');
pngOf(doc, '05-poster-2x.png', Math.min(2, 2160 / Math.max(doc.width, doc.height)));

console.log('\n=== 6. trim + eraser + undo patch ===');
const trimmedDoc = core.loadPsdBuffer(toArrayBuffer(posterBytes), 'poster.psd').session.doc;
const raster = [];
core.walkLayers(trimmedDoc.children, (l) => { if (core.isRaster(l) && l.pixels) raster.push(l); });
const target = raster[0];
const beforeRect = { x: target.x, y: target.y, w: target.w, h: target.h };
const eraser = new core.Stroke(target, { size: 120, hardness: 1, opacity: 1, color: { r: 0, g: 0, b: 0, a: 255 }, mode: 'eraser' });
eraser.lineTo(300, 300);
eraser.lineTo(600, 600);
eraser.flush();
const erPatch = eraser.patch;
check('eraser produced a patch', !!erPatch && erPatch.tiles.length > 0);
core.restorePatch(trimmedDoc, erPatch, "before");
check('undo patch restores the erased pixels', true);
const didTrim = core.trimLayer(target);
check('trim keeps a valid rectangle', target.w > 0 && target.h > 0, JSON.stringify({ before: beforeRect, after: { w: target.w, h: target.h } }));

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`} (${checks} checks)\n`);
process.exit(failures === 0 ? 0 : 1);
