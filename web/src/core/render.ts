/**
 * Rendering engine.
 *
 * Design goals (mobile-first, low CPU/RAM):
 *  - Layers are painted straight onto one compositing canvas; no per-frame
 *    full-resolution re-decode, no WebGL, no shaders.
 *  - Raster layers are uploaded to a canvas **once per change** and reused.
 *  - Layer groups are flattened into a buffer whose size is the *content* of the
 *    group, not the whole document.
 *  - Buffers come from a small canvas pool with an LRU budget, so a 4000x4000
 *    document does not silently allocate gigabytes.
 *  - Text is measured with the real font metrics of the device (cheap, cached)
 *    and drawn with the 2D API, which keeps text layers editable.
 */

import {
  isGroup, isRaster, isText,
  type Doc, type GroupLayer, type Layer, type RasterLayer, type TextData, type TextLayer,
} from './types';
import { rgbaToCss } from './color';

/* ------------------------------------------------------------------ canvas plumbing */

export interface CanvasLike {
  width: number;
  height: number;
  getContext(id: '2d', attrs?: any): CanvasRenderingContext2D | null;
  style?: any;
}

/** Indirection so the Node test harness can inject a canvas implementation. */
let canvasFactory: (w: number, h: number) => CanvasLike = (w, h) => {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  return c as unknown as CanvasLike;
};

export function setCanvasFactory(f: (w: number, h: number) => CanvasLike): void { canvasFactory = f; }
export function createCanvas(w: number, h: number): CanvasLike { return canvasFactory(Math.max(1, w), Math.max(1, h)); }

export function ctx2d(canvas: CanvasLike): CanvasRenderingContext2D {
  const ctx = canvas.getContext('2d', { willReadFrequently: false });
  if (!ctx) throw new Error('2D canvas is not available on this device');
  return ctx;
}

/**
 * Reusable canvases. Layer/group buffers are drawn from here and held while
 * their cache key is valid; `trim` releases the least recently touched ones.
 */
export class CanvasPool {
  private free: CanvasLike[] = [];
  private totalPx = 0;

  acquire(w: number, h: number): CanvasLike {
    w = Math.max(1, Math.ceil(w)); h = Math.max(1, Math.ceil(h));
    // pick the smallest pooled canvas that fits, but never one more than ~1.6x
    // the area we asked for: a huge reused buffer would waste RAM on a phone.
    const limit = w * h * 1.6;
    let best = -1;
    for (let i = 0; i < this.free.length; i++) {
      const c = this.free[i];
      if (c.width >= w && c.height >= h && c.width * c.height <= limit) {
        if (best < 0 || c.width * c.height < this.free[best].width * this.free[best].height) best = i;
      }
    }
    if (best >= 0) return this.free.splice(best, 1)[0];
    return createCanvas(w, h);
  }

  resize(canvas: CanvasLike, w: number, h: number): CanvasLike {
    w = Math.max(1, Math.ceil(w)); h = Math.max(1, Math.ceil(h));
    if (canvas.width < w || canvas.height < h) {
      // grow: only ever reallocate bigger, never smaller (avoids churn)
      canvas.width = Math.max(w, canvas.width);
      canvas.height = Math.max(h, canvas.height);
    }
    return canvas;
  }

  release(canvas: CanvasLike): void {
    if (this.free.length < 8) this.free.push(canvas);
  }

  get bytesEstimate(): number { return this.totalPx * 4; }
  notePx(px: number): void { this.totalPx += px; }

  /** Frees pooled canvases until the given pixel budget is respected. */
  trim(maxPx: number): void {
    while (this.free.length > 1 && this.totalPx > maxPx) {
      const c = this.free.pop()!;
      this.totalPx -= c.width * c.height;
    }
  }

  dispose(): void { this.free = []; this.totalPx = 0; }
}

/* ------------------------------------------------------------------ text layout */

export interface TextLine { text: string; width: number; start: number }
export interface TextGeometry {
  lines: TextLine[];
  lineHeight: number;
  ascent: number;
  descent: number;
  width: number;
  height: number;
  /** Offset from the block top to the first baseline. */
  firstBaseline: number;
  font: string;
}

const geomCache = new Map<string, TextGeometry>();

function fontKey(t: TextData): string {
  return `${t.fauxItalic ? 'i' : ''}${t.font.weight}|${t.font.style}|${t.size}|${t.font.family}`;
}

export function fontString(t: TextData): string {
  const italic = t.fauxItalic || t.font.style === 'italic' ? 'italic ' : '';
  return `${italic}${t.font.weight} ${t.size}px ${t.font.family}`;
}

/** `letterSpacing` exists on Chrome/Android WebViews; we degrade gracefully elsewhere. */
function supportsLetterSpacing(ctx: CanvasRenderingContext2D): boolean {
  return 'letterSpacing' in ctx;
}

function applyTracking(ctx: CanvasRenderingContext2D, t: TextData): void {
  if (supportsLetterSpacing(ctx)) {
    (ctx as any).letterSpacing = `${(t.tracking / 1000) * t.size}px`;
  }
}

/** Cached text layout: identical style+text is measured once. */
export function textGeometry(t: TextData, measureCtx?: CanvasRenderingContext2D): TextGeometry {
  let geometry = geomCache.get(geomCacheKey(t));
  if (geometry) return geometry;
  geometry = layoutText(t, measureCtx ?? sharedMeasureCtx());
  geomCache.set(geomCacheKey(t), geometry);
  if (geomCache.size > 200) geomCache.delete(geomCache.keys().next().value as string);
  return geometry;
}

function geomCacheKey(t: TextData): string {
  return `${fontKey(t)}|${t.tracking}|${t.leading ?? 'a'}|${t.text}`;
}

let measureCanvas: CanvasRenderingContext2D | null = null;
function sharedMeasureCtx(): CanvasRenderingContext2D {
  if (!measureCanvas) measureCanvas = ctx2d(createCanvas(8, 8));
  return measureCanvas;
}

/** Lays the string out line by line. Newlines are the only break we do (no wrapping) — like Photoshop's point text. */
export function layoutText(t: TextData, ctx: CanvasRenderingContext2D): TextGeometry {
  ctx.save();
  ctx.font = fontString(t);
  applyTracking(ctx, t);
  const m = ctx.measureText('Hg');
  let ascent = m.actualBoundingBoxAscent || t.size * 0.8;
  let descent = m.actualBoundingBoxDescent || t.size * 0.2;
  // `actualBoundingBox*` of "Hg" tracks the glyphs; use the font box for layout stability
  if (m.fontBoundingBoxAscent) ascent = m.fontBoundingBoxAscent;
  if (m.fontBoundingBoxDescent) descent = m.fontBoundingBoxDescent;

  const raw = t.text.split('\n');
  const lines: TextLine[] = [];
  let start = 0;
  let width = 0;
  for (const line of raw) {
    const w = line.length ? ctx.measureText(line).width : 0;
    lines.push({ text: line, width: w, start });
    width = Math.max(width, w);
    start += line.length + 1; // + '\n'
  }
  ctx.restore();

  const lineHeight = t.leading && t.leading > 0 ? t.leading : Math.round(t.size * 1.2);
  const height = lines.length <= 1 ? ascent + descent : (lines.length - 1) * lineHeight + ascent + descent;
  return { lines, lineHeight, ascent, descent, width, height, firstBaseline: ascent, font: fontString(t) };
}

/** Number of characters before `caret` inside the flat text of a text layer. */
export function lineStarts(g: TextGeometry): number[] { return g.lines.map((l) => l.start); }

/* ------------------------------------------------------------------ drawing */

function withTransform(ctx: CanvasRenderingContext2D, layer: TextLayer, fn: () => void): void {
  const t = layer.text;
  ctx.save();
  ctx.font = fontString(t);
  applyTracking(ctx, t);
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'left';
  if (t.fauxItalic) {
    // shear around the first baseline so the block stays roughly in place
    ctx.transform(1, 0, -0.21, 1, (layer.y + textGeometry(t).firstBaseline) * 0.21, 0);
  }
  fn();
  ctx.restore();
}

/** Draws one line, character by character, emulating tracking on browsers without `letterSpacing`. */
function drawLine(ctx: CanvasRenderingContext2D, line: TextLine, x: number, baseline: number, trackingPx: number, filled: boolean): void {
  if (!line.text.length) return;
  if (!trackingPx || supportsLetterSpacing(ctx)) {
    if (filled) ctx.fillText(line.text, x, baseline);
    else ctx.strokeText(line.text, x, baseline);
    return;
  }
  let cx = x;
  for (const ch of line.text) {
    if (filled) ctx.fillText(ch, cx, baseline);
    else ctx.strokeText(ch, cx, baseline);
    cx += ctx.measureText(ch).width + trackingPx;
  }
}

/**
 * Paints a text layer with its fill/stroke/drop-shadow. `overrideText` is used by
 * the on-canvas editor so the live text shows immediately.
 */
export function drawTextLayer(
  ctx: CanvasRenderingContext2D,
  layer: TextLayer,
  overrideText?: string,
  measureCtx?: CanvasRenderingContext2D,
): void {
  const t = overrideText !== undefined ? { ...layer.text, text: overrideText } : layer.text;
  const g = textGeometry(t, measureCtx);
  const trackingPx = supportsLetterSpacing(ctx) ? 0 : (t.tracking / 1000) * t.size;
  const originX = layer.x;
  const firstBaseline = layer.y + g.firstBaseline;

  withTransform(ctx, { ...layer, text: t } as TextLayer, () => {
    for (let i = 0; i < g.lines.length; i++) {
      const line = g.lines[i];
      const dx = t.align === 'center' ? (g.width - line.width) / 2 : t.align === 'right' ? g.width - line.width : 0;
      const x = originX + dx;
      const baseline = firstBaseline + i * g.lineHeight;

      if (layer.shadow.enabled) {
        ctx.save();
        ctx.shadowColor = rgbaToCss(layer.shadow.color);
        ctx.shadowBlur = layer.shadow.blur;
        ctx.shadowOffsetX = layer.shadow.dx;
        ctx.shadowOffsetY = layer.shadow.dy;
        ctx.fillStyle = rgbaToCss(t.color);
        drawLine(ctx, line, x, baseline, trackingPx, true);
        ctx.restore();
      }

      if (layer.stroke.enabled && layer.stroke.width > 0) {
        ctx.save();
        ctx.strokeStyle = rgbaToCss(layer.stroke.color);
        ctx.lineWidth = layer.stroke.width;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        drawLine(ctx, line, x, baseline, trackingPx, false);
        ctx.restore();
      }

      ctx.fillStyle = rgbaToCss(t.color);
      drawLine(ctx, line, x, baseline, trackingPx, true);

      if (t.underline) {
        const thickness = Math.max(1, Math.round(t.size / 16));
        const y = baseline + Math.max(1, Math.round(t.size * 0.12));
        ctx.fillRect(x, y, line.width, thickness);
      }
    }
  });
}

/** Paints a raster layer's pixels. */
export function drawRasterLayer(ctx: CanvasRenderingContext2D, layer: RasterLayer, source?: CanvasLike): void {
  if (!layer.pixels && !source) return;
  if (source) {
    ctx.drawImage(source as any, 0, 0, layer.w, layer.h, layer.x, layer.y, layer.w, layer.h);
    return;
  }
  const px = layer.pixels!;
  const tmp = createCanvas(px.width, px.height);
  const tctx = ctx2d(tmp);
  tctx.putImageData(new ImageData(px.data as any, px.width, px.height), 0, 0);
  ctx.drawImage(tmp as any, layer.x, layer.y, layer.w, layer.h);
}

/* ------------------------------------------------------------------ geometry */

export interface Rect { x: number; y: number; w: number; h: number }

export function textBounds(layer: TextLayer): Rect {
  const g = textGeometry(layer.text);
  return { x: layer.x, y: layer.y, w: Math.max(g.width, 1), h: Math.max(g.height, 1) };
}

/** Content rectangle of a layer, including effects padding. */
export function layerBounds(layer: Layer): Rect | null {
  if (isRaster(layer)) return layer.w > 0 && layer.h > 0 ? { x: layer.x, y: layer.y, w: layer.w, h: layer.h } : null;
  if (isText(layer)) {
    const b = textBounds(layer);
    const pad = effectsPad(layer.stroke.width, layer.shadow.blur, layer.shadow.dx, layer.shadow.dy);
    return { x: b.x - pad, y: b.y - pad, w: b.w + pad * 2, h: b.h + pad * 2 };
  }
  const rects = layer.children.map(layerBounds).filter(Boolean) as Rect[];
  if (!rects.length) return null;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const r of rects) {
    x0 = Math.min(x0, r.x); y0 = Math.min(y0, r.y);
    x1 = Math.max(x1, r.x + r.w); y1 = Math.max(y1, r.y + r.h);
  }
  return { x: Math.floor(x0), y: Math.floor(y0), w: Math.ceil(x1 - x0), h: Math.ceil(y1 - y0) };
}

function effectsPad(stroke: number, blur: number, dx: number, dy: number): number {
  return Math.ceil(Math.max(stroke * 1.5, blur * 2 + Math.max(Math.abs(dx), Math.abs(dy)), 2));
}

/* ------------------------------------------------------------------ renderer */

interface CacheEntry { key: string; canvas: CanvasLike; w: number; h: number; px: number; touched: number }

export interface CompositeOptions {
  /** Layers to skip (used by "hide others" while editing). */
  hideIds?: Set<string>;
  /** Draw only this layer (canvas-level isolation). */
  onlyIds?: Set<string> | null;
  /** Draw an extra overlay in document space after everything else (guides, marching ants). */
  overlay?: (ctx: CanvasRenderingContext2D) => void;
  /**
   * Redraw only this document-space rectangle. Everything else on the target is
   * left untouched, which is what makes brush strokes cheap: a 200x200 dab
   * repaints 200x200 pixels, not the whole document.
   */
  region?: Rect;
  /** Skip layers that do not intersect this rectangle (viewport culling). */
  visibleRect?: Rect;
  /** Device-pixel offset applied after the scale (view transform). */
  offsetX?: number;
  offsetY?: number;
}

export class Renderer {
  private cache = new Map<string, CacheEntry>(); // layer id -> flattened buffer (groups / raster uploads)
  private pool: CanvasPool;
  private clock = 0;
  private cull: Rect | null = null;
  doc: Doc;

  constructor(doc: Doc, pool = new CanvasPool()) {
    this.doc = doc;
    this.pool = pool;
  }

  setDoc(doc: Doc): void { this.doc = doc; this.clear(); }

  clear(): void {
    for (const entry of this.cache.values()) this.pool.release(entry.canvas);
    this.cache.clear();
  }

  /** Total pixel count currently held in layer buffers + pool (for the status bar). */
  get memoryPx(): number {
    let px = 0;
    for (const e of this.cache.values()) px += e.px;
    return px;
  }

  private ensure(layer: Layer, key: string, w: number, h: number, draw: (ctx: CanvasRenderingContext2D) => void): CanvasLike {
    this.clock++;
    const existing = this.cache.get(layer.id);
    if (existing && existing.key === key && existing.w >= w && existing.h >= h) {
      existing.touched = this.clock;
      return existing.canvas;
    }
    let canvas: CanvasLike;
    if (existing && existing.w >= w && existing.h >= h) {
      canvas = existing.canvas;
      this.cache.delete(layer.id);
    } else {
      if (existing) { this.pool.release(existing.canvas); this.cache.delete(layer.id); }
      canvas = this.pool.acquire(w, h);
    }
    canvas.width = Math.max(w, canvas.width);
    canvas.height = Math.max(h, canvas.height);
    const ctx = ctx2d(canvas);
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    draw(ctx);
    this.cache.set(layer.id, { key, canvas, w: canvas.width, h: canvas.height, px: canvas.width * canvas.height, touched: this.clock });
    this.trimToBudget();
    return canvas;
  }

  /** Drops least-recently-used buffers so total canvases stay inside the budget. */
  private trimToBudget(): void {
    const doc = this.doc;
    const budget = Math.max(8_000_000, doc.width * doc.height * 2);
    const entries = [...this.cache.entries()].sort((a, b) => a[1].touched - b[1].touched);
    let total = entries.reduce((sum, [, e]) => sum + e.px, 0);
    for (const [id, e] of entries) {
      if (total <= budget) break;
      this.pool.release(e.canvas);
      this.cache.delete(id);
      total -= e.px;
    }
    this.pool.trim(budget);
  }

  /** Signature of everything that affects a layer's *own* flattened pixels. */
  private signature(layer: Layer): string {
    if (isRaster(layer)) return `r${layer.rev}:${layer.x},${layer.y}`;
    if (isText(layer)) return `t${layer.rev}:${layer.x},${layer.y}`;
    return `g${layer.children.map((c) => this.signature(c)).join(',')}`;
  }

  /** Flattened canvas for a group (in group-local coordinates offset by `origin`). */
  private groupBuffer(group: GroupLayer, hideIds?: Set<string>): { canvas: CanvasLike; ox: number; oy: number; w: number; h: number } | null {
    const rects = group.children.filter((c) => !hideIds?.has(c.id)).map(layerBounds).filter(Boolean) as Rect[];
    if (!rects.length) return null;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const r of rects) {
      x0 = Math.min(x0, r.x); y0 = Math.min(y0, r.y);
      x1 = Math.max(x1, r.x + r.w); y1 = Math.max(y1, r.y + r.h);
    }
    const ox = Math.floor(x0), oy = Math.floor(y0);
    const w = Math.max(1, Math.ceil(x1 - x0)), h = Math.max(1, Math.ceil(y1 - y0));
    const key = this.signature(group) + (hideIds?.size ? `|h${[...hideIds].sort().join('.')}` : '');
    const canvas = this.ensure(group, key, w, h, (ctx) => {
      ctx.translate(-ox, -oy);
      for (const child of group.children) this.paint(ctx, child, hideIds);
    });
    return { canvas, ox, oy, w, h };
  }

  /** Canvas holding a raster layer's pixels (cached until `rev` changes). */
  private rasterBuffer(layer: RasterLayer): { canvas: CanvasLike; x: number; y: number; w: number; h: number } | null {
    if (!layer.pixels || layer.w <= 0 || layer.h <= 0) return null;
    const px = layer.pixels;
    const canvas = this.ensure(layer, `r${layer.rev}`, px.width, px.height, (ctx) => {
      ctx.putImageData(new ImageData(px.data as any, px.width, px.height), 0, 0);
    });
    return { canvas, x: layer.x, y: layer.y, w: px.width, h: px.height };
  }

  /** Paints one layer (and its children) applying its own opacity / blend mode. */
  private paint(ctx: CanvasRenderingContext2D, layer: Layer, hideIds: Set<string> | undefined): void {
    if (!layer.visible || hideIds?.has(layer.id) || layer.unsupported) return;
    if (this.cull) {
      const b = layerBounds(layer);
      if (b && !rectsIntersect(b, this.cull)) return;
    }

    ctx.save();
    if (layer.opacity < 1) ctx.globalAlpha *= layer.opacity;
    const blend = blendForCanvas(layer.blend);
    if (blend !== 'source-over') ctx.globalCompositeOperation = blend;

    if (isRaster(layer)) {
      const buf = this.rasterBuffer(layer);
      // 9-argument form: pooled canvases can be larger than the content area,
      // so the source rectangle has to be explicit or the image would stretch.
      if (buf) ctx.drawImage(buf.canvas as any, 0, 0, buf.w, buf.h, buf.x, buf.y, buf.w, buf.h);
    } else if (isText(layer)) {
      drawTextLayer(ctx, layer);
    } else {
      const buf = this.groupBuffer(layer, hideIds);
      if (buf) ctx.drawImage(buf.canvas as any, 0, 0, buf.w, buf.h, buf.ox, buf.oy, buf.w, buf.h);
    }
    ctx.restore();
  }

  /** Full (or partial) document composite into `target` at the given scale (device px per doc px). */
  composite(target: CanvasLike, scale: number, options: CompositeOptions = {}): void {
    const ctx = ctx2d(target);
    const doc = this.doc;
    const region = options.region;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';

    if (region) {
      ctx.clearRect(
        Math.floor(region.x * scale), Math.floor(region.y * scale),
        Math.ceil(region.w * scale) + 1, Math.ceil(region.h * scale) + 1,
      );
      ctx.save();
      ctx.beginPath();
      ctx.rect(Math.floor(region.x * scale), Math.floor(region.y * scale), Math.ceil(region.w * scale) + 1, Math.ceil(region.h * scale) + 1);
      ctx.clip();
    } else {
      ctx.clearRect(0, 0, target.width, target.height);
    }

    ctx.setTransform(scale, 0, 0, scale, options.offsetX ?? 0, options.offsetY ?? 0);
    this.cull = options.visibleRect ?? region ?? null;

    const hideIds = options.hideIds;
    const only = options.onlyIds ?? null;
    for (const layer of doc.children) {
      if (only && !only.has(layer.id)) continue;
      this.paint(ctx, layer, hideIds);
    }
    if (options.overlay) options.overlay(ctx);
    if (region) ctx.restore();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    this.cull = null;
  }

  /** Documents maps of unknown blend modes to a canvas-compatible mode. */
  dispose(): void { this.clear(); this.pool.dispose(); }
}

export function rectsIntersect(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

export function blendForCanvas(mode: string): GlobalCompositeOperation {
  switch (mode) {
    case 'multiply': case 'screen': case 'overlay': case 'darken': case 'lighten':
    case 'color-dodge': case 'color-burn': case 'difference': case 'exclusion':
    case 'hue': case 'saturation': case 'color': case 'luminosity':
      return mode as GlobalCompositeOperation;
    case 'color dodge': return 'color-dodge';
    case 'color burn': return 'color-burn';
    case 'hard light': return 'hard-light';
    case 'soft light': return 'soft-light';
    case 'linear dodge': return 'lighter';
    default: return 'source-over';
  }
}

/** Topmost layer under a document-space point (used for tap-to-select). */
export function hitTest(doc: Doc, x: number, y: number): Layer | null {
  const search = (layers: Layer[]): Layer | null => {
    for (let i = layers.length - 1; i >= 0; i--) {
      const l = layers[i];
      if (isGroup(l)) {
        const hit = search(l.children);
        if (hit) return hit;
        continue;
      }
      const b = layerBounds(l);
      if (b && x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) return l;
    }
    return null;
  };
  return search(doc.children);
}

/** Flattens a document into an ImageData (used for the PSD composite preview + export). */
export function flatten(doc: Doc, renderer?: Renderer): ImageData {
  const canvas = createCanvas(doc.width, doc.height);
  const ctx = ctx2d(canvas);
  const r = renderer ?? new Renderer(doc);
  r.composite(canvas, 1);
  return ctx.getImageData(0, 0, doc.width, doc.height);
}

/**
 * Which PSD blend modes the browser can reproduce exactly. Everything else is
 * approximated with `source-over` (we surface that as a note in the UI instead
 * of silently producing a different picture).
 */
export function blendIsApproximate(mode: string): boolean {
  return !(mode === 'normal' || mode === 'pass through' || mode === 'multiply' || mode === 'screen' ||
    mode === 'overlay' || mode === 'darken' || mode === 'lighten' || mode === 'color dodge' ||
    mode === 'color burn' || mode === 'hard light' || mode === 'soft light' || mode === 'difference' ||
    mode === 'exclusion' || mode === 'hue' || mode === 'saturation' || mode === 'color' ||
    mode === 'luminosity' || mode === 'linear dodge');
}
