/**
 * photo_cc — core data model.
 *
 * The model is intentionally tiny and maps 1:1 onto the parts of the PSD format
 * that a phone screen can realistically edit:
 *
 *   layer   -> PSD layer record (rectangle + channels + blend mode + opacity)
 *   group   -> PSD layer section (`lsct`) with children
 *   text    -> PSD type layer (`TySh` + `Txt2` engine data) rendered with Web fonts
 *
 * Everything an imported file contains that we do *not* understand is kept on
 * `session.source` and written back untouched on export (see core/psd.ts).
 */

/** Blend modes we can both read and write. Values match ag-psd / PSD naming. */
export type BlendMode =
  | 'normal' | 'dissolve'
  | 'darken' | 'multiply' | 'color burn' | 'linear burn' | 'darker color'
  | 'lighten' | 'screen' | 'color dodge' | 'linear dodge' | 'lighter color'
  | 'overlay' | 'soft light' | 'hard light' | 'vivid light' | 'linear light' | 'pin light' | 'hard mix'
  | 'difference' | 'exclusion' | 'subtract' | 'divide'
  | 'hue' | 'saturation' | 'color' | 'luminosity'
  | 'pass through';

export const BLEND_MODES: BlendMode[] = [
  'normal', 'dissolve',
  'darken', 'multiply', 'color burn', 'linear burn', 'darker color',
  'lighten', 'screen', 'color dodge', 'linear dodge', 'lighter color',
  'overlay', 'soft light', 'hard light', 'vivid light', 'linear light', 'pin light', 'hard mix',
  'difference', 'exclusion', 'subtract', 'divide',
  'hue', 'saturation', 'color', 'luminosity',
];

const BLEND_LABELS: Record<string, string> = {
  'normal': 'Normal', 'dissolve': 'Dissolve',
  'darken': 'Darken', 'multiply': 'Multiply', 'color burn': 'Color Burn',
  'linear burn': 'Linear Burn', 'darker color': 'Darker Color',
  'lighten': 'Lighten', 'screen': 'Screen', 'color dodge': 'Color Dodge',
  'linear dodge': 'Linear Dodge (Add)', 'lighter color': 'Lighter Color',
  'overlay': 'Overlay', 'soft light': 'Soft Light', 'hard light': 'Hard Light',
  'vivid light': 'Vivid Light', 'linear light': 'Linear Light',
  'pin light': 'Pin Light', 'hard mix': 'Hard Mix',
  'difference': 'Difference', 'exclusion': 'Exclusion',
  'subtract': 'Subtract', 'divide': 'Divide',
  'hue': 'Hue', 'saturation': 'Saturation', 'color': 'Color', 'luminosity': 'Luminosity',
  'pass through': 'Pass Through',
};

export function blendLabel(mode: string): string {
  return BLEND_LABELS[mode] ?? mode.replace(/\b\w/g, (c) => c.toUpperCase());
}

export interface RGBA { r: number; g: number; b: number; a: number }

/** A pixel block positioned in document space. `data` is always straight (non-premultiplied) RGBA. */
export interface Pixels {
  width: number;
  height: number;
  data: Uint8ClampedArray;
}

export interface FontRef {
  /** CSS font stack used to actually draw the glyphs. */
  family: string;
  /** PostScript-style name written into the PSD so Photoshop can look it up. */
  psdName: string;
  weight: number;
  style: 'normal' | 'italic';
}

export interface TextData {
  text: string;
  font: FontRef;
  size: number;
  color: RGBA;
  align: 'left' | 'center' | 'right';
  /** PSD tracking value (1/1000 em). */
  tracking: number;
  /** Baseline-to-baseline distance in px; `null` = automatic (size * 1.2). */
  leading: number | null;
  fauxBold: boolean;
  fauxItalic: boolean;
  underline: boolean;
}

export interface StrokeStyle { enabled: boolean; color: RGBA; width: number }
export interface ShadowStyle { enabled: boolean; color: RGBA; blur: number; dx: number; dy: number }

interface LayerBase {
  id: string;
  name: string;
  visible: boolean;
  /** 0..1, matching PSD opacity. */
  opacity: number;
  blend: BlendMode | string;
  locked: boolean;
  /** Bumped whenever the appearance changes; drives the render cache keys. */
  rev: number;
  /** PSD layer id (`lyid`) from the opened file, written back unchanged. */
  sourceId?: number;
  /**
   * Original ag-psd layer record. Everything this app does not model (layer
   * styles, masks, vector paths, metadata) lives here and survives a save
   * because export starts from a shallow copy of this object.
   */
  source?: any;
  /** Set when the layer is preserved but cannot be previewed (adjustment, smart object). */
  unsupported?: string;
}

export interface RasterLayer extends LayerBase {
  kind: 'raster';
  /** Document-space position of the pixel block (PSD layer rectangle). */
  x: number; y: number; w: number; h: number;
  /** null for empty/new layers until something is painted. */
  pixels: Pixels | null;
  /** Set when the layer comes from a placed/linked file we could not decode — kept verbatim. */
  passthrough?: boolean;
}

export interface TextLayer extends LayerBase {
  kind: 'text';
  /** Top-left of the text bounding box in document space. */
  x: number; y: number;
  text: TextData;
  stroke: StrokeStyle;
  shadow: ShadowStyle;
  /** Original ag-psd text record (extra Photoshop-only fields), kept for round-tripping. */
  source?: any;
}

export interface GroupLayer extends LayerBase {
  kind: 'group';
  children: Layer[];
  /** PSD 'pass through' groups blend with the layers below; we approximate with normal. */
  opened: boolean;
}

export type Layer = RasterLayer | TextLayer | GroupLayer;

export interface Doc {
  width: number;
  height: number;
  /** Bottom-most layer first — same order as PSD layer records. */
  children: Layer[];
}

export interface ImportMeta {
  colorMode: string;
  bitsPerChannel: number;
  /** Warnings surfaced in the UI after opening a file. */
  notes: string[];
}

export interface Session {
  doc: Doc;
  meta: ImportMeta;
  /** File bookkeeping for Save / Export. */
  file: {
    /** Original file name, e.g. `poster.psd`. */
    name: string;
    /** Suggested name for the next export. */
    outName: string;
    /** Size in bytes of the opened file. */
    bytes: number;
  };
  /** Original ag-psd document + read options, used to keep unknown data alive on export. */
  source?: { psd: any; readOptions: any } | null;
  dirty: boolean;
}

let idCounter = 0;
export function newId(prefix = 'L'): string {
  idCounter += 1;
  return `${prefix}${Date.now().toString(36)}${idCounter.toString(36)}`;
}

export function createTextData(overrides: Partial<TextData> = {}): TextData {
  return {
    text: 'Type here',
    font: { family: 'system-ui, -apple-system, Roboto, sans-serif', psdName: 'ArialMT', weight: 400, style: 'normal' },
    size: 48,
    color: { r: 0, g: 0, b: 0, a: 255 },
    align: 'left',
    tracking: 0,
    leading: null,
    fauxBold: false,
    fauxItalic: false,
    underline: false,
    ...overrides,
  };
}

export function isGroup(l: Layer): l is GroupLayer { return l.kind === 'group'; }
export function isRaster(l: Layer): l is RasterLayer { return l.kind === 'raster'; }
export function isText(l: Layer): l is TextLayer { return l.kind === 'text'; }

/** Depth-first walk, bottom-most leaf first (the order layers are painted in). */
export function walkLayers(layers: Layer[], visit: (l: Layer, parents: GroupLayer[]) => void, parents: GroupLayer[] = []): void {
  for (const l of layers) {
    visit(l, parents);
    if (isGroup(l)) walkLayers(l.children, visit, [...parents, l]);
  }
}

export function findLayer(layers: Layer[], id: string): Layer | null {
  for (const l of layers) {
    if (l.id === id) return l;
    if (isGroup(l)) {
      const found = findLayer(l.children, id);
      if (found) return found;
    }
  }
  return null;
}

export function findParent(layers: Layer[], id: string, parent: GroupLayer | null = null): GroupLayer | null | undefined {
  for (const l of layers) {
    if (l.id === id) return parent;
    if (isGroup(l)) {
      const found = findParent(l.children, id, l);
      if (found !== undefined) return found;
    }
  }
  return undefined;
}

/** Removes a layer from the tree, returning it. */
export function detachLayer(doc: Doc, id: string): Layer | null {
  const remove = (list: Layer[]): Layer | null => {
    const idx = list.findIndex((l) => l.id === id);
    if (idx >= 0) return list.splice(idx, 1)[0];
    for (const l of list) {
      if (isGroup(l)) {
        const found = remove(l.children);
        if (found) return found;
      }
    }
    return null;
  };
  return remove(doc.children);
}
