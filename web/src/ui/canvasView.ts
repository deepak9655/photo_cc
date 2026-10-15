/**
 * The document viewport.
 *
 * Performance model (this is what keeps the app light on a phone):
 *  - The document is composited into one off-screen canvas at 1:1 ("back").
 *    Panning/zooming only blits that canvas, so 60fps gestures never touch the
 *    layer stack.
 *  - Edits invalidate a *rectangle*, and only that rectangle is recomposited.
 *  - Documents too large for an off-screen buffer (> 12 Mpx, where iOS Safari
 *    starts failing canvas allocations) fall back to compositing the visible
 *    region straight into the screen canvas with layer culling.
 */

import { Renderer, blendIsApproximate, createCanvas, hitTest, type CanvasLike } from '../core/render';
import { isGroup, type Doc, type Layer } from '../core/types';

export interface Viewport { scale: number; x: number; y: number }
export interface Rect { x: number; y: number; w: number; h: number }

export interface ViewHooks {
  /** Called when a paint-ish gesture asks for the layer it should edit. */
  requestPaintTarget(x: number, y: number): Layer | null;
  /** Called with the doc-space point of a tap (for selection / text creation). */
  onTap(x: number, y: number, layer: Layer | null): void;
  /** Called when a layer drag (move tool) finished, for history. */
  onLayerDragStart(layer: Layer): void;
  onLayerDragMove(layer: Layer, dx: number, dy: number): void;
  onLayerDragEnd(): void;
  /** Called when a stroke should begin/continue/end. */
  onStrokeBegin(x: number, y: number): boolean;
  onStrokeMove(x: number, y: number): void;
  onStrokeEnd(): void;
  /** Called while dragging a fill rectangle. */
  onFillRect(rect: Rect | null): void;
  onFillCommit(rect: Rect): void;
  onViewportChange(): void;
}

const MAX_BACKING_PX = 12_000_000;   // off-screen 1:1 buffer limit
const MAX_SCREEN_PX = 4_000_000;     // on-screen canvas limit (device pixels)

export class CanvasView {
  readonly canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private stage: HTMLElement;
  private renderer: Renderer | null = null;
  private doc: Doc | null = null;
  private back: HTMLCanvasElement | null = null;

  viewport: Viewport = { scale: 1, x: 0, y: 0 };
  private dirty: Rect | null = null;
  private fullDirty = true;
  private rafPending = false;
  private dpr = 1;
  private pointers = new Map<number, { x: number; y: number; type: string }>();
  private gesture: 'none' | 'tool' | 'pinch' | 'pan' = 'none';
  private pinchStart: { dist: number; cx: number; cy: number; scale: number; x: number; y: number } | null = null;
  private panStart: { x: number; y: number; vx: number; vy: number } | null = null;
  private toolActive = false;
  private tapCandidate: { x: number; y: number; time: number; moved: boolean } | null = null;
  private dragLayer: Layer | null = null;
  private dragLast: { x: number; y: number } | null = null;
  private dragStarted = false;
  private fillStart: { x: number; y: number } | null = null;
  private fillRect: Rect | null = null;
  private lastPointer: { x: number; y: number } | null = null;

  tool: 'move' | 'brush' | 'eraser' | 'fill' | 'text' = 'brush';
  /** Set by the app while a stroke is being recorded by history. */
  hiddenIds: Set<string> | undefined;

  constructor(stage: HTMLElement, private hooks: ViewHooks) {
    this.stage = stage;
    this.canvas = document.getElementById('view') as HTMLCanvasElement;
    this.ctx = this.canvas.getContext('2d')!;
    this.bindEvents();
    this.resize();
    new ResizeObserver(() => this.resize()).observe(stage);
  }

  setDocument(doc: Doc | null): void {
    this.doc = doc;
    this.detachRenderer();
    if (doc) {
      this.renderer = new Renderer(doc);
      const px = doc.width * doc.height;
      this.back = px <= MAX_BACKING_PX ? createCanvas(doc.width, doc.height) as HTMLCanvasElement : null;
      this.showEmpty(false);
      this.fit();
    } else {
      this.back = null;
      this.showEmpty(true);
    }
    this.invalidate();
  }

  get rendererInstance(): Renderer | null { return this.renderer; }

  private detachRenderer(): void {
    this.renderer?.clear();
    this.renderer = null;
  }

  private showEmpty(show: boolean): void {
    const empty = document.getElementById('empty-state');
    if (empty) empty.hidden = !show;
  }

  /* ------------------------------------------------------------ geometry */

  private stageSize(): { w: number; h: number } {
    const r = this.stage.getBoundingClientRect();
    return { w: Math.max(1, r.width), h: Math.max(1, r.height) };
  }

  private resize(): void {
    const { w, h } = this.stageSize();
    let dpr = Math.min(window.devicePixelRatio || 1, 2);
    // keep the on-screen buffer inside a sane budget on huge screens
    const want = w * h * dpr * dpr;
    if (want > MAX_SCREEN_PX) dpr = Math.max(1, Math.sqrt(MAX_SCREEN_PX / (w * h)));
    this.dpr = dpr;
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.canvas.style.width = `${w}px`;
    this.canvas.style.height = `${h}px`;
    this.invalidate();
  }

  screenToDoc(sx: number, sy: number): { x: number; y: number } {
    return { x: (sx - this.viewport.x) / this.viewport.scale, y: (sy - this.viewport.y) / this.viewport.scale };
  }

  private eventPoint(e: PointerEvent): { x: number; y: number } {
    const r = this.stage.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  /** Zoom limits: 4% up to 3200%, and never smaller than a quarter of "fit". */
  private limits(): { min: number; max: number } {
    const doc = this.doc!;
    const { w, h } = this.stageSize();
    const fit = Math.min(w / doc.width, h / doc.height);
    return { min: Math.max(0.04, fit * 0.25), max: 32 };
  }

  setZoom(scale: number, cx?: number, cy?: number): void {
    if (!this.doc) return;
    const { min, max } = this.limits();
    const next = Math.max(min, Math.min(max, scale));
    const { w, h } = this.stageSize();
    const px = cx ?? w / 2;
    const py = cy ?? h / 2;
    const before = this.screenToDoc(px, py);
    this.viewport.scale = next;
    this.viewport.x = px - before.x * next;
    this.viewport.y = py - before.y * next;
    this.invalidate();
    this.hooks.onViewportChange();
  }

  zoomBy(factor: number): void { this.setZoom(this.viewport.scale * factor); }

  fit(): void {
    if (!this.doc) return;
    const { w, h } = this.stageSize();
    const scale = Math.min(w / this.doc.width, h / this.doc.height) * 0.94;
    this.viewport.scale = scale;
    this.viewport.x = (w - this.doc.width * scale) / 2;
    this.viewport.y = (h - this.doc.height * scale) / 2;
    this.invalidate();
    this.hooks.onViewportChange();
  }

  /* ------------------------------------------------------------ gestures */

  private bindEvents(): void {
    const c = this.canvas;
    c.addEventListener('pointerdown', this.onDown, { passive: false });
    c.addEventListener('pointermove', this.onMove, { passive: false });
    c.addEventListener('pointerup', this.onUp);
    c.addEventListener('pointercancel', this.onUp);
    c.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse') this.lastPointer = null; });
    c.addEventListener('wheel', this.onWheel, { passive: false });
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener('dblclick', (e) => {
      const p = this.eventPoint(e as unknown as PointerEvent);
      const doc = this.screenToDoc(p.x, p.y);
      this.hooks.onTap(doc.x, doc.y, hitTest(this.doc!, doc.x, doc.y));
    });
  }

  private onWheel = (e: WheelEvent): void => {
    if (!this.doc) return;
    e.preventDefault();
    const p = this.eventPoint(e as unknown as PointerEvent);
    if (e.ctrlKey || e.metaKey || Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
      const factor = Math.exp(-e.deltaY * 0.0022);
      this.setZoom(this.viewport.scale * factor, p.x, p.y);
    } else {
      this.viewport.x -= e.deltaX;
      this.viewport.y -= e.deltaY;
      this.invalidate();
      this.hooks.onViewportChange();
    }
  };

  private onDown = (e: PointerEvent): void => {
    if (!this.doc) return;
    this.canvas.setPointerCapture?.(e.pointerId);
    const p = this.eventPoint(e);
    this.pointers.set(e.pointerId, { x: p.x, y: p.y, type: e.pointerType });

    if (this.pointers.size === 2) {
      // second finger: cancel any tool gesture and switch to pinch/pan
      if (this.toolActive) { this.hooks.onStrokeEnd(); this.toolActive = false; }
      if (this.dragStarted) this.hooks.onLayerDragEnd();
      this.dragLayer = null; this.dragLast = null; this.dragStarted = false;
      this.fillStart = null; this.fillRect = null;
      const [a, b] = [...this.pointers.values()];
      this.gesture = 'pinch';
      this.pinchStart = {
        dist: Math.hypot(a.x - b.x, a.y - b.y),
        cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2,
        scale: this.viewport.scale, x: this.viewport.x, y: this.viewport.y,
      };
      return;
    }
    if (this.pointers.size > 2) return;

    const doc = this.screenToDoc(p.x, p.y);
    this.gesture = 'tool';
    this.tapCandidate = { x: p.x, y: p.y, time: performance.now(), moved: false };
    this.lastPointer = p;

    if (this.tool === 'brush' || this.tool === 'eraser') {
      this.toolActive = this.hooks.onStrokeBegin(doc.x, doc.y);
    } else if (this.tool === 'fill') {
      this.fillStart = { x: doc.x, y: doc.y };
      this.fillRect = null;
    } else if (this.tool === 'move') {
      this.dragLayer = hitTest(this.doc!, doc.x, doc.y) ?? this.selectedLayer();
      this.dragLast = { x: doc.x, y: doc.y };
      this.dragStarted = false;
    } else if (this.tool === 'text') {
      // handled on tap
    }
    e.preventDefault();
  };

  private onMove = (e: PointerEvent): void => {
    if (!this.doc) return;
    const p = this.eventPoint(e);
    this.lastPointer = p;
    if (this.pointers.has(e.pointerId)) this.pointers.set(e.pointerId, { x: p.x, y: p.y, type: e.pointerType });

    if (this.gesture === 'pinch' && this.pointers.size >= 2 && this.pinchStart) {
      const [a, b] = [...this.pointers.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
      const start = this.pinchStart;
      const { min, max } = this.limits();
      const scale = Math.max(min, Math.min(max, start.scale * (dist / Math.max(1, start.dist))));
      const anchor = { x: (start.cx - start.x) / start.scale, y: (start.cy - start.y) / start.scale };
      this.viewport.scale = scale;
      this.viewport.x = cx - anchor.x * scale;
      this.viewport.y = cy - anchor.y * scale;
      this.invalidate();
      this.hooks.onViewportChange();
      e.preventDefault();
      return;
    }

    if (this.gesture !== 'tool') return;
    if (this.tapCandidate && Math.hypot(p.x - this.tapCandidate.x, p.y - this.tapCandidate.y) > 8) this.tapCandidate.moved = true;
    const doc = this.screenToDoc(p.x, p.y);

    if (this.toolActive) {
      this.hooks.onStrokeMove(doc.x, doc.y);
    } else if (this.fillStart) {
      const x = Math.min(this.fillStart.x, doc.x), y = Math.min(this.fillStart.y, doc.y);
      const w = Math.abs(doc.x - this.fillStart.x), h = Math.abs(doc.y - this.fillStart.y);
      this.fillRect = { x, y, w, h };
      this.hooks.onFillRect(this.fillRect);
      this.invalidate();
    } else if (this.dragLayer && this.dragLast) {
      const dx = doc.x - this.dragLast.x, dy = doc.y - this.dragLast.y;
      this.dragLast = { x: doc.x, y: doc.y };
      if (!this.dragStarted) {
        // wait for the movement threshold so a plain tap stays a selection
        if (Math.hypot(dx, dy) < 1) return;
        this.dragStarted = true;
        this.hooks.onLayerDragStart(this.dragLayer);
      }
      this.hooks.onLayerDragMove(this.dragLayer, dx, dy);
    }
    e.preventDefault();
  };

  private onUp = (e: PointerEvent): void => {
    this.pointers.delete(e.pointerId);
    if (this.pointers.size >= 1) {
      // still pinching/panning: rebuild the pinch baseline with the remaining finger
      if (this.pointers.size === 1 && this.gesture === 'pinch') {
        const [a] = [...this.pointers.values()];
        this.gesture = 'pan';
        this.panStart = { x: a.x, y: a.y, vx: this.viewport.x, vy: this.viewport.y };
      }
      return;
    }
    if (this.pointers.size === 0 && this.gesture === 'pan' && this.panStart) {
      this.gesture = 'none';
      this.panStart = null;
      return;
    }
    if (!this.doc) { this.gesture = 'none'; return; }

    const p = this.lastPointer ?? this.eventPoint(e);
    const doc = this.screenToDoc(p.x, p.y);

    if (this.toolActive) { this.hooks.onStrokeEnd(); this.toolActive = false; }

    if (this.dragLayer) {
      if (this.dragStarted) this.hooks.onLayerDragEnd();
      this.dragLayer = null;
      this.dragLast = null;
      this.dragStarted = false;
    }

    if (this.fillStart && this.fillRect && this.fillRect.w > 2 && this.fillRect.h > 2) {
      this.hooks.onFillCommit(this.fillRect);
    } else if (this.fillStart) {
      // a tap with the fill tool fills a small default square so it is usable one-handed
      const size = 80;
      this.hooks.onFillCommit({ x: doc.x - size / 2, y: doc.y - size / 2, w: size, h: size });
    }
    this.fillStart = null;
    this.fillRect = null;
    this.hooks.onFillRect(null);

    // taps (no drag) select / create / edit
    const isTap = this.tapCandidate && !this.tapCandidate.moved && performance.now() - this.tapCandidate.time < 700;
    this.tapCandidate = null;
    this.gesture = 'none';
    if (isTap) this.hooks.onTap(doc.x, doc.y, hitTest(this.doc!, doc.x, doc.y));
    this.invalidate();
  };

  /** The app tells the view which layer is selected (used by the move tool). */
  selected: Layer | null = null;
  private selectedLayer(): Layer | null { return this.selected; }

  /* ------------------------------------------------------------ painting */

  /** Marks a document-space rectangle as needing recomposite. */
  invalidate(rect?: Rect | null): void {
    if (!rect) { this.fullDirty = true; }
    else if (!this.fullDirty) {
      const r = { x: Math.floor(rect.x), y: Math.floor(rect.y), w: Math.ceil(rect.w), h: Math.ceil(rect.h) };
      if (!this.dirty) this.dirty = r;
      else {
        const x1 = Math.max(this.dirty.x + this.dirty.w, r.x + r.w);
        const y1 = Math.max(this.dirty.y + this.dirty.h, r.y + r.h);
        this.dirty.x = Math.min(this.dirty.x, r.x);
        this.dirty.y = Math.min(this.dirty.y, r.y);
        this.dirty.w = x1 - this.dirty.x;
        this.dirty.h = y1 - this.dirty.y;
      }
    }
    this.schedule();
  }

  private schedule(): void {
    if (this.rafPending) return;
    this.rafPending = true;
    requestAnimationFrame(() => { this.rafPending = false; this.render(); });
  }

  private render(): void {
    const { w, h } = this.stageSize();
    const ctx = this.ctx;
    const dpr = this.dpr;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    if (!this.doc || !this.renderer) return;

    if (this.back) {
      if (this.fullDirty) {
        this.renderer.composite(this.back as unknown as CanvasLike, 1);
        this.fullDirty = false;
        this.dirty = null;
      } else if (this.dirty) {
        this.renderer.composite(this.back as unknown as CanvasLike, 1, { region: this.dirty, visibleRect: this.dirty });
        this.dirty = null;
      }
      const vp = this.viewport;
      ctx.imageSmoothingEnabled = true;
      ctx.drawImage(
        this.back,
        vp.x * dpr, vp.y * dpr,
        this.doc.width * vp.scale * dpr, this.doc.height * vp.scale * dpr,
      );
    } else {
      const vp = this.viewport;
      const visible: Rect = {
        x: Math.max(0, -vp.x / vp.scale), y: Math.max(0, -vp.y / vp.scale),
        w: Math.min(this.doc.width, w / vp.scale), h: Math.min(this.doc.height, h / vp.scale),
      };
      this.renderer.composite(this.canvas as unknown as CanvasLike, vp.scale * dpr, {
        offsetX: vp.x * dpr, offsetY: vp.y * dpr, visibleRect: visible,
      });
      this.fullDirty = false;
      this.dirty = null;
    }

    // pixel-level work: nearest-neighbour above 300%
    this.canvas.classList.toggle('crisp', this.viewport.scale >= 3);
    void w; void h;
  }

  /** Approximate memory held by layer buffers + the backing canvas (for the info sheet). */
  memoryBytes(): number {
    const backPx = this.back ? this.back.width * this.back.height : 0;
    return (backPx + (this.renderer?.memoryPx ?? 0)) * 4;
  }

  dispose(): void { this.renderer?.dispose(); }
}

/** Names of layers whose blend mode the browser cannot reproduce exactly. */
export function approximateBlendLayers(doc: Doc): string[] {
  const notes: string[] = [];
  const walk = (layers: Layer[]) => {
    for (const l of layers) {
      if (l.blend && blendIsApproximate(l.blend)) notes.push(l.name);
      if (isGroup(l)) walk(l.children);
    }
  };
  walk(doc.children);
  return notes;
}
