/**
 * Document operations.
 *
 * Everything here is a pure function on our small model: no canvas, no DOM, no
 * history. The UI wraps these calls in `History.run()` so that undo stays cheap
 * (we store pixel *patches*, never whole-document snapshots).
 *
 * The brush engine is deliberately CPU-light: a stroke is rasterised into a
 * small Float32 coverage buffer covering only the area the stroke touched, then
 * composited into the layer's pixels. No canvas is involved while painting, so
 * there is no per-dab GPU upload and no allocation churn.
 */

import {
  createTextData, isGroup, isRaster, isText, newId, walkLayers,
  type Doc, type GroupLayer, type Layer, type Pixels, type RGBA, type RasterLayer,
  type TextData, type TextLayer,
} from './types';

/* ------------------------------------------------------------------ constructors */

export function createDocument(width: number, height: number): Doc {
  return { width: Math.max(1, Math.round(width)), height: Math.max(1, Math.round(height)), children: [] };
}

export function createRasterLayer(name: string, x = 0, y = 0, w = 0, h = 0, pixels: Pixels | null = null): RasterLayer {
  return {
    kind: 'raster', id: newId('R'), name, visible: true, opacity: 1, blend: 'normal',
    locked: false, rev: 1, x, y, w, h, pixels,
  };
}

export function createTextLayer(name: string, x: number, y: number, text: TextData): TextLayer {
  return {
    kind: 'text', id: newId('T'), name, visible: true, opacity: 1, blend: 'normal',
    locked: false, rev: 1, x, y, text,
    stroke: { enabled: false, color: { r: 0, g: 0, b: 0, a: 255 }, width: 2 },
    shadow: { enabled: false, color: { r: 0, g: 0, b: 0, a: 200 }, blur: 8, dx: 3, dy: 3 },
  };
}

export function createGroupLayer(name: string, children: Layer[] = []): GroupLayer {
  return {
    kind: 'group', id: newId('G'), name, visible: true, opacity: 1, blend: 'normal',
    locked: false, rev: 1, children, opened: true,
  };
}

export function createSolidLayer(name: string, w: number, h: number, color: RGBA, x = 0, y = 0): RasterLayer {
  const data = new Uint8ClampedArray(w * h * 4);
  for (let i = 0; i < data.length; i += 4) {
    data[i] = color.r; data[i + 1] = color.g; data[i + 2] = color.b; data[i + 3] = color.a;
  }
  return createRasterLayer(name, x, y, w, h, { width: w, height: h, data });
}

export function touch(layer: Layer): void { layer.rev++; }

/* ------------------------------------------------------------------ tree helpers */

export interface LayerPath { list: Layer[]; index: number; parent: GroupLayer | null }

export function findPath(doc: Doc, id: string): LayerPath | null {
  const search = (list: Layer[], parent: GroupLayer | null): LayerPath | null => {
    for (let i = 0; i < list.length; i++) {
      if (list[i].id === id) return { list, index: i, parent };
      const l = list[i];
      if (isGroup(l)) {
        const found = search(l.children, l);
        if (found) return found;
      }
    }
    return null;
  };
  return search(doc.children, null);
}

export function removeLayer(doc: Doc, id: string): Layer | null {
  const path = findPath(doc, id);
  if (!path) return null;
  return path.list.splice(path.index, 1)[0];
}

export function insertLayer(doc: Doc, layer: Layer, parentId: string | null, index: number): void {
  const parent = parentId ? findLayerById(doc, parentId) : undefined;
  const target: Layer[] = parent && isGroup(parent) ? parent.children : doc.children;
  const at = Math.max(0, Math.min(index, target.length));
  target.splice(at, 0, layer);
}

export function findLayerById(doc: Doc, id: string): Layer | undefined {
  let found: Layer | undefined;
  walkLayers(doc.children, (l) => { if (l.id === id) found = l; });
  return found;
}

/** Deep copy of a layer (pixels included) with fresh ids. */
export function duplicateLayer(layer: Layer, nameSuffix = ' copy'): Layer {
  const copy: any = {
    ...layer,
    id: newId(layer.kind[0].toUpperCase()),
    name: layer.name + nameSuffix,
    source: undefined,
    sourceId: undefined,
    rev: 1,
  };
  if (isRaster(layer)) {
    copy.pixels = layer.pixels ? { width: layer.pixels.width, height: layer.pixels.height, data: new Uint8ClampedArray(layer.pixels.data) } : null;
  } else if (isText(layer)) {
    copy.text = { ...layer.text, font: { ...layer.text.font }, color: { ...layer.text.color } };
    copy.stroke = { ...layer.stroke, color: { ...layer.stroke.color } };
    copy.shadow = { ...layer.shadow, color: { ...layer.shadow.color } };
  } else if (isGroup(layer)) {
    copy.children = layer.children.map((c) => duplicateLayer(c, ''));
    copy.opened = layer.opened;
  }
  return copy as Layer;
}

/** Moves a layer inside the tree; returns true when the move happened. */
export function moveLayer(doc: Doc, id: string, targetParentId: string | null, index: number): boolean {
  if (id === targetParentId) return false;
  const layer = removeLayer(doc, id);
  if (!layer) return false;
  // cannot drop a group inside itself
  if (isGroup(layer) && targetParentId && containsId(layer, targetParentId)) {
    doc.children.push(layer);
    return false;
  }
  insertLayer(doc, layer, targetParentId, index);
  return true;
}

export function containsId(layer: Layer, id: string): boolean {
  if (layer.id === id) return true;
  if (isGroup(layer)) return layer.children.some((c) => containsId(c, id));
  return false;
}

/* ------------------------------------------------------------------ pixels */

export function emptyPixels(w: number, h: number): Pixels {
  return { width: Math.max(1, Math.round(w)), height: Math.max(1, Math.round(h)), data: new Uint8ClampedArray(Math.max(1, Math.round(w)) * Math.max(1, Math.round(h)) * 4) };
}

/**
 * Grows (or moves) a raster layer's rectangle while keeping its pixels, like
 * Photoshop does when you paint outside the current layer bounds.
 */
export function ensureLayerRect(layer: RasterLayer, x: number, y: number, w: number, h: number, pad = 0): void {
  const x0 = Math.floor(Math.min(layer.x, x - pad));
  const y0 = Math.floor(Math.min(layer.y, y - pad));
  const x1 = Math.ceil(Math.max(layer.x + (layer.pixels?.width ?? 0), x + w + pad));
  const y1 = Math.ceil(Math.max(layer.y + (layer.pixels?.height ?? 0), y + h + pad));
  if (layer.pixels && layer.x === x0 && layer.y === y0 && layer.pixels.width === x1 - x0 && layer.pixels.height === y1 - y0) return;
  const next = emptyPixels(x1 - x0, y1 - y0);
  if (layer.pixels) {
    const dx = layer.x - x0, dy = layer.y - y0;
    const src = layer.pixels;
    for (let row = 0; row < src.height; row++) {
      const from = row * src.width * 4;
      const to = ((row + dy) * next.width + dx) * 4;
      next.data.set(src.data.subarray(from, from + src.width * 4), to);
    }
  }
  layer.pixels = next;
  layer.x = x0; layer.y = y0;
  layer.w = next.width; layer.h = next.height;
}

export function fillPixels(layer: RasterLayer, color: RGBA): void {
  if (!layer.pixels) return;
  const d = layer.pixels.data;
  for (let i = 0; i < d.length; i += 4) {
    d[i] = color.r; d[i + 1] = color.g; d[i + 2] = color.b; d[i + 3] = color.a;
  }
  touch(layer);
}

/** Fills a document-space rectangle (growing the layer if needed). */
export function fillRect(layer: RasterLayer, x: number, y: number, w: number, h: number, color: RGBA): void {
  ensureLayerRect(layer, x, y, w, h);
  const px = layer.pixels!;
  const lx = Math.max(0, Math.round(x - layer.x));
  const ly = Math.max(0, Math.round(y - layer.y));
  const rx = Math.min(px.width, Math.round(x - layer.x + w));
  const ry = Math.min(px.height, Math.round(y - layer.y + h));
  for (let row = ly; row < ry; row++) {
    for (let col = lx; col < rx; col++) {
      const i = (row * px.width + col) * 4;
      px.data[i] = color.r; px.data[i + 1] = color.g; px.data[i + 2] = color.b; px.data[i + 3] = color.a;
    }
  }
  touch(layer);
}

/** Removes fully transparent rows/columns around the layer (smaller files). */
export function trimLayer(layer: RasterLayer): boolean {
  const px = layer.pixels;
  if (!px) return false;
  let minX = px.width, minY = px.height, maxX = -1, maxY = -1;
  for (let y = 0; y < px.height; y++) {
    for (let x = 0; x < px.width; x++) {
      if (px.data[(y * px.width + x) * 4 + 3] > 0) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
      }
    }
  }
  if (maxX < 0) {
    layer.pixels = null; layer.w = 0; layer.h = 0; touch(layer);
    return true;
  }
  if (minX === 0 && minY === 0 && maxX === px.width - 1 && maxY === px.height - 1) return false;
  const w = maxX - minX + 1, h = maxY - minY + 1;
  const next = emptyPixels(w, h);
  for (let row = 0; row < h; row++) {
    const from = ((row + minY) * px.width + minX) * 4;
    next.data.set(px.data.subarray(from, from + w * 4), row * w * 4);
  }
  layer.x += minX; layer.y += minY;
  layer.pixels = next; layer.w = w; layer.h = h;
  touch(layer);
  return true;
}

/* ------------------------------------------------------------------ undo patches */

/**
 * A tile of an edited area, addressed in **document** space. Document
 * coordinates make the patch stable: a layer rectangle may grow while you paint
 * (Photoshop does the same), which shifts layer-local coordinates, but never
 * moves document content.
 */
export interface TilePatch {
  docX: number;
  docY: number;
  w: number;
  h: number;
  /** Pixels before the edit (undo) and after it (redo). */
  before: Uint8ClampedArray;
  after?: Uint8ClampedArray;
}

/**
 * Records the pixels of a layer *before* they are modified, in tiles, so a
 * brush stroke only ever stores the few hundred KB it actually touched.
 */
export class PixelUndo {
  /**
   * One entry per 96 px grid cell that the edit touched. A cell stores a dense
   * block covering *(cell ∩ layer rectangle at capture time)*, so the stored
   * rectangle is always completely read and always nested inside the next one
   * (layer rectangles only ever grow). That invariant is what makes undo exact
   * even when a stroke grows the layer halfway through.
   */
  private patches = new Map<string, TilePatch>();
  private static CELL = 96;

  constructor(private layer: RasterLayer) {}

  /** Records the pixels the given document-space rectangle is about to change. */
  capture(docX: number, docY: number, w: number, h: number): void {
    const px = this.layer.pixels;
    if (!px) return;
    const cell = PixelUndo.CELL;
    const rx0 = Math.max(docX, this.layer.x);
    const ry0 = Math.max(docY, this.layer.y);
    const rx1 = Math.min(docX + w, this.layer.x + px.width);
    const ry1 = Math.min(docY + h, this.layer.y + px.height);
    if (rx1 <= rx0 || ry1 <= ry0) return;
    for (let cy = Math.floor(ry0 / cell) * cell; cy < ry1; cy += cell) {
      for (let cx = Math.floor(rx0 / cell) * cell; cx < rx1; cx += cell) {
        // the stored block is exactly cell ∩ layer rectangle
        const x0 = Math.max(cx, this.layer.x);
        const y0 = Math.max(cy, this.layer.y);
        const x1 = Math.min(cx + cell, this.layer.x + px.width);
        const y1 = Math.min(cy + cell, this.layer.y + px.height);
        if (x1 <= x0 || y1 <= y0) continue;
        this.ensureCell(`${cx},${cy}`, x0, y0, x1, y1);
      }
    }
  }

  /** Ensures a cell holds the before-pixels of `(x0,y0)-(x1,y1)` (nested growth). */
  private ensureCell(key: string, x0: number, y0: number, x1: number, y1: number): void {
    const existing = this.patches.get(key);
    if (existing &&
      existing.docX <= x0 && existing.docY <= y0 &&
      existing.docX + existing.w >= x1 && existing.docY + existing.h >= y1) {
      return;
    }
    const newX0 = existing ? Math.min(existing.docX, x0) : x0;
    const newY0 = existing ? Math.min(existing.docY, y0) : y0;
    const newX1 = existing ? Math.max(existing.docX + existing.w, x1) : x1;
    const newY1 = existing ? Math.max(existing.docY + existing.h, y1) : y1;
    const before = new Uint8ClampedArray((newX1 - newX0) * (newY1 - newY0) * 4);
    if (existing) {
      for (let row = 0; row < existing.h; row++) {
        before.set(
          existing.before.subarray(row * existing.w * 4, (row + 1) * existing.w * 4),
          ((row + existing.docY - newY0) * (newX1 - newX0) + (existing.docX - newX0)) * 4,
        );
      }
      // Only the bands the old rectangle did not cover can hold unread pixels —
      // everything else keeps the values captured before it was painted.
      for (const band of complementBands(existing, { x: x0, y: y0, w: x1 - x0, h: y1 - y0 })) {
        this.readInto(before, newX0, newY0, newX1 - newX0, band.x, band.y, band.x + band.w, band.y + band.h);
      }
    } else {
      this.readInto(before, newX0, newY0, newX1 - newX0, x0, y0, x1, y1);
    }
    this.patches.set(key, { docX: newX0, docY: newY0, w: newX1 - newX0, h: newY1 - newY0, before });
  }

  /** Copies a document-space sub-rectangle out of the layer (transparent outside it). */
  private readInto(dest: Uint8ClampedArray, destX: number, destY: number, destW: number, x0: number, y0: number, x1: number, y1: number): void {
    const px = this.layer.pixels;
    if (!px) return;
    // clamp to the layer rectangle: everything outside it is transparent
    const startX = Math.max(x0, this.layer.x);
    const endX = Math.min(x1, this.layer.x + px.width);
    const startY = Math.max(y0, this.layer.y);
    const endY = Math.min(y1, this.layer.y + px.height);
    if (endX <= startX || endY <= startY) return;
    const lx = startX - this.layer.x;
    for (let y = startY; y < endY; y++) {
      const ly = y - this.layer.y;
      if (ly < 0 || ly >= px.height) continue;
      const from = (ly * px.width + lx) * 4;
      const to = ((y - destY) * destW + (startX - destX)) * 4;
      dest.set(px.data.subarray(from, from + (endX - startX) * 4), to);
    }
  }

  get empty(): boolean { return this.patches.size === 0; }
  get byteSize(): number { let n = 0; for (const p of this.patches.values()) n += p.before.byteLength; return n; }

  /** Captures the *current* pixels of every touched cell, so redo is possible. */
  toPatch(): PixelPatch | null {
    if (!this.patches.size) return null;
    for (const tile of this.patches.values()) {
      const after = new Uint8ClampedArray(tile.w * tile.h * 4);
      this.readInto(after, tile.docX, tile.docY, tile.w, tile.docX, tile.docY, tile.docX + tile.w, tile.docY + tile.h);
      tile.after = after;
    }
    return { layerId: this.layer.id, tiles: [...this.patches.values()] };
  }
}

export interface PixelPatch { layerId: string; tiles: TilePatch[] }

/** Applies a patch in either direction (`before` = undo, `after` = redo). */
export function restorePatch(doc: Doc, patch: PixelPatch, direction: 'before' | 'after' = 'before'): void {
  const layer = findLayerById(doc, patch.layerId);
  if (!layer || !isRaster(layer) || !layer.pixels) return;
  const px = layer.pixels;
  for (const tile of patch.tiles) {
    const source = direction === 'before' ? tile.before : tile.after;
    if (!source) continue;
    const lx = tile.docX - layer.x, ly = tile.docY - layer.y;
    for (let row = 0; row < tile.h; row++) {
      if (ly + row < 0 || ly + row >= px.height) continue;
      const to = ((row + ly) * px.width + lx) * 4;
      if (lx < 0 || to < 0 || to + tile.w * 4 > px.data.length) continue;
      px.data.set(source.subarray(row * tile.w * 4, (row + 1) * tile.w * 4), to);
    }
  }
  touch(layer);
}

/* ------------------------------------------------------------------ brush strokes */

export interface BrushOptions {
  size: number;
  hardness: number; // 0..1
  opacity: number;  // 0..1
  color: RGBA;
  mode: 'brush' | 'eraser';
}

interface Rect { x: number; y: number; w: number; h: number }

/**
 * A single continuous stroke. Dab coverage is accumulated in a Float32 mask so
 * that overlapping dabs inside one stroke do not build past the opacity, just
 * like Photoshop's default brush behaviour.
 */
export class Stroke {
  private mask: Float32Array | null = null;
  private rect: Rect = { x: 0, y: 0, w: 0, h: 0 };
  private pending: Rect | null = null;
  private undo: PixelUndo;
  private last: { x: number; y: number } | null = null;

  constructor(private layer: RasterLayer, private options: BrushOptions) {
    this.undo = new PixelUndo(layer);
  }

  /** The layer this stroke writes into. */
  get target(): RasterLayer { return this.layer; }

  get patch(): PixelPatch | null { return this.undo.toPatch(); }

  /** Stamps along a segment, spacing dabs so the stroke looks continuous. */
  lineTo(x: number, y: number): void {
    if (!this.last) { this.dab(x, y); this.last = { x, y }; return; }
    const dx = x - this.last.x, dy = y - this.last.y;
    const distance = Math.hypot(dx, dy);
    const spacing = Math.max(1, this.options.size * 0.18);
    const steps = Math.max(1, Math.min(4000, Math.ceil(distance / spacing)));
    for (let i = 1; i <= steps; i++) {
      this.dab(this.last.x + (dx * i) / steps, this.last.y + (dy * i) / steps);
    }
    this.last = { x, y };
  }

  private dab(cx: number, cy: number): void {
    const radius = Math.max(0.5, this.options.size / 2);
    const x0 = Math.floor(cx - radius), y0 = Math.floor(cy - radius);
    const w = Math.ceil(radius * 2) + 1, h = w;
    this.growTo({ x: x0, y: y0, w, h });
    const rect = this.rect, mask = this.mask!;
    const inner = radius * Math.max(0, Math.min(1, this.options.hardness));
    const feather = Math.max(0.5, radius - inner);
    for (let y = Math.max(y0, rect.y); y < Math.min(y0 + h, rect.y + rect.h); y++) {
      for (let x = Math.max(x0, rect.x); x < Math.min(x0 + w, rect.x + rect.w); x++) {
        const dist = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
        if (dist > radius) continue;
        const coverage = dist <= inner ? 1 : 1 - smoothstep((dist - inner) / feather);
        if (coverage <= 0) continue;
        const idx = (y - rect.y) * rect.w + (x - rect.x);
        const next = Math.max(mask[idx], coverage);
        if (next !== mask[idx]) {
          mask[idx] = next;
          this.markPending(x, y);
        }
      }
    }
  }

  private markPending(x: number, y: number): void {
    if (!this.pending) this.pending = { x, y, w: 1, h: 1 };
    else {
      const px1 = this.pending.x + this.pending.w, py1 = this.pending.y + this.pending.h;
      this.pending.x = Math.min(this.pending.x, x);
      this.pending.y = Math.min(this.pending.y, y);
      this.pending.w = Math.max(px1, x + 1) - this.pending.x;
      this.pending.h = Math.max(py1, y + 1) - this.pending.y;
    }
  }

  private growTo(rect: Rect): void {
    if (this.mask && rect.x >= this.rect.x && rect.y >= this.rect.y &&
      rect.x + rect.w <= this.rect.x + this.rect.w && rect.y + rect.h <= this.rect.y + this.rect.h) {
      return;
    }
    const margin = Math.max(4, Math.ceil(this.options.size));
    const x0 = this.mask ? Math.min(this.rect.x, rect.x) - margin : rect.x - margin;
    const y0 = this.mask ? Math.min(this.rect.y, rect.y) - margin : rect.y - margin;
    const x1 = this.mask ? Math.max(this.rect.x + this.rect.w, rect.x + rect.w) + margin : rect.x + rect.w + margin;
    const y1 = this.mask ? Math.max(this.rect.y + this.rect.h, rect.y + rect.h) + margin : rect.y + rect.h + margin;
    const next = new Float32Array((x1 - x0) * (y1 - y0));
    if (this.mask) {
      for (let y = 0; y < this.rect.h; y++) {
        const from = y * this.rect.w;
        const to = (y + (this.rect.y - y0)) * (x1 - x0) + (this.rect.x - x0);
        next.set(this.mask.subarray(from, from + this.rect.w), to);
      }
    }
    this.mask = next;
    this.rect = { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
  }

  /** Composites pending coverage into the layer (called after each pointer move).
   *  Returns the document-space rectangle that changed, for a minimal repaint. */
  flush(): { x: number; y: number; w: number; h: number } | null {
    if (!this.pending || !this.mask) return null;
    const rect = this.pending;
    this.pending = null;

    // Grow the layer rectangle if the stroke went outside it (Photoshop does this
    // too). The padding is only head-room so mid-stroke flushes rarely have to
    // reallocate — it is transparent, never visible.
    ensureLayerRect(this.layer, rect.x, rect.y, rect.w, rect.h, Math.ceil(this.options.size / 2) + 4);
    const px = this.layer.pixels;
    if (!px) return null;
    const lx = Math.max(0, rect.x - this.layer.x);
    const ly = Math.max(0, rect.y - this.layer.y);
    const lw = Math.min(px.width - lx, rect.w);
    const lh = Math.min(px.height - ly, rect.h);
    if (lw <= 0 || lh <= 0) return null;

    // tiles are captured in document space so that later growth of the layer
    // rectangle cannot invalidate them
    this.undo.capture(this.layer.x + lx, this.layer.y + ly, lw, lh);
    const { color, opacity, mode } = this.options;
    const srcA = (color.a / 255);
    for (let y = 0; y < lh; y++) {
      const maskRow = (ly + this.layer.y - this.rect.y) * this.rect.w + (lx + this.layer.x - this.rect.x);
      const pxRow = ((y + ly) * px.width + lx) * 4;
      for (let x = 0; x < lw; x++) {
        const coverage = this.mask[maskRow + x];
        if (coverage <= 0) continue;
        const i = pxRow + x * 4;
        if (mode === 'eraser') {
          px.data[i + 3] = Math.round(px.data[i + 3] * (1 - coverage * opacity));
          continue;
        }
        const sa = coverage * opacity * srcA;
        if (sa <= 0) continue;
        const da = px.data[i + 3] / 255;
        const outA = sa + da * (1 - sa);
        if (outA <= 0) { px.data[i + 3] = 0; continue; }
        px.data[i] = Math.round((color.r * sa + px.data[i] * da * (1 - sa)) / outA);
        px.data[i + 1] = Math.round((color.g * sa + px.data[i + 1] * da * (1 - sa)) / outA);
        px.data[i + 2] = Math.round((color.b * sa + px.data[i + 2] * da * (1 - sa)) / outA);
        px.data[i + 3] = Math.round(outA * 255);
      }
    }
    touch(this.layer);
    // the layer rectangle may have grown, so repaint the whole touched area
    return { x: this.layer.x + lx, y: this.layer.y + ly, w: lw, h: lh };
  }
}

/** The parts of `rect` that `taken` does not already cover (at most four bands). */
function complementBands(taken: { docX: number; docY: number; w: number; h: number }, rect: Rect): Rect[] {
  const bands: Rect[] = [];
  const tx0 = taken.docX, ty0 = taken.docY, tx1 = taken.docX + taken.w, ty1 = taken.docY + taken.h;
  const rx0 = rect.x, ry0 = rect.y, rx1 = rect.x + rect.w, ry1 = rect.y + rect.h;
  // rows above the taken rectangle
  if (ry0 < ty0) bands.push({ x: rx0, y: ry0, w: rx1 - rx0, h: Math.min(ty0, ry1) - ry0 });
  // rows below it
  if (ry1 > ty1) bands.push({ x: rx0, y: Math.max(ty1, ry0), w: rx1 - rx0, h: ry1 - Math.max(ty1, ry0) });
  // columns left / right of it, within the overlapping rows
  const midY0 = Math.max(ry0, ty0);
  const midY1 = Math.min(ry1, ty1);
  if (midY1 > midY0) {
    if (rx0 < tx0) bands.push({ x: rx0, y: midY0, w: tx0 - rx0, h: midY1 - midY0 });
    if (rx1 > tx1) bands.push({ x: tx1, y: midY0, w: rx1 - tx1, h: midY1 - midY0 });
  }
  return bands;
}

function smoothstep(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

/* ------------------------------------------------------------------ text */

export function updateTextLayer(layer: TextLayer, patch: Partial<TextData>): void {
  layer.text = { ...layer.text, ...patch };
  if (patch.font) layer.text.font = { ...layer.text.font, ...patch.font };
  touch(layer);
}

/** Total pixel area of a document's layers (used for the memory hint). */
export function documentPixelCount(doc: Doc): number {
  let total = 0;
  walkLayers(doc.children, (l) => {
    if (isRaster(l) && l.pixels) total += l.pixels.width * l.pixels.height;
  });
  return total;
}

export function defaultTextData(overrides: Partial<TextData> = {}): TextData { return createTextData(overrides); }
