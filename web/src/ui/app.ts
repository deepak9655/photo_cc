/**
 * Application controller: wires the core model to the mobile UI.
 *
 * Layout rule for small screens: the document fills the middle, every control
 * lives in the bottom third of the screen where a thumb can reach it, and no
 * panel ever covers the whole canvas.
 */

import {
  createTextData, isGroup, isRaster, isText, walkLayers,
  type Doc, type Layer, type RGBA, type RasterLayer, type Session, type TextLayer,
} from '../core/types';
import { rgbaToHex } from '../core/color';
import {
  PixelUndo, Stroke, createDocument, createRasterLayer, createTextLayer, documentPixelCount,
  duplicateLayer, fillRect, findLayerById, findPath, insertLayer, removeLayer, touch,
  trimLayer as trimLayerPixels,
} from '../core/ops';
import { Renderer, createCanvas, ctx2d, drawTextLayer, flatten, layerBounds } from '../core/render';
import { buildPsdFile, exportName, loadPsdBuffer, probeHeader, unsupportedReason } from '../core/psd';
import { CanvasView } from './canvasView';
import { History } from './history';
import { buildDemoDocument } from './demo';
import {
  ICONS, busy, clear, confirmSheet, el, iconBtn, nextFrame, showSheet, slider, toast, closeSheet,
} from './dom';
import {
  blendNote as blendNoteText, layerSummary, openBrushSheet, openColorSheet, openExportSheet,
  openHelpSheet, openInfoSheet, openLayerMenu, openNewDocSheet, openTextEditor, type PanelHost,
} from './panels';

type Tool = 'move' | 'brush' | 'eraser' | 'fill' | 'text';

/** Above this the "maximize compatibility" composite is skipped (phones cannot allocate it). */
const MAX_FLATTEN_PX = 14_000_000;

interface PendingProp { layer: Layer; key: string; before: unknown }

export class App implements PanelHost {
  session: Session | null = null;
  view: CanvasView;
  history: History;

  private stroke: Stroke | null = null;
  private brushSettings = { size: 48, hardness: 0.75, opacity: 1 };
  private currentColor: RGBA = { r: 255, g: 255, b: 255, a: 255 };
  private tool: Tool = 'brush';
  private selectedId: string | null = null;
  private textDefaults = createTextData({ text: 'New text', size: 72 });
  private thumbCache = new Map<string, { rev: number; canvas: HTMLCanvasElement }>();
  private thumbRenderer: Renderer | null = null;
  private pendingProp: { token: string; label: string; items: PendingProp[]; timer: number } | null = null;
  private dragSession: { layer: Layer; before: Map<string, { x: number; y: number }>; moved: boolean } | null = null;
  /**
   * Last known rectangle per layer. A layer can change size without moving
   * (retyping, changing the font size), so repainting only its *new* bounds
   * would leave the old pixels behind. Every partial repaint therefore also
   * covers the remembered rectangle.
   */
  private layerRects = new Map<string, { x: number; y: number; w: number; h: number }>();

  constructor() {
    this.history = new History(() => this.session!.doc, () => this.updateHistoryButtons());
    this.view = new CanvasView(document.getElementById('stage')!, {
      requestPaintTarget: (x, y) => this.paintTarget(x, y),
      onTap: (x, y, layer) => this.handleTap(x, y, layer),
      onLayerDragStart: (layer) => this.beginLayerDrag(layer),
      onLayerDragMove: (layer, dx, dy) => this.dragLayerBy(layer, dx, dy),
      onLayerDragEnd: () => this.commitLayerMove(),
      onStrokeBegin: (x, y) => this.beginStroke(x, y),
      onStrokeMove: (x, y) => this.moveStroke(x, y),
      onStrokeEnd: () => this.endStroke(),
      onFillRect: (rect) => this.view.invalidate(rect),
      onFillCommit: (rect) => this.commitFill(rect),
      onViewportChange: () => this.updateZoomLabel(),
    });
    this.bindChrome();
    this.setTool('brush');
    this.renderAll();
  }

  /* ------------------------------------------------------------ chrome */

  private bindChrome(): void {
    const openFile = () => (document.getElementById('file-input') as HTMLInputElement).click();
    for (const id of ['btn-open', 'btn-open-2']) document.getElementById(id)?.addEventListener('click', openFile);
    document.getElementById('btn-sample')?.addEventListener('click', () => this.loadDemo());
    document.getElementById('btn-new')?.addEventListener('click', () => openNewDocSheet(this));
    document.getElementById('file-input')?.addEventListener('change', (e) => {
      const input = e.target as HTMLInputElement;
      const file = input.files?.[0];
      input.value = '';
      if (file) void this.openFile(file);
    });
    document.getElementById('image-input')?.addEventListener('change', (e) => {
      const input = e.target as HTMLInputElement;
      const file = input.files?.[0];
      input.value = '';
      if (file) void this.openImageAsDocument(file);
    });

    document.getElementById('btn-save')?.addEventListener('click', () => {
      if (!this.session) { toast('Open a PSD first, or start a new document.', 'warn'); return; }
      openExportSheet(this);
    });
    document.getElementById('btn-undo')?.addEventListener('click', () => this.undo());
    document.getElementById('btn-redo')?.addEventListener('click', () => this.redo());
    document.getElementById('btn-menu')?.addEventListener('click', () => this.openMenu());

    document.getElementById('btn-zoom-in')?.addEventListener('click', () => this.view.zoomBy(1.4));
    document.getElementById('btn-zoom-out')?.addEventListener('click', () => this.view.zoomBy(1 / 1.4));
    document.getElementById('btn-fit')?.addEventListener('click', () => this.view.fit());

    for (const node of document.querySelectorAll<HTMLButtonElement>('.tool')) {
      node.addEventListener('click', () => this.setTool(node.dataset.tool as Tool));
    }

    const colorBtn = document.getElementById('btn-color')!;
    colorBtn.addEventListener('click', () => openColorSheet(this));
    attachLongPress(colorBtn, () => openColorSheet(this));

    document.getElementById('btn-layers-toggle')?.addEventListener('click', () => this.toggleStrip());
    document.getElementById('btn-add-layer')?.addEventListener('click', () => this.addRasterLayer());
    document.getElementById('btn-add-text')?.addEventListener('click', () => this.addTextLayer());
    document.getElementById('btn-layer-menu')?.addEventListener('click', () => {
      const layer = this.selected();
      if (!layer) { toast('No layer selected', 'warn'); this.openStrip(); return; }
      this.openLayerMenu(layer);
    });

    document.addEventListener('keydown', (e) => this.onKey(e));
    window.addEventListener('beforeunload', (e) => {
      if (this.session?.dirty) { e.preventDefault(); e.returnValue = ''; }
    });
  }

  private onKey(e: KeyboardEvent): void {
    const meta = e.ctrlKey || e.metaKey;
    const key = e.key.toLowerCase();
    if (meta && key === 'z') { e.preventDefault(); if (e.shiftKey) this.redo(); else this.undo(); return; }
    if (meta && key === 'y') { e.preventDefault(); this.redo(); return; }
    if (meta && key === 's') { e.preventDefault(); if (this.session) openExportSheet(this); return; }
    if (meta) return;
    const toolKeys: Record<string, Tool> = { b: 'brush', e: 'eraser', v: 'move', t: 'text', g: 'fill' };
    if (toolKeys[key]) { this.setTool(toolKeys[key]); return; }
    if (key === '+' || key === '=') this.view.zoomBy(1.3);
    if (key === '-') this.view.zoomBy(1 / 1.3);
    if (key === '0') this.view.fit();
  }

  /* ------------------------------------------------------------ files */

  async openFile(file: File): Promise<void> {
    try {
      busy('Reading file…');
      await nextFrame();
      const buffer = await file.arrayBuffer();
      const header = probeHeader(buffer);

      if (!header) {
        if (file.type.startsWith('image/')) { await this.openImageAsDocument(file); return; }
        toast('That file is not a Photoshop document. (PSD and PSB are supported.)', 'warn', 6000);
        return;
      }
      const reason = unsupportedReason(header);
      if (reason) { toast(reason, 'warn', 9000); return; }

      if (this.session?.dirty) {
        const proceed = await this.confirmDiscard();
        if (!proceed) return;
        busy('Reading file…');
      }

      busy(`Parsing layers… ${(buffer.byteLength / 1024 / 1024).toFixed(1)} MB`);
      await nextFrame();
      const started = performance.now();
      const loaded = loadPsdBuffer(buffer, file.name);
      this.setSession(loaded.session);
      const ms = Math.round(performance.now() - started);
      toast(`Opened ${file.name} — ${loaded.session.doc.children.length} top-level layers in ${ms} ms`, 'good');
      for (const note of loaded.warnings) toast(note, 'info', 6500);
      for (const note of this.blendWarnings().slice(0, 2)) toast(note, 'info', 7000);
    } catch (err: any) {
      toast(err?.message ?? String(err), 'warn', 9000);
    } finally {
      busy(null);
    }
  }

  private confirmDiscard(): Promise<boolean> {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value: boolean) => { if (!settled) { settled = true; resolve(value); } };
      confirmSheet(
        'Discard unsaved changes?',
        `“${this.session?.file.name}” has edits that are not saved yet.`,
        'Discard and open',
        () => finish(true),
        true,
      );
      // the sheet's cancel/backdrop path only closes, so also resolve on close
      const observer = new MutationObserver(() => {
        if (!document.querySelector('.sheet')) { observer.disconnect(); finish(false); }
      });
      observer.observe(document.body, { childList: true });
      setTimeout(() => { observer.disconnect(); finish(false); }, 120_000);
    });
  }

  private async openImageAsDocument(file: File): Promise<void> {
    const url = URL.createObjectURL(file);
    try {
      const img = await new Promise<HTMLImageElement>((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error('Could not decode that image'));
        image.src = url;
      });
      const maxSide = 4096;
      const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
      const w = Math.max(1, Math.round(img.width * scale));
      const h = Math.max(1, Math.round(img.height * scale));
      const canvas = createCanvas(w, h);
      const ctx = ctx2d(canvas);
      ctx.drawImage(img as unknown as CanvasImageSource, 0, 0, w, h);
      const data = ctx.getImageData(0, 0, w, h);
      const doc = createDocument(w, h);
      doc.children.push(createRasterLayer(file.name.replace(/\.[^.]+$/, ''), 0, 0, w, h, { width: w, height: h, data: data.data }));
      this.setSession({
        doc,
        meta: { colorMode: 'RGB', bitsPerChannel: 8, notes: [] },
        file: { name: file.name, outName: file.name.replace(/\.[^.]+$/, '') + '.psd', bytes: file.size },
        source: null,
        dirty: false,
      });
      toast(`Imported ${file.name} as a single-layer document`, 'good');
    } catch (err: any) {
      toast(err?.message ?? 'Unsupported image', 'warn');
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  private setSession(session: Session): void {
    this.session = session;
    this.selectedId = session.doc.children.at(-1)?.id ?? null;
    this.thumbCache.clear();
    this.thumbRenderer = null;
    this.pendingProp = null;
    this.history.clear();
    this.view.setDocument(session.doc);
    this.view.selected = this.selected();
    this.rememberAllLayerRects();
    this.renderAll();
  }

  private loadDemo(): void {
    this.setSession({
      doc: buildDemoDocument(),
      meta: { colorMode: 'RGB', bitsPerChannel: 8, notes: [] },
      file: { name: 'demo (generated)', outName: 'demo.psd', bytes: 0 },
      source: null,
      dirty: false,
    });
    toast('Demo document ready — try the brush, the text tool and the layer list.', 'good', 4200);
  }

  async exportPsd(options: { trim: boolean; invalidateText: boolean; compress: boolean; psb: boolean }): Promise<void> {
    if (!this.session) return;
    try {
      busy('Building PSD…');
      await nextFrame();
      const built = this.buildExport(options);
      const name = exportName(this.session);
      await this.writeOut(built, name, 'image/vnd.adobe.photoshop');
      this.session.dirty = false;
      this.updateTitle();
      toast(`Saved ${name} (${(built.byteLength / 1024 / 1024).toFixed(2)} MB)`, 'good', 5000);
    } catch (err: any) {
      toast(`Save failed: ${err?.message ?? err}`, 'warn', 9000);
    } finally {
      busy(null);
    }
  }

  async sharePsd(options: { trim: boolean; invalidateText: boolean; compress: boolean; psb: boolean }): Promise<void> {
    if (!this.session) return;
    try {
      busy('Preparing to share…');
      await nextFrame();
      const built = this.buildExport(options);
      const file = new File([built as unknown as BlobPart], exportName(this.session), { type: 'image/vnd.adobe.photoshop' });
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: exportName(this.session) });
      } else {
        toast('This browser cannot share files. Use Save instead.', 'warn');
      }
    } catch (err: any) {
      if (err?.name !== 'AbortError') toast(`Share failed: ${err?.message ?? err}`, 'warn');
    } finally {
      busy(null);
    }
  }

  private buildExport(options: { trim: boolean; invalidateText: boolean; compress: boolean; psb: boolean }): Uint8Array {
    const session = this.session!;
    const doc = session.doc;
    let composite: ImageData | null = null;
    if (doc.width * doc.height <= MAX_FLATTEN_PX) {
      composite = flatten(doc, this.view.rendererInstance ?? undefined);
    } else {
      toast('Document is very large: saving without an embedded preview.', 'info', 5000);
    }
    return buildPsdFile(session, composite, options).bytes;
  }

  async exportPng(): Promise<void> {
    if (!this.session) return;
    try {
      busy('Rendering PNG…');
      await nextFrame();
      const doc = this.session.doc;
      const canvas = createCanvas(doc.width, doc.height);
      const renderer = this.view.rendererInstance ?? new Renderer(doc);
      renderer.composite(canvas, 1);
      const blob: Blob = await new Promise((resolve, reject) => {
        (canvas as unknown as HTMLCanvasElement).toBlob((b) => (b ? resolve(b) : reject(new Error('toBlob failed'))), 'image/png');
      });
      await this.writeOut(blob, this.session.file.outName.replace(/\.psd$/i, '.png'), 'image/png');
      toast('PNG exported', 'good', 2600);
    } catch (err: any) {
      toast(`PNG export failed: ${err?.message ?? err}`, 'warn');
    } finally {
      busy(null);
    }
  }

  private async writeOut(data: Uint8Array | Blob, name: string, mime: string): Promise<void> {
    const blob = data instanceof Blob ? data : new Blob([data as unknown as BlobPart], { type: mime });
    const picker = (window as any).showSaveFilePicker;
    if (typeof picker === 'function') {
      try {
        const ext = name.slice(name.lastIndexOf('.'));
        const handle = await picker({ suggestedName: name, types: [{ description: 'File', accept: { [mime]: [ext] } }] });
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
        return;
      } catch (err: any) {
        if (err?.name === 'AbortError') return;
        // not available / blocked: fall through to the download path
      }
    }
    const url = URL.createObjectURL(blob);
    const a = el('a', { href: url, download: name, style: 'display:none' });
    document.body.append(a);
    a.click();
    setTimeout(() => { a.remove(); URL.revokeObjectURL(url); }, 10_000);
  }

  /* ------------------------------------------------------------ menu & chrome state */

  private openMenu(): void {
    const body = el('div', { class: 'list' });
    const item = (label: string, sub: string, fn: () => void) => el('button', {
      class: 'menu-item',
      onclick: () => { closeSheet(); fn(); },
    }, el('span', { class: 'grow' }, label, el('small', {}, sub)));

    body.append(
      item('New document', 'Start an empty layered document', () => openNewDocSheet(this)),
      item('Open a PSD file', 'PSD / PSB, RGB · grayscale · indexed', () => (document.getElementById('file-input') as HTMLInputElement).click()),
      item('Import an image', 'JPG / PNG / WebP as a single layer', () => (document.getElementById('image-input') as HTMLInputElement).click()),
      item('Demo document', 'Generated on this device, nothing downloaded', () => this.loadDemo()),
      item('Save as PSD or PNG', 'Keeps layers, styles, masks and editable text', () => (this.session ? openExportSheet(this) : toast('Nothing to save yet', 'warn'))),
      item('Brush settings', 'Size, hardness, opacity', () => openBrushSheet(this)),
      item('Document info', 'Size, layers, memory, zoom', () => openInfoSheet(this)),
      item('How to use it', 'Gestures, tools, what is preserved', () => openHelpSheet()),
    );
    showSheet({
      title: 'Menu',
      body,
      actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Close')],
    });
  }

  setTool(tool: Tool): void {
    this.tool = tool;
    this.view.tool = tool;
    for (const node of document.querySelectorAll<HTMLButtonElement>('.tool')) {
      node.setAttribute('aria-pressed', String(node.dataset.tool === tool));
    }
    this.renderProps();
  }

  private toggleStrip(): void {
    const app = document.getElementById('app')!;
    const open = app.dataset.sheet === 'layers';
    app.dataset.sheet = open ? 'none' : 'layers';
    document.getElementById('btn-layers-toggle')?.setAttribute('aria-expanded', String(!open));
    if (!open) this.renderLayers();
  }

  private openStrip(): void {
    const app = document.getElementById('app')!;
    app.dataset.sheet = 'layers';
    document.getElementById('btn-layers-toggle')?.setAttribute('aria-expanded', 'true');
    this.renderLayers();
  }

  private updateZoomLabel(): void {
    const label = document.getElementById('btn-fit');
    if (label) label.textContent = `${Math.round(this.view.viewport.scale * 100)}%`;
  }

  /* ------------------------------------------------------------ selection & tools */

  selected(): Layer | null {
    if (!this.session || !this.selectedId) return null;
    return findLayerById(this.session.doc, this.selectedId) ?? null;
  }

  selectLayer(id: string): void {
    this.selectedId = id;
    this.view.selected = this.selected();
    this.renderLayers();
    this.renderProps();
  }

  private handleTap(x: number, y: number, layer: Layer | null): void {
    if (this.tool === 'text') {
      if (layer && isText(layer)) { this.selectLayer(layer.id); openTextEditor(this, layer); return; }
      this.createTextAt(x, y);
      return;
    }
    if (layer && this.selected()?.id !== layer.id) this.selectLayer(layer.id);
  }

  /** Topmost raster layer whose rectangle contains the point (nothing else). */
  private rasterAt(x: number, y: number): RasterLayer | null {
    if (!this.session) return null;
    let hit: RasterLayer | null = null;
    const search = (layers: Layer[]) => {
      for (let i = layers.length - 1; i >= 0; i--) {
        const l = layers[i];
        if (isGroup(l)) { search(l.children); continue; }
        if (!isRaster(l) || l.unsupported || !l.visible) continue;
        const b = layerBounds(l);
        if (b && x >= b.x - 8 && x <= b.x + b.w + 8 && y >= b.y - 8 && y <= b.y + b.h + 8) { hit = l; return; }
      }
    };
    search(this.session.doc.children);
    return hit;
  }

  /**
   * The layer a brush stroke should write into: the selection when it is a
   * paintable raster layer, otherwise the layer under the finger.
   */
  private paintTarget(x: number, y: number): Layer | null {
    const selected = this.selected();
    if (selected && isRaster(selected) && !selected.locked && !selected.unsupported) return selected;
    if (selected && isText(selected)) {
      toast('Text layers have to be rasterized first — layer menu → “Rasterize text”.', 'warn', 4200);
      return null;
    }
    if (selected && isGroup(selected)) {
      toast('Select a layer inside the group to paint on it.', 'warn', 3600);
      return null;
    }
    const hit = this.rasterAt(x, y);
    if (hit) this.selectLayer(hit.id);
    return hit;
  }

  /* ------------------------------------------------------------ painting */

  private beginStroke(x: number, y: number): boolean {
    const layer = this.paintTarget(x, y) as RasterLayer | null;
    if (!layer) { toast('No raster layer to paint on — add one with “+ Layer”.', 'warn', 3600); return false; }
    this.stroke = new Stroke(layer, {
      size: this.brushSettings.size,
      hardness: this.brushSettings.hardness,
      opacity: this.brushSettings.opacity,
      color: this.currentColor,
      mode: this.tool === 'eraser' ? 'eraser' : 'brush',
    });
    this.stroke.lineTo(x, y);
    const rect = this.stroke.flush();
    if (rect) this.view.invalidate(rect);
    this.rememberLayerRect(layer);
    this.markDirty();
    return true;
  }

  private moveStroke(x: number, y: number): void {
    if (!this.stroke) return;
    this.stroke.lineTo(x, y);
    const rect = this.stroke.flush();
    if (rect) this.view.invalidate(rect);
    const target = this.stroke.target;
    if (target) this.rememberLayerRect(target);
  }

  private endStroke(): void {
    if (!this.stroke) return;
    const target = this.stroke.target;
    const rect = this.stroke.flush();
    if (rect) this.view.invalidate(rect);
    const patch = this.stroke.patch;
    const label = this.tool === 'eraser' ? 'Erase' : 'Brush';
    this.stroke = null;
    this.history.pushPixelPatch(label, patch);
    this.markDirty();
    if (target) this.rememberLayerRect(target);
    this.refreshThumbFor(this.selected());
  }

  private commitFill(rect: { x: number; y: number; w: number; h: number }): void {
    if (!this.session) return;
    const selected = this.selected();
    // fill the selected layer when it can hold pixels, otherwise whatever is under the box
    const target = selected && isRaster(selected) && !selected.unsupported
      ? selected
      : this.rasterAt(rect.x + rect.w / 2, rect.y + rect.h / 2);
    if (!target || !isRaster(target)) { toast('Select a raster layer to fill — text layers need rasterizing first.', 'warn', 3800); return; }
    this.selectLayer(target.id);
    const undo = new PixelUndo(target);
    undo.capture(rect.x, rect.y, rect.w, rect.h);
    fillRect(target, rect.x, rect.y, rect.w, rect.h, this.currentColor);
    this.history.pushPixelPatch('Fill', undo.toPatch());
    this.view.invalidate({ x: rect.x - 2, y: rect.y - 2, w: rect.w + 4, h: rect.h + 4 });
    this.rememberLayerRect(target);
    this.markDirty();
    this.refreshThumbFor(target);
  }

  /* ------------------------------------------------------------ layer drag (move tool) */

  private beginLayerDrag(layer: Layer): void {
    const before = new Map<string, { x: number; y: number }>();
    const collect = (l: Layer) => {
      if (isGroup(l)) { l.children.forEach(collect); return; }
      before.set(l.id, { x: l.x, y: l.y });
    };
    collect(layer);
    this.dragSession = { layer, before, moved: false };
  }

  private dragLayerBy(layer: Layer, dx: number, dy: number): void {
    if (!this.session || (dx === 0 && dy === 0)) return;
    if (this.dragSession) this.dragSession.moved = true;
    // repaint the area the layer is leaving *and* the area it is entering
    const before = layerBounds(layer);
    const move = (l: Layer, mx: number, my: number) => {
      if (isGroup(l)) { l.children.forEach((c) => move(c, mx, my)); return; }
      l.x += mx; l.y += my;
      if (isText(l)) touch(l);
    };
    move(layer, dx, dy);
    const after = layerBounds(layer);
    this.view.invalidate(unionRect(before, after, 16));
    this.rememberLayerRect(layer);
    this.markDirty();
    this.renderLayersIfOpen();
  }

  private commitLayerMove(): void {
    const session = this.dragSession;
    this.dragSession = null;
    if (!session || !session.moved) return;
    const layer = session.layer;
    // a group drag moves its children; store the first child so undo works
    const moved = isGroup(layer) ? layer.children.find((c) => !isGroup(c)) : layer;
    if (!moved) return;
    const before = session.before.get(moved.id);
    if (!before) return;
    this.history.pushProps('Move layer', moved, { x: before.x, y: before.y }, { x: (moved as any).x, y: (moved as any).y });
    this.refreshThumbFor(layer);
  }

  /* ------------------------------------------------------------ layer operations */

  addRasterLayer(): void {
    if (!this.session) { toast('Open or create a document first.', 'warn'); return; }
    const doc = this.session.doc;
    const path = this.selectedId ? findPath(doc, this.selectedId) : null;
    const parent = path?.parent ?? null;
    const index = path ? path.index + 1 : doc.children.length;
    const layer = createRasterLayer(`Layer ${doc.children.length + 1}`, 0, 0, 0, 0, null);
    insertLayer(doc, layer, parent?.id ?? null, index);
    this.history.pushLayerAdd('Add layer', layer, parent?.id ?? null, index);
    this.selectedId = layer.id;
    this.view.selected = layer;
    this.markDirty();
    this.repaintAll();
    this.renderAll();
    this.openStrip();
    toast('Empty layer added — the brush paints into it.', 'good', 3000);
  }

  addTextLayer(): void {
    if (!this.session) { toast('Open or create a document first.', 'warn'); return; }
    const doc = this.session.doc;
    this.pushText(doc, Math.round(doc.width * 0.12), Math.round(doc.height * 0.4), 'New text');
  }

  private createTextAt(x: number, y: number): void {
    if (!this.session) { this.loadDemo(); return; }
    this.pushText(this.session.doc, Math.round(x), Math.round(y), 'New text');
  }

  private pushText(doc: Doc, x: number, y: number, text: string): void {
    const layer = createTextLayer(text, x, y, {
      ...this.textDefaults,
      text,
      color: this.currentColor.a === 0 ? { r: 255, g: 255, b: 255, a: 255 } : { ...this.currentColor },
      font: { ...this.textDefaults.font },
    });
    doc.children.push(layer);
    this.history.pushLayerAdd('Add text', layer, null, doc.children.length - 1);
    this.selectedId = layer.id;
    this.view.selected = layer;
    this.markDirty();
    this.repaintAll();
    this.renderAll();
    openTextEditor(this, layer);
  }

  duplicate(layer: Layer): void {
    if (!this.session) return;
    const doc = this.session.doc;
    const path = findPath(doc, layer.id);
    if (!path) return;
    const copy = duplicateLayer(layer);
    insertLayer(doc, copy, path.parent?.id ?? null, path.index + 1);
    this.history.pushLayerAdd('Duplicate layer', copy, path.parent?.id ?? null, path.index + 1);
    this.selectedId = copy.id;
    this.view.selected = copy;
    this.markDirty();
    this.repaintAll();
    this.renderAll();
    toast(`Duplicated “${layer.name}”`, 'good', 2200);
  }

  deleteLayer(layer: Layer): void {
    if (!this.session) return;
    const doc = this.session.doc;
    const path = findPath(doc, layer.id);
    if (!path) return;
    removeLayer(doc, layer.id);
    this.history.pushLayerRemove('Delete layer', layer, path.parent?.id ?? null, path.index);
    if (this.selectedId === layer.id) {
      this.selectedId = doc.children.at(-1)?.id ?? null;
      this.view.selected = this.selected();
    }
    this.markDirty();
    this.repaintAll();
    this.renderAll();
    toast(`Deleted “${layer.name}” — undo is available`, 'info', 3200);
  }

  moveInStack(layer: Layer, delta: number): void {
    if (!this.session) return;
    const doc = this.session.doc;
    const path = findPath(doc, layer.id);
    if (!path) return;
    const target = Math.max(0, Math.min(path.list.length - 1, path.index + delta));
    if (target === path.index) return;
    this.history.runState('Reorder layer', layer.id, () => {
      path.list.splice(path.index, 1);
      path.list.splice(target, 0, layer);
    });
    this.markDirty();
    this.repaintAll();
    this.renderAll();
  }

  rasterize(layer: Layer): void {
    if (!this.session || !isText(layer)) return;
    const doc = this.session.doc;
    const path = findPath(doc, layer.id);
    if (!path) return;
    const bounds = layerBounds(layer)!;
    const w = Math.max(1, Math.ceil(bounds.w));
    const h = Math.max(1, Math.ceil(bounds.h));
    const canvas = createCanvas(w, h);
    const ctx = ctx2d(canvas);
    ctx.translate(-bounds.x, -bounds.y);
    drawTextLayer(ctx, layer);
    const img = ctx.getImageData(0, 0, w, h);
    const raster = createRasterLayer(layer.name, Math.round(bounds.x), Math.round(bounds.y), w, h, { width: w, height: h, data: img.data });
    raster.opacity = layer.opacity;
    raster.blend = layer.blend;
    raster.visible = layer.visible;
    removeLayer(doc, layer.id);
    insertLayer(doc, raster, path.parent?.id ?? null, path.index);
    this.history.pushLayerRemove('Rasterize text', layer, path.parent?.id ?? null, path.index);
    this.history.pushLayerAdd('Rasterize text', raster, path.parent?.id ?? null, path.index);
    this.selectedId = raster.id;
    this.view.selected = raster;
    this.markDirty();
    this.repaintAll();
    this.renderAll();
    toast('Text rasterized — the brush and fill tools work on it now.', 'good', 3400);
  }

  private openLayerMenu(layer: Layer): void {
    openLayerMenu(this, layer, {
      onRename: () => {
        const body = el('div', {});
        body.append(el('div', { class: 'field' },
          el('label', {}, 'Layer name'),
          (() => {
            const input = el('input', { type: 'text', value: layer.name });
            input.addEventListener('input', () => { layer.name = (input as HTMLInputElement).value || 'Layer'; this.renderLayers(); });
            return input;
          })(),
        ));
        showSheet({
          title: 'Rename layer',
          body,
          actions: [el('button', {
            class: 'btn primary wide',
            onclick: () => { closeSheet(); this.flushPendingProp(); this.markDirty(); this.renderAll(); },
          }, 'Done')],
        });
      },
      onDuplicate: () => this.duplicate(layer),
      onDelete: () => this.deleteLayer(layer),
      onMove: (delta) => this.moveInStack(layer, delta),
      onRasterize: () => this.rasterize(layer),
      onTrim: () => {
        if (!isRaster(layer) || !layer.pixels) { toast('Nothing to trim on this layer.', 'warn'); return; }
        if (trimLayerPixels(layer)) {
          this.markDirty();
          this.repaintAll();
          this.refreshThumbFor(layer);
          this.renderAll();
          toast('Transparent edges removed.', 'good', 2400);
        } else {
          toast('This layer has no transparent edges.', 'info', 2400);
        }
      },
      onBlend: (mode) => {
        const before = { blend: layer.blend };
        layer.blend = mode as any;
        this.history.pushProps('Blend mode', layer, before, { blend: layer.blend });
        this.markDirty();
        this.repaintAll();
        const note = blendNoteText(mode);
        if (note) toast(note, 'info', 6500);
        this.renderAll();
      },
      onOpacity: (value) => {
        this.updateLayerProp(layer, 'opacity', value, 'Opacity', `opacity:${layer.id}`);
        this.repaintAll();
        this.renderLayersIfOpen();
      },
    });
  }

  /* ------------------------------------------------------------ dirty state + history */

  markDirty(): void {
    if (!this.session || this.session.dirty) return;
    this.session.dirty = true;
    this.updateTitle();
  }

  private updateHistoryButtons(): void {
    const undo = document.getElementById('btn-undo') as HTMLButtonElement | null;
    const redo = document.getElementById('btn-redo') as HTMLButtonElement | null;
    if (undo) undo.disabled = !this.history.canUndo;
    if (redo) redo.disabled = !this.history.canRedo;
  }

  private undo(): void {
    this.flushPendingProp();
    if (!this.history.undo()) { toast('Nothing to undo', 'info', 1600); return; }
    this.afterHistoryJump();
  }

  private redo(): void {
    this.flushPendingProp();
    if (!this.history.redo()) { toast('Nothing to redo', 'info', 1600); return; }
    this.afterHistoryJump();
  }

  private afterHistoryJump(): void {
    this.thumbCache.clear();
    this.layerRects.clear();
    this.repaintAll();
    this.renderAll();
  }

  /* ------------------------------------------------------------ PanelHost */

  doc(): Doc | null { return this.session?.doc ?? null; }
  brush() { return this.brushSettings; }
  setBrush(patch: Partial<{ size: number; hardness: number; opacity: number }>) {
    Object.assign(this.brushSettings, patch);
    this.renderProps();
  }
  color(): RGBA { return this.currentColor; }
  setColor(c: RGBA): void {
    this.currentColor = c;
    const swatch = document.getElementById('color-swatch');
    if (swatch) swatch.style.background = `rgba(${c.r},${c.g},${c.b},${c.a / 255})`;
  }

  addLayer(layer: Layer, label: string, index?: number): void {
    if (!this.session) return;
    const doc = this.session.doc;
    const at = index ?? doc.children.length;
    insertLayer(doc, layer, null, at);
    this.history.pushLayerAdd(label, layer, null, at);
    this.selectedId = layer.id;
    this.view.selected = layer;
    this.markDirty();
    this.repaintAll();
    this.renderAll();
  }

  openTextEditor(layer: TextLayer): void {
    this.selectedId = layer.id;
    this.view.selected = layer;
    this.renderLayers();
    openTextEditor(this, layer);
  }

  /**
   * Applies a layer property change and records it. Rapid changes on the same
   * key (dragging a slider, typing) are coalesced into a single undo step.
   */
  updateLayerProp(layer: Layer, key: string, value: unknown, label: string, coalesceKey?: string): void {
    const apply = PROP_APPLIERS[key];
    if (!apply) return;
    const before = captureProp(layer, key);
    apply(layer, value);
    if (key.startsWith('text')) touch(layer);
    this.markDirty();

    const token = coalesceKey ?? `${key}:${layer.id}`;
    const existing = this.pendingProp;
    if (existing && existing.token === token) {
      existing.items.push({ layer, key, before });
      window.clearTimeout(existing.timer);
      existing.timer = window.setTimeout(() => this.flushPendingProp(), 650);
      return;
    }
    this.flushPendingProp();
    this.pendingProp = {
      token,
      label,
      items: [{ layer, key, before }],
      timer: window.setTimeout(() => this.flushPendingProp(), 650),
    };
  }

  flushPendingProp(): void {
    const pending = this.pendingProp;
    if (!pending) return;
    this.pendingProp = null;
    window.clearTimeout(pending.timer);
    for (const item of pending.items) {
      const after = captureProp(item.layer, item.key);
      if (JSON.stringify(after) === JSON.stringify(item.before)) continue;
      this.history.pushProps(pending.label, item.layer, { [item.key]: item.before }, { [item.key]: after });
    }
  }

  newDocument(width: number, height: number, background: 'transparent' | 'white'): void {
    const w = Math.max(16, Math.min(16000, Math.round(width) || 1080));
    const h = Math.max(16, Math.min(16000, Math.round(height) || 1080));
    const doc = createDocument(w, h);
    if (background === 'white') {
      const data = new Uint8ClampedArray(w * h * 4);
      data.fill(255);
      doc.children.push(createRasterLayer('Background', 0, 0, w, h, { width: w, height: h, data }));
    }
    this.setSession({
      doc,
      meta: { colorMode: 'RGB', bitsPerChannel: 8, notes: [] },
      file: { name: 'Untitled', outName: 'untitled.psd', bytes: 0 },
      source: null,
      dirty: true,
    });
    toast(`New ${w} × ${h} document`, 'good', 2200);
  }

  documentStats() {
    const doc = this.session?.doc;
    return {
      layers: doc ? countLayers(doc) : 0,
      pixels: doc ? documentPixelCount(doc) : 0,
      memoryBytes: this.view.memoryBytes(),
      scale: this.view.viewport.scale,
    };
  }

  /**
   * Repaints the document, or just one layer when the caller knows what
   * changed — that is what keeps typing in a text layer cheap.
   */
  refreshCanvas(layer?: Layer | null): void {
    this.markDirty();
    const target = layer ?? null;
    if (target && !isGroup(target)) {
      const rect = unionRect(this.layerRects.get(target.id) ?? null, layerBounds(target), 12);
      if (rect) this.view.invalidate(rect);
      else this.repaintAll();
      this.rememberLayerRect(target);
    } else {
      this.repaintAll();
    }
    this.refreshThumbFor(target ?? this.selected());
  }

  /** Repaints everything (structural changes, undo, …) and re-syncs the rects. */
  private repaintAll(): void {
    this.rememberAllLayerRects();
    this.view.invalidate();
  }

  private rememberLayerRect(layer: Layer): void {
    if (isGroup(layer)) { this.layerRects.delete(layer.id); return; }
    const bounds = layerBounds(layer);
    if (bounds) this.layerRects.set(layer.id, bounds);
    else this.layerRects.delete(layer.id);
  }

  private rememberAllLayerRects(): void {
    if (!this.session) { this.layerRects.clear(); return; }
    walkLayers(this.session.doc.children, (layer) => this.rememberLayerRect(layer));
  }

  /* ------------------------------------------------------------ panels */

  renderAll(): void {
    this.renderLayers();
    this.renderProps();
    this.updateTitle();
    this.updateHistoryButtons();
  }

  private renderLayersIfOpen(): void {
    if (document.getElementById('app')!.dataset.sheet === 'layers') this.renderLayers();
  }

  private updateTitle(): void {
    const nameEl = document.getElementById('doc-name');
    const metaEl = document.getElementById('doc-meta');
    const saveBtn = document.getElementById('btn-save') as HTMLButtonElement | null;
    if (!this.session) {
      if (nameEl) nameEl.textContent = 'No document';
      if (metaEl) metaEl.textContent = 'Open a PSD to begin';
      if (saveBtn) saveBtn.disabled = true;
      return;
    }
    const doc = this.session.doc;
    if (nameEl) {
      nameEl.textContent = this.session.file.name;
      nameEl.classList.toggle('dirty', this.session.dirty);
    }
    if (metaEl) {
      const blend = this.selected()?.blend;
      const warn = blend && blendNoteText(blend) ? ' · mode shown as Normal' : '';
      metaEl.textContent = `${doc.width} × ${doc.height} · RGB 8-bit · ${countLayers(doc)} layers${warn}`;
    }
    if (saveBtn) saveBtn.disabled = false;
  }

  private renderLayers(): void {
    const host = document.getElementById('layers');
    if (!host) return;
    clear(host);
    if (!this.session) {
      host.append(el('p', { class: 'hint' }, 'No document open.'));
      return;
    }
    const rows: HTMLElement[] = [];
    const walk = (layers: Layer[], depth: number) => {
      for (let i = layers.length - 1; i >= 0; i--) { // top-most first, like Photoshop
        const layer = layers[i];
        rows.push(this.layerRow(layer, depth));
        if (isGroup(layer) && layer.opened) walk(layer.children, depth + 1);
      }
    };
    walk(this.session.doc.children, 0);
    host.append(...rows);
  }

  private layerRow(layer: Layer, depth: number): HTMLElement {
    const selected = layer.id === this.selectedId;
    const row = el('div', {
      class: `layer-row${selected ? ' selected' : ''}`,
      'data-depth': String(Math.min(depth, 2)),
      'data-id': layer.id,
    });

    const eye = iconBtn(layer.visible ? ICONS.eye : ICONS.eyeOff, layer.visible ? 'Hide layer' : 'Show layer', (e) => {
      e.stopPropagation();
      const before = { visible: layer.visible };
      layer.visible = !layer.visible;
      this.history.pushProps('Toggle visibility', layer, before, { visible: layer.visible });
      this.markDirty();
      this.repaintAll();
      this.renderLayers();
      this.renderProps();
    });
    if (!layer.visible) eye.style.opacity = '.45';

    const thumb = el('canvas', { class: 'layer-thumb', width: 40, height: 40 });
    this.drawThumb(thumb as HTMLCanvasElement, layer);

    const info = el('div', { class: 'layer-info' },
      el('span', { class: 'layer-name' }, layer.name),
      el('span', { class: 'layer-sub' }, layerSummary(layer)),
    );
    const bits: Node[] = [eye, el('span', { class: 'layer-indent' })];
    if (isGroup(layer)) {
      bits.push(iconBtn(layer.opened ? ICONS.chevronDown : ICONS.chevron, 'Expand group', (e) => {
        e.stopPropagation();
        layer.opened = !layer.opened;
        this.renderLayers();
      }));
    }
    bits.push(thumb, info);
    if (layer.unsupported) info.append(el('span', { class: 'badge' }, 'kept'));
    if (layer.locked) info.append(el('span', { class: 'badge' }, 'locked'));
    row.append(...bits);

    row.addEventListener('click', () => this.selectLayer(layer.id));
    row.addEventListener('dblclick', () => {
      if (isText(layer)) openTextEditor(this, layer); else this.openLayerMenu(layer);
    });
    attachLongPress(row, () => this.openLayerMenu(layer));
    this.attachLayerDrag(row, layer);
    return row;
  }

  /** Hold a row for ~half a second, then drag to move it within its own group. */
  private attachLayerDrag(row: HTMLElement, layer: Layer): void {
    const host = document.getElementById('layers')!;
    let timer = 0;
    let dragging = false;
    let startY = 0;

    const down = (e: PointerEvent) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      startY = e.clientY;
      timer = window.setTimeout(() => {
        dragging = true;
        row.classList.add('dragging');
        row.style.touchAction = 'none';
        row.setPointerCapture?.(e.pointerId);
      }, 420);
    };
    const move = (e: PointerEvent) => {
      if (!dragging) {
        if (Math.abs(e.clientY - startY) > 10 && timer) { clearTimeout(timer); timer = 0; }
        return;
      }
      e.preventDefault();
      const target = [...host.querySelectorAll<HTMLElement>('.layer-row')].find((r) => {
        if (r === row) return false;
        const box = r.getBoundingClientRect();
        return e.clientY >= box.top && e.clientY <= box.bottom;
      });
      if (!target) return;
      const box = target.getBoundingClientRect();
      host.insertBefore(row, e.clientY < box.top + box.height / 2 ? target : target.nextSibling);
    };
    const up = (e: PointerEvent) => {
      if (timer) { clearTimeout(timer); timer = 0; }
      if (!dragging) return;
      dragging = false;
      row.classList.remove('dragging');
      row.releasePointerCapture?.(e.pointerId);
      this.commitLayerDrop(layer);
    };
    row.addEventListener('pointerdown', down);
    row.addEventListener('pointermove', move);
    row.addEventListener('pointerup', up);
    row.addEventListener('pointercancel', up);
  }

  private commitLayerDrop(layer: Layer): void {
    if (!this.session) return;
    const doc = this.session.doc;
    const host = document.getElementById('layers')!;
    const modelOrder = [...host.querySelectorAll<HTMLElement>('.layer-row')].map((r) => r.dataset.id!).reverse();
    const path = findPath(doc, layer.id);
    if (!path) { this.renderLayers(); return; }
    const siblings = new Set(path.list.map((l) => l.id));
    const index = modelOrder.slice(0, modelOrder.indexOf(layer.id)).filter((id) => siblings.has(id)).length;
    if (index === path.index) { this.renderLayers(); return; }
    this.history.runState('Reorder layer', layer.id, () => {
      path.list.splice(path.index, 1);
      path.list.splice(Math.max(0, Math.min(path.list.length, index)), 0, layer);
    });
    this.markDirty();
    this.repaintAll();
    this.renderAll();
  }

  private drawThumb(canvas: HTMLCanvasElement, layer: Layer): void {
    const cached = this.thumbCache.get(layer.id);
    if (cached && cached.rev === layer.rev) {
      const ctx = canvas.getContext('2d')!;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(cached.canvas, 0, 0, canvas.width, canvas.height);
      return;
    }
    const bounds = layerBounds(layer);
    if (!bounds || bounds.w <= 0 || bounds.h <= 0) return;
    const size = 80; // supersampled, drawn down into the 40 px slot
    const scale = Math.min(size / bounds.w, size / bounds.h);
    const out = createCanvas(size, size);
    const tempDoc: Doc = { width: Math.max(1, Math.round(bounds.w)), height: Math.max(1, Math.round(bounds.h)), children: [layer] };
    if (!this.thumbRenderer) this.thumbRenderer = new Renderer(tempDoc);
    this.thumbRenderer.doc = tempDoc;
    this.thumbRenderer.composite(out, scale, { offsetX: -bounds.x * scale, offsetY: -bounds.y * scale });
    this.thumbCache.set(layer.id, { rev: layer.rev, canvas: out as unknown as HTMLCanvasElement });
    const dst = canvas.getContext('2d')!;
    dst.clearRect(0, 0, canvas.width, canvas.height);
    dst.drawImage(out as unknown as HTMLCanvasElement, 0, 0, canvas.width, canvas.height);
  }

  private refreshThumbFor(layer: Layer | null): void {
    if (layer) this.thumbCache.delete(layer.id);
    this.renderLayersIfOpen();
  }

  private renderProps(): void {
    const host = document.getElementById('props');
    if (!host) return;
    clear(host);
    if (!this.session) return;

    const group = el('div', { class: 'prop-group' });
    const layer = this.selected();

    if (this.tool === 'brush' || this.tool === 'eraser') {
      group.append(
        el('div', { class: 'prop-title' }, this.tool === 'eraser' ? 'Eraser' : 'Brush'),
        slider({
          label: 'Size', min: 1, max: 400, step: 1, value: this.brushSettings.size,
          format: (v) => `${v} px`, onInput: (v) => { this.brushSettings.size = v; },
        }),
        slider({
          label: 'Opacity', min: 0.05, max: 1, step: 0.05, value: this.brushSettings.opacity,
          format: (v) => `${Math.round(v * 100)}%`, onInput: (v) => { this.brushSettings.opacity = v; },
        }),
      );
      if (this.tool === 'brush') {
        group.append(el('div', { class: 'row' },
          el('button', { class: 'btn', style: 'flex:1;border-color:' + rgbaToHex(this.currentColor), onclick: () => openColorSheet(this) }, `Colour ${rgbaToHex(this.currentColor)}`),
        ));
      }
      group.append(el('p', { class: 'hint' }, 'Paint on the selected raster layer — text layers must be rasterized first.'));
    } else if (this.tool === 'fill') {
      group.append(
        el('div', { class: 'prop-title' }, 'Fill rectangle'),
        el('p', { class: 'hint' }, 'Drag a box to fill it, or tap to fill an 80 px square with the current colour.'),
        el('div', { class: 'row' }, el('button', { class: 'btn', style: 'flex:1', onclick: () => openColorSheet(this, 'fill') }, `Colour ${rgbaToHex(this.currentColor)}`)),
      );
    } else if (this.tool === 'text') {
      group.append(
        el('div', { class: 'prop-title' }, 'Text'),
        el('p', { class: 'hint' }, 'Tap the canvas to place editable text, or tap existing text to edit it.'),
        el('div', { class: 'row' },
          el('button', { class: 'btn', style: 'flex:1', onclick: () => (layer && isText(layer) ? openTextEditor(this, layer) : this.addTextLayer()) }, layer && isText(layer) ? 'Edit text' : 'New text layer'),
        ),
      );
    } else {
      group.append(
        el('div', { class: 'prop-title' }, 'Move'),
        el('p', { class: 'hint' }, 'Drag on the canvas to move the selected layer. Pinch with two fingers to zoom.'),
      );
    }

    if (layer) {
      const opacityStart = layer.opacity;
      const quick = el('div', { class: 'prop-group' });
      quick.append(
        el('div', { class: 'prop-title' }, `Selected: ${layer.name}`),
        slider({
          label: 'Layer opacity', min: 0, max: 100, step: 1, value: Math.round(layer.opacity * 100),
          format: (v) => `${v}%`,
          onInput: (v) => {
            layer.opacity = v / 100;
            this.markDirty();
            this.repaintAll();
          },
          onCommit: () => {
            this.history.pushProps('Opacity', layer, { opacity: opacityStart }, { opacity: layer.opacity });
          },
        }),
        (() => {
          const actions = el('div', { class: 'row' },
            el('button', { class: 'btn', onclick: () => this.openLayerMenu(layer) }, 'Options'),
          );
          if (isText(layer)) actions.append(el('button', { class: 'btn', onclick: () => openTextEditor(this, layer) }, 'Edit text'));
          if (isRaster(layer) && !layer.unsupported) actions.append(el('button', { class: 'btn', onclick: () => openBrushSheet(this) }, 'Brush'));
          if (isGroup(layer)) actions.append(el('span', { class: 'hint' }, `${layer.children.length} layers`));
          return actions;
        })(),
      );
      if (layer.unsupported) quick.append(el('p', { class: 'hint' }, 'Kept in the file, not previewed here.'));
      host.append(group, quick);
    } else {
      host.append(group);
    }
  }

  private blendWarnings(): string[] {
    const doc = this.session?.doc;
    if (!doc) return [];
    const notes: string[] = [];
    walkLayers(doc.children, (l) => {
      const note = blendNoteText(l.blend);
      if (note) notes.push(`${l.name}: ${note}`);
    });
    return [...new Set(notes)];
  }
}

/* -------------------------------------------------------------- helpers */

const PROP_APPLIERS: Record<string, (layer: any, value: any) => void> = {
  opacity: (l, v) => { l.opacity = v; },
  textText: (l, v) => { l.text.text = v; },
  textFont: (l, v) => { l.text.font = v; },
  textSize: (l, v) => { l.text.size = Math.max(1, v); },
  textAlign: (l, v) => { l.text.align = v; },
  textTracking: (l, v) => { l.text.tracking = v; },
  textLeading: (l, v) => { l.text.leading = v; },
  textColor: (l, v) => { l.text.color = v; },
  textFauxBold: (l, v) => { l.text.fauxBold = v; },
  textFauxItalic: (l, v) => { l.text.fauxItalic = v; },
  textUnderline: (l, v) => { l.text.underline = v; },
};

function captureProp(layer: any, key: string): unknown {
  switch (key) {
    case 'textText': return layer.text.text;
    case 'textFont': return { ...layer.text.font };
    case 'textSize': return layer.text.size;
    case 'textAlign': return layer.text.align;
    case 'textTracking': return layer.text.tracking;
    case 'textLeading': return layer.text.leading;
    case 'textColor': return { ...layer.text.color };
    case 'textFauxBold': return layer.text.fauxBold;
    case 'textFauxItalic': return layer.text.fauxItalic;
    case 'textUnderline': return layer.text.underline;
    default: return layer[key];
  }
}

function unionRect(
  a: { x: number; y: number; w: number; h: number } | null,
  b: { x: number; y: number; w: number; h: number } | null,
  pad: number,
): { x: number; y: number; w: number; h: number } | null {
  if (!a) return b ? { x: b.x - pad, y: b.y - pad, w: b.w + pad * 2, h: b.h + pad * 2 } : null;
  if (!b) return { x: a.x - pad, y: a.y - pad, w: a.w + pad * 2, h: a.h + pad * 2 };
  const x0 = Math.min(a.x, b.x) - pad;
  const y0 = Math.min(a.y, b.y) - pad;
  const x1 = Math.max(a.x + a.w, b.x + b.w) + pad;
  const y1 = Math.max(a.y + a.h, b.y + b.h) + pad;
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
}

function countLayers(doc: Doc): number {
  let n = 0;
  walkLayers(doc.children, () => { n++; });
  return n;
}

function attachLongPress(node: HTMLElement, fn: () => void): void {
  let timer = 0;
  const cancel = () => { if (timer) { clearTimeout(timer); timer = 0; } };
  node.addEventListener('pointerdown', () => { cancel(); timer = window.setTimeout(() => { timer = 0; fn(); }, 550); });
  node.addEventListener('pointerup', cancel);
  node.addEventListener('pointerleave', cancel);
  node.addEventListener('pointercancel', cancel);
}
