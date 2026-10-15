/**
 * PSD import / export.
 *
 * Import strategy (keeps CPU + RAM low and never loses data):
 *  1. Parse the 26-byte header ourselves. Photoshop colour modes we cannot
 *     reasonably re-compose (CMYK, Lab, Duotone, Multichannel) are rejected with
 *     a clear message *before* allocating anything.
 *  2. Hand the buffer to `ag-psd` with `useImageData: true` (no per-layer canvas
 *     allocation) and `skipThumbnail: true` (we regenerate one on save).
 *  3. Map the ag-psd tree onto our small model. Every source layer record is kept
 *     by reference (`source`) and every unknown section of the file (layer styles,
 *     masks, vector paths, smart-object links, image resources, ...) survives
 *     because export starts from a shallow clone of that same record.
 *
 * Export always rewrites the parts we own (name/visibility/opacity/blend mode/
 * pixels/text) and leaves everything else byte-identical in meaning.
 */

import { readPsd, writePsdUint8Array, type Layer as AgLayer, type Psd as AgPsd } from 'ag-psd';
import {
  createTextData, isGroup, isRaster, isText, newId, walkLayers,
  type Doc, type ImportMeta, type Layer, type Pixels, type Session, type TextLayer,
} from './types';
import { agColorToRgba, rgbaToAg } from './color';
import { psdFontToCss, cssToPsdFontName, FALLBACK_FONT } from './fonts';
import { textGeometry } from './render';

export const PSD_MAGIC = '8BPS';

const COLOR_MODE_NAMES: Record<number, string> = {
  0: 'Bitmap', 1: 'Grayscale', 2: 'Indexed', 3: 'RGB', 4: 'CMYK',
  7: 'Multichannel', 8: 'Duotone', 9: 'Lab',
};

const SUPPORTED_COLOR_MODES = new Set([1, 2, 3]); // grayscale, indexed, RGB

export interface PsdHeader {
  version: number;
  channels: number;
  height: number;
  width: number;
  bitsPerChannel: number;
  colorMode: number;
  colorModeName: string;
}

export function probeHeader(buffer: ArrayBuffer): PsdHeader | null {
  if (buffer.byteLength < 26) return null;
  const view = new DataView(buffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  if (magic !== PSD_MAGIC) {
    // 8B64 == PSB (large document format) also starts with 8BPS
    return null;
  }
  const version = view.getUint16(4);
  const channels = view.getUint16(12);
  const height = view.getUint32(14);
  const width = view.getUint32(18);
  const bitsPerChannel = view.getUint16(22);
  const colorMode = view.getUint16(24);
  return { version, channels, height, width, bitsPerChannel, colorMode, colorModeName: COLOR_MODE_NAMES[colorMode] ?? `#${colorMode}` };
}

export function isPsd(buffer: ArrayBuffer): boolean {
  return probeHeader(buffer) !== null;
}

/** Human-readable reason when the file cannot be opened (or null when it is fine). */
export function unsupportedReason(header: PsdHeader): string | null {
  if (!SUPPORTED_COLOR_MODES.has(header.colorMode)) {
    return `${header.colorModeName} colour mode is not supported. Open the file in desktop Photoshop (or any converter) and save it as RGB 8-bit, then try again — everything else in the document is preserved as-is.`;
  }
  if (header.version === 2) {
    return 'This is a PSB (Large Document Format) file. It can only be opened if it is 30 000 px or smaller on both sides.';
  }
  return null;
}

export interface LoadOptions {
  /** Keep the original pixel data of type layers (needed for a perfect round-trip). */
  keepTextPixels?: boolean;
}

export interface LoadedDocument {
  session: Session;
  warnings: string[];
}

/** Converts one ag-psd layer record into our model. */
function importLayer(agLayer: AgLayer, depth: number): Layer | null {
  const name = agLayer.name || (agLayer.children ? 'Group' : 'Layer');
  const base = {
    id: newId('L'),
    name,
    visible: !agLayer.hidden,
    opacity: typeof agLayer.opacity === 'number' ? agLayer.opacity : 1,
    blend: (agLayer.blendMode ?? 'normal') as any,
    locked: !!agLayer.protected?.transparency,
    rev: 1,
    sourceId: agLayer.id,
    source: agLayer,
  };

  if (agLayer.children && agLayer.children.length) {
    return {
      ...base,
      kind: 'group',
      opened: agLayer.opened !== false,
      children: agLayer.children.map((c) => importLayer(c, depth + 1)).filter(Boolean) as Layer[],
    };
  }

  const text = (agLayer as any).text;
  if (text && typeof text.text === 'string') {
    return importTextLayer(base, agLayer, text);
  }

  // Anything we cannot rasterise ourselves (adjustment layers, unresolved smart
  // objects, layer effects-only records) is preserved but not drawn.
  let unsupported: string | undefined;
  if ((agLayer as any).adjustment) unsupported = 'Adjustment layer (kept as-is, not previewed)';
  else if ((agLayer as any).placedLayer && !agLayer.imageData && !agLayer.canvas) unsupported = 'Unresolved smart object (kept as-is)';

  const imageData = agLayer.imageData
    ? toPixels(agLayer.imageData)
    : agLayer.canvas
      ? canvasToPixels(agLayer.canvas)
      : null;

  const layer: Layer = {
    ...base,
    kind: 'raster',
    x: Math.round(agLayer.left ?? agLayer.canvas?.width ?? 0) || 0,
    y: Math.round(agLayer.top ?? agLayer.canvas?.height ?? 0) || 0,
    w: imageData?.width ?? 0,
    h: imageData?.height ?? 0,
    pixels: imageData,
    unsupported,
  };
  if (!imageData && !unsupported) {
    // genuinely empty layer: keep it so the layer list stays faithful
    layer.x = 0; layer.y = 0;
  }
  return layer;
}

/** Converts ag-psd pixel data (8- or 16-bit) into 8-bit straight RGBA. */
function toPixels(pd: { width: number; height: number; data: any }): Pixels {
  const raw = pd.data;
  if (raw instanceof Uint16Array) {
    // 16-bit channels: take the high byte (Photoshop itself does a similar shift)
    const out = new Uint8ClampedArray(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw[i] >> 8;
    return { width: pd.width, height: pd.height, data: out };
  }
  if (raw instanceof Uint8ClampedArray) return { width: pd.width, height: pd.height, data: raw };
  return {
    width: pd.width,
    height: pd.height,
    data: new Uint8ClampedArray(raw.buffer, raw.byteOffset, raw.byteLength),
  };
}

function canvasToPixels(canvas: HTMLCanvasElement): Pixels | null {
  try {
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
    return { width: img.width, height: img.height, data: img.data };
  } catch {
    return null;
  }
}

type Align = 'left' | 'center' | 'right';
const JUSTIFICATION: Record<number, Align> = { 0: 'left', 1: 'right', 2: 'center', 3: 'left', 4: 'left' };

function importTextLayer(base: any, agLayer: AgLayer, text: any): TextLayer {
  const style = text.style ?? {};
  const psdFontName: string = style.font?.name ?? FALLBACK_FONT.psd;
  const family = psdFontToCss(psdFontName);
  const size = typeof style.fontSize === 'number' ? style.fontSize : 24;
  const justify = text.paragraphStyle?.justification;
  const color = agColorToRgba(style.fillColor);

  const layer: TextLayer = {
    ...base,
    kind: 'text',
    x: Math.round(text.left ?? text.transform?.[4] ?? 0),
    y: Math.round(text.top ?? text.transform?.[5] ?? 0),
    text: createTextData({
      text: String(text.text).replace(/\r\n?/g, '\n'),
      font: {
        family: family.family,
        psdName: psdFontName,
        weight: style.fauxBold ? 700 : (family.weight ?? 400),
        style: style.fauxItalic ? 'italic' : 'normal',
      },
      size,
      color,
      align: JUSTIFICATION[justify ?? 0] ?? 'left',
      tracking: typeof style.tracking === 'number' ? style.tracking : 0,
      leading: style.autoLeading === false && typeof style.leading === 'number' ? style.leading : null,
      fauxBold: !!style.fauxBold,
      fauxItalic: !!style.fauxItalic,
      underline: !!style.underline,
    }),
    stroke: { enabled: false, color: { r: 0, g: 0, b: 0, a: 255 }, width: 1 },
    shadow: { enabled: false, color: { r: 0, g: 0, b: 0, a: 255 }, blur: 0, dx: 0, dy: 0 },
    source: text,
  };

  // layer effects we can actually draw
  const effects: any = (agLayer as any).effects;
  // ag-psd reports every effect as a list; take the first enabled entry of each kind.
  const firstEffect = (v: any): any => (Array.isArray(v) ? v.find((e) => e?.enabled !== false) ?? v[0] : v);
  if (firstEffect(effects?.stroke)?.enabled) {
    const stroke = firstEffect(effects.stroke);
    layer.stroke = {
      enabled: true,
      color: agColorToRgba(stroke.color),
      width: unitValue(stroke.size) ?? 1,
    };
  }
  const shadowEffect = firstEffect(effects?.dropShadow);
  if (shadowEffect?.enabled) {
    const distance = unitValue(shadowEffect.distance) ?? 0;
    const angle = ((shadowEffect.angle ?? 120) * Math.PI) / 180;
    layer.shadow = {
      enabled: true,
      color: agColorToRgba(shadowEffect.color),
      blur: unitValue(shadowEffect.size) ?? 0,
      dx: Math.round(-Math.cos(angle) * distance),
      dy: Math.round(Math.sin(angle) * distance),
    };
  }
  return layer;
}

function unitValue(v: any): number | undefined {
  if (typeof v === 'number') return v;
  if (v && typeof v.value === 'number') return v.value;
  return undefined;
}

export function loadPsdBuffer(buffer: ArrayBuffer, fileName: string): LoadedDocument {
  const header = probeHeader(buffer);
  if (!header) throw new Error('This does not look like a Photoshop document (missing 8BPS signature).');
  const reason = unsupportedReason(header);
  if (reason) throw new Error(reason);

  const warnings: string[] = [];
  if (header.bitsPerChannel !== 8) warnings.push(`${header.bitsPerChannel}-bit channels are converted to 8-bit on save.`);
  if (header.width * header.height > 20_000_000) warnings.push('Large document: editing will be slower. Consider cropping first.');
  if (header.colorMode !== 3) warnings.push(`${header.colorModeName} documents are treated as RGB on save.`);

  const readOptions = {
    skipThumbnail: true,
    skipLinkedFilesData: false,
    useImageData: true,
    totalMemoryLimit: 512 * 1024 * 1024,
  } as const;

  let psd: AgPsd;
  try {
    psd = readPsd(buffer, readOptions as any);
  } catch (err: any) {
    throw new Error(`Could not read this PSD: ${err?.message ?? err}`);
  }

  const children = (psd.children ?? []).map((c) => importLayer(c, 0)).filter(Boolean) as Layer[];
  const doc: Doc = { width: psd.width, height: psd.height, children };

  // Count features we keep but do not render, so the user is not surprised.
  let unsupportedCount = 0;
  let effectCount = 0;
  let maskCount = 0;
  walkLayers(children, (l) => {
    if ((l as any).unsupported) unsupportedCount++;
    if ((l as any).source?.effects && !isText(l)) effectCount++;
    if ((l as any).source?.mask) maskCount++;
  });
  if (effectCount) warnings.push(`${effectCount} layer${effectCount > 1 ? 's have' : ' has'} layer styles that are kept but not previewed.`);
  if (maskCount) warnings.push(`${maskCount} layer mask${maskCount > 1 ? 's are' : ' is'} preserved but not editable here.`);
  if (unsupportedCount) warnings.push(`${unsupportedCount} layer${unsupportedCount > 1 ? 's' : ''} cannot be previewed (adjustment/smart object) and will be written back untouched.`);

  const meta: ImportMeta = {
    colorMode: header.colorModeName,
    bitsPerChannel: header.bitsPerChannel,
    notes: warnings,
  };

  const outName = fileName.replace(/\.(psd|psb)$/i, '') + '-edited.psd';
  const session: Session = {
    doc,
    meta,
    file: { name: fileName, outName, bytes: buffer.byteLength },
    source: { psd, readOptions },
    dirty: false,
  };
  return { session, warnings };
}

/* ------------------------------------------------------------------ export */

export interface ExportOptions {
  /** Trim transparent pixels around layers (smaller file, moves layer rectangles). */
  trim?: boolean;
  /** Let Photoshop redraw text layers on open (required after text edits). */
  invalidateText?: boolean;
  /** Compress layer data with ZIP (smaller file, slower, slightly less compatible). */
  compress?: boolean;
  /** Write a PSB (large document) file. */
  psb?: boolean;
  /** Include the embedded smart-object data. */
  keepEmbedded?: boolean;
}

function agTextPayload(layer: TextLayer): any {
  const t = layer.text;
  const g = textGeometry(t);
  const style: any = {
    font: { name: t.font.psdName || cssToPsdFontName(t.font.family), script: 0, type: 0, synthetic: 0 },
    fontSize: t.size,
    fauxBold: t.fauxBold,
    fauxItalic: t.fauxItalic,
    autoLeading: t.leading == null,
    leading: t.leading != null ? t.leading : t.size * 1.2,
    tracking: t.tracking,
    fillColor: rgbaToAg(t.color),
    underline: t.underline,
    kerning: 0,
    autoKerning: true,
  };
  const paragraphStyle: any = {
    justification: t.align === 'center' ? 2 : t.align === 'right' ? 1 : 0,
    autoHyphenate: false,
  };
  return {
    text: t.text,
    // Text space origin: point text is anchored on its first baseline.
    transform: [1, 0, 0, 1, layer.x, layer.y + g.firstBaseline],
    left: layer.x,
    top: layer.y,
    right: layer.x + Math.max(1, g.width),
    bottom: layer.y + Math.max(1, g.height),
    shapeType: 'point' as const,
    pointBase: [0, 0],
    antiAlias: 'smooth',
    orientation: 'horizontal',
    index: layer.source?.index ?? 0,
    style,
    paragraphStyle,
    // style runs / warp / bounds are intentionally dropped: they would be stale
    // after a text or font change and Photoshop would render them wrong.
    warp: { style: 'none', value: 0, perspective: 0, perspectiveOther: 0, rotate: 'horizontal' },
  };
}

/** Rebuilds a layer record for writing: our values win, everything else is cloned from the source. */
function exportLayer(layer: Layer, options: ExportOptions, stats: { textEdited: number }): any {
  const source: any = (layer as any).source ?? {};
  const out: any = { ...source };
  delete out.children;
  delete out.canvas;
  delete out.imageData;
  delete out.rawData;
  delete out.text;

  out.name = layer.name;
  out.hidden = !layer.visible;
  out.opacity = layer.opacity;
  out.blendMode = layer.blend;
  if (out.id === undefined) delete out.id;

  if (isGroup(layer)) {
    out.children = layer.children.map((c) => exportLayer(c, options, stats)).filter(Boolean);
    if (!out.children.length) return null;
    out.opened = layer.opened;
    if (out.blendMode === 'pass through') delete out.blendMode; // Photoshop default for groups
    return out;
  }

  if (isText(layer)) {
    out.text = agTextPayload(layer);
    stats.textEdited++;
    // The stored pixel snapshot of a text layer is stale as soon as the font
    // resolution differs, so drop it and let Photoshop redraw the type.
    return out;
  }

  if (isRaster(layer)) {
    if (layer.unsupported) return out; // untouched adjustment layer / smart object
    if (layer.pixels && layer.pixels.width > 0 && layer.pixels.height > 0) {
      out.left = Math.round(layer.x);
      out.top = Math.round(layer.y);
      out.right = Math.round(layer.x + layer.pixels.width);
      out.bottom = Math.round(layer.y + layer.pixels.height);
      out.imageData = { width: layer.pixels.width, height: layer.pixels.height, data: layer.pixels.data };
    } else {
      // empty layer: no channel data at all (Photoshop writes exactly this)
      out.left = Math.round(layer.x);
      out.top = Math.round(layer.y);
      out.right = Math.round(layer.x);
      out.bottom = Math.round(layer.y);
    }
    return out;
  }
  return null;
}

export interface BuiltFile {
  bytes: Uint8Array;
  psd: AgPsd;
  writeOptions: ExportOptions;
}

/**
 * Builds the PSB/PSD bytes. `composite` is the flattened document preview
 * (maximize-compatibility image) — Photoshop, and every other viewer, shows it
 * without re-rendering layers.
 */
export function buildPsdFile(session: Session, composite: ImageData | null, options: ExportOptions = {}): BuiltFile {
  const doc = session.doc;
  const sourcePsd: any = session.source?.psd ?? {};
  const stats = { textEdited: 0 };

  const psd: any = { ...sourcePsd };
  psd.width = doc.width;
  psd.height = doc.height;
  psd.children = doc.children.map((l) => exportLayer(l, options, stats)).filter(Boolean);
  if (!psd.children.length) throw new Error('Nothing to save: the document has no layers.');

  psd.imageData = composite ?? undefined;
  if (!composite) delete psd.imageData;
  delete psd.canvas;

  if (!options.keepEmbedded) delete psd.linkedFiles;
  if (options.invalidateText && stats.textEdited) {
    delete psd.engineData; // document-level type engine cache, regenerated by Photoshop
  }

  const writeOptions: any = {
    generateThumbnail: !options.psb,
    trimImageData: !!options.trim,
    invalidateTextLayers: !!(options.invalidateText && stats.textEdited),
    psb: !!options.psb,
    compress: !!options.compress,
  };

  const bytes = writePsdUint8Array(psd, writeOptions);
  return { bytes, psd, writeOptions };
}

/** Convenience: name for the exported file. */
export function exportName(session: Session): string {
  return session.file.outName || 'export.psd';
}

export function layerHasUnknownData(layer: Layer): boolean {
  return !!(layer as any).unsupported || !!(layer as any).source?.effects;
}

export function describeLayer(layer: Layer): string {
  if (isGroup(layer)) return `${layer.children.length} layer${layer.children.length === 1 ? '' : 's'}`;
  if (isText(layer)) return layer.text.text.replace(/\n/g, ' ').slice(0, 40) || 'Empty text';
  if (isRaster(layer)) return layer.pixels ? `${layer.w} × ${layer.h} px` : 'Empty layer';
  return '';
}


