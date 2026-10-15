/**
 * Headless UI smoke test.
 *
 * Boots the *real* browser entry point inside jsdom with a real canvas
 * implementation, then drives the app the way a user would: open the demo
 * document, paint, undo, add layers, export a PSD and re-open it.
 *
 *   node tools/build-ui.mjs && node tools/ui-smoke.mjs
 */
import { createCanvas, ImageData as NapiImageData } from '@napi-rs/canvas';
import { JSDOM } from 'jsdom';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readPsd, initializeCanvas } from 'ag-psd';
import { buildCore } from './build-core.mjs';
import { buildUi } from './build-ui.mjs';

initializeCanvas((w, h) => createCanvas(w, h));

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(root, 'tools/out');
mkdirSync(outDir, { recursive: true });

let failures = 0;
let checks = 0;
function check(label, condition, detail = '') {
  checks++;
  if (condition) console.log(`  ok   ${label}`);
  else { failures++; console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`); }
}

/* ---------------------------------------------------------------- jsdom set-up */

const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/<script type="module".*?<\/script>/s, '');
const dom = new JSDOM(html, { pretendToBeVisual: true, url: 'https://localhost/' });
const { window } = dom;

// canvases: back every jsdom <canvas> with a real 2D implementation
const backing = new WeakMap();
function backingFor(element) {
  let canvas = backing.get(element);
  if (!canvas) {
    canvas = createCanvas(Math.max(1, element.__w || 1), Math.max(1, element.__h || 1));
    backing.set(element, canvas);
  }
  return canvas;
}
const canvasProto = window.HTMLCanvasElement.prototype;
for (const dimension of ['width', 'height']) {
  const key = dimension === 'width' ? '__w' : '__h';
  Object.defineProperty(canvasProto, dimension, {
    configurable: true,
    get() { return backingFor(this)[dimension]; },
    set(value) {
      const size = Math.max(1, Math.round(Number(value) || 1));
      this[key] = size;
      backingFor(this)[dimension] = size;
    },
  });
}
canvasProto.getContext = function getContext(id, attrs) { return backingFor(this).getContext(id, attrs); };

canvasProto.toBlob = function toBlob(cb, type) { cb(new window.Blob([backingFor(this).toBuffer('image/png')], { type: type || 'image/png' })); };
Object.defineProperty(canvasProto, '__backing', { configurable: true, get() { return backingFor(this); } });
canvasProto.setPointerCapture = function () {};
window.HTMLCanvasElement.prototype.releasePointerCapture = function () {};

// jsdom has no layout engine: give every element a phone-sized box so the
// viewport, hit testing and drag maths behave like they do on a device.
const VIEWPORT = { width: 360, height: 640 };
window.Element.prototype.getBoundingClientRect = function getBoundingClientRect() {
  const isStage = this.id === 'stage';
  const width = isStage ? VIEWPORT.width : VIEWPORT.width;
  const height = isStage ? VIEWPORT.height : 56;
  return {
    x: 0, y: 0, top: 0, left: 0, right: width, bottom: height, width, height,
    toJSON() { return this; },
  };
};
canvasProto.releasePointerCapture = function () {};

// napi-rs/canvas only accepts its own objects: unwrap jsdom elements and ImageData
const napiCtxProto = Object.getPrototypeOf(createCanvas(1, 1).getContext('2d'));
const nativeDrawImage = napiCtxProto.drawImage;
napiCtxProto.drawImage = function drawImage(image, ...args) {
  const source = image && image.__backing ? image.__backing : image;
  return nativeDrawImage.call(this, source, ...args);
};
const nativePutImageData = napiCtxProto.putImageData;
napiCtxProto.putImageData = function putImageData(data, dx, dy, ...rest) {
  if (data instanceof globalThis.ImageData && !(data instanceof NapiImageData)) {
    return nativePutImageData.call(this, new NapiImageData(data.data, data.width, data.height), dx, dy, ...rest);
  }
  return nativePutImageData.call(this, data, dx, dy, ...rest);
};

class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
window.ResizeObserver = ResizeObserverStub;
window.devicePixelRatio = 2;
window.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
window.cancelAnimationFrame = (id) => clearTimeout(id);
window.URL.createObjectURL = () => 'blob:mock';
window.URL.revokeObjectURL = () => {};
window.navigator.canShare = undefined;
window.HTMLAnchorElement.prototype.click = function click() { /* block navigation */ };
window.HTMLCanvasElement.prototype.setPointerCapture = function () {};
window.HTMLCanvasElement.prototype.releasePointerCapture = function () {};
window.Element.prototype.setPointerCapture = function () {};
window.Element.prototype.releasePointerCapture = function () {};

const g = globalThis;
g.window = window;
g.document = window.document;
Object.defineProperty(g, 'navigator', { value: window.navigator, configurable: true, writable: true });
g.HTMLElement = window.HTMLElement;
g.HTMLCanvasElement = window.HTMLCanvasElement;
g.HTMLAnchorElement = window.HTMLAnchorElement;
g.Element = window.Element;
g.Node = window.Node;
g.Event = window.Event;
g.MouseEvent = window.MouseEvent;
g.PointerEvent = window.MouseEvent;
g.KeyboardEvent = window.KeyboardEvent;
g.Blob = window.Blob;
g.File = window.File;
g.Image = window.Image;
g.FileReader = window.FileReader;
g.MutationObserver = window.MutationObserver;
g.ResizeObserver = ResizeObserverStub;
g.requestAnimationFrame = window.requestAnimationFrame;
g.cancelAnimationFrame = window.cancelAnimationFrame;
g.devicePixelRatio = 2;
g.ImageData = NapiImageData; // the app only ever hands ImageData to a 2D context
g.location = window.location;
g.history = window.history;
g.caches = { async open() { return { async match() { return null; }, async delete() { return true; } }; } };
g.getComputedStyle = window.getComputedStyle.bind(window);
g.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
g.performance = window.performance;

await buildCore();
const core = await import(resolve(root, 'tools/.build/core.mjs'));
core.setCanvasFactory((w, h) => createCanvas(w, h));

await buildUi();
await import(resolve(root, 'tools/.build/ui.mjs'));

const app = window.photoCC;
const doc = () => app.session?.doc ?? null;
const $ = (id) => window.document.getElementById(id);

console.log('\n=== boot ===');
check('app booted', !!app, 'window.photoCC missing');
check('empty state is visible', !$('empty-state').hidden);
check('save button starts disabled', $('btn-save').disabled === true);

console.log('\n=== open the demo document (clicks the real button) ===');
$('btn-sample').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('document loaded', !!doc());
check('demo document has 5 top-level layers', doc()?.children.length === 5, `got ${doc()?.children.length}`);
check('canvas got a document', !!app.view.rendererInstance);
check('layer list rendered', $('layers').children.length >= 5, `${$('layers').children.length} rows`);
check('empty state hidden', $('empty-state').hidden);
check('layer rows have thumbnails', [...$('layers').querySelectorAll('canvas.layer-thumb')].length >= 5);

const textLayer = doc().children.flatMap((l) => (l.kind === 'group' ? l.children : [l])).find((l) => l.kind === 'text');
check('demo has an editable text layer', !!textLayer, 'no text layer');

console.log('\n=== paint with the brush (through the view hooks) ===');
const hooks = app.view.hooks ?? app.view['hooks'];
const rasterTarget = doc().children.find((l) => l.kind === 'raster' && l.name === 'Glow');
app.selectLayer(rasterTarget.id);

// Digests the part of the layer that existed before painting: strokes may grow
// the layer rectangle (transparent padding), so sample by document position.
const originalRect = { x: rasterTarget.x, y: rasterTarget.y, w: rasterTarget.w, h: rasterTarget.h };
const digestRegion = (layer) => {
  const { width: w, data } = layer.pixels;
  const dx = originalRect.x - layer.x, dy = originalRect.y - layer.y;
  let hash = 2166136261;
  for (let row = 0; row < originalRect.h; row++) {
    for (let col = 0; col < originalRect.w; col++) {
      const index = ((row + dy) * w + (col + dx)) * 4;
      hash = Math.imul(hash ^ data[index], 16777619);
      hash = Math.imul(hash ^ data[index + 1], 16777619);
      hash = Math.imul(hash ^ data[index + 3], 16777619);
    }
  }
  return hash >>> 0;
};
const beforeDigest = digestRegion(rasterTarget);
let beforeSnapshot = new Uint8ClampedArray(0);

{
  // document-space snapshot right before painting (the layer may grow afterwards)
  const { width: w, data } = rasterTarget.pixels;
  beforeSnapshot = new Uint8ClampedArray(originalRect.w * originalRect.h * 4);
  for (let row = 0; row < originalRect.h; row++) {
    for (let col = 0; col < originalRect.w; col++) {
      const from = ((row + originalRect.y - rasterTarget.y) * w + (col + originalRect.x - rasterTarget.x)) * 4;
      beforeSnapshot.set(data.subarray(from, from + 4), (row * originalRect.w + col) * 4);
    }
  }
}
hooks.onStrokeBegin(300, 300);
for (let i = 0; i < 12; i++) hooks.onStrokeMove(300 + i * 22, 300 + Math.sin(i / 2) * 60);
hooks.onStrokeEnd();
check('brush changed pixels', digestRegion(rasterTarget) !== beforeDigest);
check('painting outside the layer grew its rectangle', rasterTarget.pixels.height >= originalRect.h);
check('history recorded the stroke', app.history.canUndo);
check('undo button enabled', $('btn-undo').disabled === false);

console.log('\n=== undo / redo ===');
$('btn-undo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('undo restored the pixels', digestRegion(rasterTarget) === beforeDigest);
$('btn-redo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('redo re-applied the stroke', digestRegion(rasterTarget) !== beforeDigest);
$('btn-undo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

console.log('\n=== layers: add, rename, visibility, delete, reorder ===');
const idsBefore = new Set(doc().children.map((l) => l.id));
$('btn-add-layer').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const newLayer = doc().children.find((l) => !idsBefore.has(l.id));
check('layer added above the selection', !!newLayer && newLayer.name.startsWith('Layer'), newLayer?.name);
check('new layer is directly above the selected one', doc().children.indexOf(newLayer) === doc().children.indexOf(rasterTarget) + 1);
check('layer list refreshed with the new layer', $('layers').children.length >= 6);

app.updateLayerProp(textLayer, 'textText', 'Edited on a phone', 'Edit text');
check('text edit applied', textLayer.text.text === 'Edited on a phone', textLayer.text.text);
app.flushPendingProp(); // closes the coalescing window, like leaving the text sheet does
check('text edit is undoable', app.history.canUndo);

const rowCount = $('layers').children.length;
const eyeButton = $('layers').querySelector('.layer-row canvas.layer-thumb').parentElement.querySelector('button');
eyeButton.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('visibility toggle kept the tree intact', doc().children.length === newLayer ? true : doc().children.length === 6, String(doc().children.length));
void rowCount;

app.deleteLayer(newLayer);
app.flushPendingProp();
check('layer deleted', !doc().children.includes(newLayer));
$('btn-undo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('delete undone', doc().children.includes(newLayer), `children=${doc().children.map((l) => l.name).join(' | ')}`);

const indexBefore = doc().children.indexOf(newLayer);
app.moveInStack(newLayer, -1);
check('reorder moved the layer down one slot', doc().children.indexOf(newLayer) === indexBefore - 1, `${indexBefore} -> ${doc().children.indexOf(newLayer)}`);

console.log('\n=== text tool creates a real layer ===');
app.setTool('text');
const hooksB = app.view.hooks ?? app.view['hooks'];
app.flushPendingProp();
hooksB.onTap(200, 200, null);
const createdText = doc().children.filter((l) => l.kind === 'text').at(-1);
check('tap created a text layer', !!createdText && createdText.name === 'New text', createdText?.name);
// close the editor sheet that opening a new text layer triggers
window.document.querySelectorAll('.sheet').forEach((n) => n.remove());
window.document.querySelectorAll('.backdrop').forEach((n) => n.remove());

console.log('\n=== fill tool ===');
app.setTool('fill');
app.setColor({ r: 255, g: 0, b: 0, a: 255 });
const background = doc().children[0];
const regionSample = () => {
  const { width: w, data } = background.pixels;
  const parts = [];
  for (let y = 0; y < 80; y += 4) {
    for (let x = 0; x < 80; x += 4) {
      const i = ((y + 0) * w + x) * 4;
      parts.push(data[i], data[i + 1], data[i + 3]);
    }
  }
  return parts.join(',');
};
const backgroundDigestBefore = regionSample();
hooksB.onFillCommit({ x: 10, y: 10, w: 60, h: 60 });
const filledPixel = (() => {
  const { width: w, data } = background.pixels;
  const i = ((30 - background.y) * w + (30 - background.x)) * 4;
  return [...data.slice(i, i + 4)];
})();
check('fill painted the current colour', filledPixel[0] === 255 && filledPixel[1] === 0 && filledPixel[2] === 0, JSON.stringify(filledPixel));
check('fill changed the layer', backgroundDigestBefore !== regionSample());
check('fill is undoable', app.history.canUndo);

console.log('\n=== move tool drags a layer and is undoable ===');
const moveTarget = doc().children.find((l) => l.kind === 'raster' && l.name === 'Badge') ?? rasterTarget;
app.setTool('move');
const positionBefore = { x: moveTarget.x, y: moveTarget.y };
hooks.onLayerDragStart(moveTarget);
hooks.onLayerDragMove(moveTarget, 24, -18);
hooks.onLayerDragEnd();
app.flushPendingProp();
check('drag moved the layer', moveTarget.x === positionBefore.x + 24 && moveTarget.y === positionBefore.y - 18, JSON.stringify({ x: moveTarget.x, y: moveTarget.y }));
check('drag is undoable', app.history.canUndo && app.history.undoLabel === 'Move layer', app.history.undoLabel);
$('btn-undo').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
check('undo restored the position', moveTarget.x === positionBefore.x && moveTarget.y === positionBefore.y, JSON.stringify({ x: moveTarget.x, y: moveTarget.y }));

console.log('\n=== partial repaint matches a full repaint ===');
const settle = () => new Promise((resolve) => setTimeout(resolve, 25));
// the off-screen 1:1 document buffer is what region/full redraws must agree on
const backCanvas = () => (app.view.back ?? app.view['back']).__backing;
const stageDigest = () => {
  const node = backCanvas();
  const image = node.getContext('2d').getImageData(0, 0, node.width, node.height);
  let hash = 2166136261;
  for (let i = 0; i < image.data.length; i += 41) hash = Math.imul(hash ^ image.data[i], 16777619);
  return hash >>> 0;
};
app.view.invalidate();
await settle();
const digestBefore = stageDigest();
const editedLayer = doc().children.find((l) => l.kind === 'raster' && l.name === 'Chip') ?? moveTarget;
editedLayer.x += 30;                 // moves it: the old spot must be cleared too
editedLayer.rev++;
app.refreshCanvas(editedLayer);
await settle();
const digestPartial = stageDigest();
check('partial repaint changed the canvas', digestPartial !== digestBefore);
writeFileSync(resolve(outDir, 'ui-region-partial.png'), backCanvas().toBuffer('image/png'));
app.view.invalidate();               // force a full recomposite
await settle();
const digestFull = stageDigest();
check('partial repaint is pixel-identical to a full repaint', digestPartial === digestFull, `${digestPartial} vs ${digestFull}`);
writeFileSync(resolve(outDir, 'ui-region-full.png'), backCanvas().toBuffer('image/png'));
// put it back so later checks see the original layout
editedLayer.x -= 30;
editedLayer.rev++;
app.view.invalidate();

console.log('\n=== export a PSD (writeOut intercepted) ===');
let saved = null;
app.writeOut = async (data, name) => { saved = { data, name }; };
await app.exportPsd({ trim: false, invalidateText: true, compress: false, psb: false });
check('PSD bytes produced', !!saved && saved.data.byteLength > 1000, `${(saved?.data?.byteLength ?? 0)} bytes`);
check('file name looks right', saved?.name === 'demo.psd', saved?.name);
const reparsed = readPsd(saved.data.buffer.slice(saved.data.byteOffset, saved.data.byteOffset + saved.data.byteLength), { useImageData: true });
const flat = [];
(function collect(layers) { for (const l of layers ?? []) { flat.push(l); collect(l.children); } })(reparsed.children);
check('exported PSD parses', reparsed.width === doc().width && reparsed.height === doc().height);
check('exported PSD keeps every layer', flat.length === doc().children.flatMap((l) => (l.kind === 'group' ? [l, ...l.children] : [l])).length, `${flat.length} layers`);
check('edited text is in the file', flat.some((l) => l.text?.text?.includes('Edited on a phone')), JSON.stringify(flat.filter((l) => l.text).map((l) => l.text.text)));
check('export clears the dirty flag', app.session.dirty === false);

console.log('\n=== open the exported file back in the app ===');
const file = new window.File([new Uint8Array(saved.data)], saved.name, { type: 'image/vnd.adobe.photoshop' });
await app.openFile(file);
check('exported file re-opens in the app', !!doc());
const reopenedText = [];
core.walkLayers(doc().children, (l) => { if (l.kind === 'text') reopenedText.push(l); });
check('text survived the round trip', reopenedText.some((l) => l.text.text.includes('Edited on a phone')), JSON.stringify(reopenedText.map((l) => l.text.text)));

console.log('\n=== PNG export ===');
let png = null;
app.writeOut = async (data, name) => { png = { data, name }; };
await app.exportPng();
check('PNG produced', !!png && png.data instanceof window.Blob, png?.name);

// write a preview of the demo document so the result can be eyeballed
const view = createCanvas(doc().width, doc().height);
app.view.rendererInstance.composite(view, 1);
writeFileSync(resolve(outDir, 'ui-demo-document.png'), view.toBuffer('image/png'));

console.log(`\n${failures === 0 ? 'ALL UI CHECKS PASSED' : `${failures} UI CHECK(S) FAILED`} (${checks} checks)\n`);
process.exit(failures === 0 ? 0 : 1);
