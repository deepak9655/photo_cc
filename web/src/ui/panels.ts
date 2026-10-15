/**
 * Bottom-sheet panels: colour, brush, text, new document, export, document info,
 * and the layer long-press menu.
 *
 * Everything is plain DOM built on demand — closing a sheet drops the nodes, so
 * an idle app holds almost nothing.
 */

import {
  BLEND_MODES, blendLabel, isGroup, isRaster, isText,
  type Doc, type Layer, type RGBA, type TextLayer,
} from '../core/types';
import { hexToRgba, rgbaToHex } from '../core/color';
import { FONT_LIBRARY, fontLikelyAvailable } from '../core/fonts';
import { documentPixelCount } from '../core/ops';
import {
  el, field, slider, segmented, showSheet, closeSheet, confirmSheet, toast, ICONS, iconBtn,
} from './dom';

export const QUICK_COLORS = [
  '#ffffff', '#000000', '#ff2d75', '#ff8a00', '#ffd166',
  '#42e2b8', '#3aa0ff', '#8b6cff', '#f25c54', '#8d99ae',
];

export interface PanelHost {
  doc(): Doc | null;
  selected(): Layer | null;
  brush(): { size: number; hardness: number; opacity: number };
  setBrush(patch: Partial<{ size: number; hardness: number; opacity: number }>): void;
  color(): RGBA;
  setColor(c: RGBA): void;
  /** Called whenever a panel changed something the canvas must redraw. */
  /** Repaint everything, or just `layer` when the caller knows what changed. */
  refreshCanvas(layer?: Layer | null): void;
  addLayer(layer: Layer, label: string, index?: number): void;
  openTextEditor(layer: TextLayer): void;
  selectLayer(id: string): void;
  updateLayerProp<K extends string>(layer: Layer, key: K, value: unknown, label: string, coalesceKey?: string): void;
  rasterize(layer: Layer): void;
  duplicate(layer: Layer): void;
  deleteLayer(layer: Layer): void;
  moveInStack(layer: Layer, delta: number): void;
  newDocument(width: number, height: number, background: 'transparent' | 'white'): void;
  exportPsd(options: { trim: boolean; invalidateText: boolean; compress: boolean; psb: boolean }): void;
  exportPng(): void;
  documentStats(): { layers: number; pixels: number; memoryBytes: number; scale: number };
}

/* ------------------------------------------------------------------ colour */

export function openColorSheet(host: PanelHost, target: 'brush' | 'fill' = 'brush'): void {
  const current = host.color();
  const preview = el('div', {
    style: `height:56px;border-radius:14px;border:1px solid var(--line);background:${rgbaToHex(current)}`,
  });
  const hexField = field({
    label: 'Hex',
    value: rgbaToHex(current),
    placeholder: '#rrggbb',
    onInput: (value) => {
      const parsed = hexToRgba(value, Math.round(host.color().a));
      host.setColor({ ...parsed, a: current.a });
      preview.style.background = `rgba(${parsed.r},${parsed.g},${parsed.b},${current.a / 255})`;
      sync();
    },
  });
  const alpha = slider({
    label: 'Opacity',
    min: 0, max: 255, step: 1, value: current.a,
    format: (v) => `${Math.round((v / 255) * 100)}%`,
    onInput: (v) => {
      const c = host.color();
      host.setColor({ ...c, a: v });
      preview.style.background = `rgba(${c.r},${c.g},${c.b},${v / 255})`;
    },
  });
  const swatches = el('div', { class: 'swatches' });
  const sync = () => {
    const c = host.color();
    for (const child of swatches.children) {
      const b = child as HTMLElement;
      b.setAttribute('aria-pressed', String(b.dataset.hex === rgbaToHex(c)));
    }
  };
  for (const hex of QUICK_COLORS) {
    const b = el('button', {
      class: 'swatch-btn',
      title: hex,
      'data-hex': hex,
      style: `background:${hex}`,
      onclick: () => {
        const alpha = host.color().a;
        host.setColor(hexToRgba(hex, alpha));
        (hexField.querySelector('input') as HTMLInputElement).value = hex;
        preview.style.background = hex;
        sync();
      },
    });
    swatches.append(b);
  }
  const rgbToHexInput = (input: HTMLInputElement) => {
    const c = host.color();
    input.value = rgbaToHex(c);
    sync();
  };
  const native = el('input', {
    type: 'color',
    value: rgbaToHex(current),
    style: 'width:100%;height:48px;border:1px solid var(--line);border-radius:12px;background:transparent',
    oninput: (e: Event) => {
      const value = (e.target as HTMLInputElement).value;
      const c = host.color();
      host.setColor(hexToRgba(value, c.a));
      preview.style.background = value;
      rgbToHexInput(hexField.querySelector('input') as HTMLInputElement);
    },
  });
  sync();

  showSheet({
    title: target === 'brush' ? 'Colour' : 'Fill colour',
    body: el('div', { style: 'display:flex;flex-direction:column;gap:12px' },
      preview, swatches, native, hexField, alpha,
      el('p', { class: 'hint' }, 'Long-press the swatch on the tool bar to jump straight here.'),
    ),
    actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Done')],
  });
}

/* ------------------------------------------------------------------ brush */

export function openBrushSheet(host: PanelHost): void {
  const brush = host.brush();
  const body = el('div', { style: 'display:flex;flex-direction:column;gap:10px' },
    slider({ label: 'Size', min: 1, max: 400, step: 1, value: brush.size, format: (v) => `${v} px`, onInput: (v) => host.setBrush({ size: v }) }),
    slider({ label: 'Hardness', min: 0, max: 1, step: 0.02, value: brush.hardness, format: (v) => `${Math.round(v * 100)}%`, onInput: (v) => host.setBrush({ hardness: v }) }),
    slider({ label: 'Opacity', min: 0.05, max: 1, step: 0.05, value: brush.opacity, format: (v) => `${Math.round(v * 100)}%`, onInput: (v) => host.setBrush({ opacity: v }) }),
    el('p', { class: 'hint' }, 'The brush paints into the selected raster layer and grows it if you paint outside its edges.'),
  );
  showSheet({ title: 'Brush', body, actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Done')] });
}

/* ------------------------------------------------------------------ text */

export function openTextEditor(host: PanelHost, layer: TextLayer): void {
  const preview = () => host.refreshCanvas(layer);
  const textArea = el('textarea', { placeholder: 'Type here' });
  textArea.value = layer.text.text;

  const body = el('div', { style: 'display:flex;flex-direction:column;gap:12px' });
  const coerceFont = layer.text.font;

  const fontSelect = el('select');
  for (const f of FONT_LIBRARY) {
    const available = fontLikelyAvailable(f.css.split(',')[0].replace(/["']/g, '')) ? '' : ' (substituted)';
    fontSelect.append(el('option', { value: f.css, selected: f.css === coerceFont.family }, `${f.label}${available}`));
  }
  fontSelect.addEventListener('change', () => {
    const entry = FONT_LIBRARY.find((f) => f.css === fontSelect.value);
    host.updateLayerProp(layer, 'textFont', {
      family: fontSelect.value,
      psdName: entry?.psd ?? 'ArialMT',
      weight: layer.text.font.weight,
      style: layer.text.font.style,
    }, 'Font', `font:${layer.id}`);
    preview();
  });

  textArea.addEventListener('input', () => {
    layer.name = layer.name.startsWith('Text') || layer.name === 'Type here' ? textArea.value.split('\n')[0].slice(0, 24) || 'Text' : layer.name;
    host.updateLayerProp(layer, 'textText', textArea.value, 'Edit text', `text:${layer.id}`);
    preview();
  });

  body.append(
    el('div', { class: 'field' }, el('label', {}, 'Text'), textArea),
    el('div', { class: 'row' },
      el('div', { class: 'field', style: 'flex:2 1 160px' }, el('label', {}, 'Font'), fontSelect),
      el('div', { class: 'field', style: 'flex:1 1 90px' },
        el('label', {}, 'Size'),
        (() => {
          const input = el('input', { type: 'number', min: 4, max: 900, step: 1, value: Math.round(layer.text.size) });
          input.addEventListener('input', () => {
            host.updateLayerProp(layer, 'textSize', Number(input.value), 'Font size', `size:${layer.id}`);
            preview();
          });
          return input;
        })(),
      ),
    ),
    segmented(
      [{ value: 'left', label: 'Left' }, { value: 'center', label: 'Centre' }, { value: 'right', label: 'Right' }],
      layer.text.align,
      (v) => {
        host.updateLayerProp(layer, 'textAlign', v, 'Alignment');
        preview();
      },
    ),
    slider({
      label: 'Tracking', min: -200, max: 1000, step: 10, value: layer.text.tracking,
      format: (v) => String(v),
      onInput: (v) => { host.updateLayerProp(layer, 'textTracking', v, 'Tracking', `track:${layer.id}`); preview(); },
    }),
    slider({
      label: 'Line height', min: 0.6, max: 3, step: 0.05,
      value: layer.text.leading == null ? 1.2 : layer.text.leading / layer.text.size,
      format: (v) => (layer.text.leading == null ? `auto (${v.toFixed(2)}×)` : `${v.toFixed(2)}×`),
      onInput: (v) => {
        host.updateLayerProp(layer, 'textLeading', v === 1.2 ? null : v * layer.text.size, 'Line height', `lead:${layer.id}`);
        preview();
      },
    }),
    el('div', { class: 'row' },
      el('button', { class: 'btn', onclick: () => { host.updateLayerProp(layer, 'textFauxBold', !layer.text.fauxBold, 'Bold'); preview(); }, style: layer.text.fauxBold ? 'border-color:var(--accent)' : '' }, 'Bold'),
      el('button', { class: 'btn', onclick: () => { host.updateLayerProp(layer, 'textFauxItalic', !layer.text.fauxItalic, 'Italic'); preview(); }, style: layer.text.fauxItalic ? 'border-color:var(--accent)' : '' }, 'Italic'),
      el('button', { class: 'btn', onclick: () => { host.updateLayerProp(layer, 'textUnderline', !layer.text.underline, 'Underline'); preview(); }, style: layer.text.underline ? 'border-color:var(--accent)' : '' }, 'Underline'),
      el('button', { class: 'btn', onclick: () => openColorSheetForText(host, layer), style: `border-color:${rgbaToHex(layer.text.color)}` }, 'Colour'),
    ),
    el('div', { class: 'prop-group' },
      el('div', { class: 'prop-title' }, 'Outline'),
      slider({
        label: 'Width', min: 0, max: 40, step: 0.5, value: layer.stroke.width, format: (v) => `${v} px`,
        onInput: (v) => { layer.stroke.width = v; layer.stroke.enabled = v > 0; layer.rev++; host.refreshCanvas(layer); },
      }),
    ),
    el('div', { class: 'prop-group' },
      el('div', { class: 'prop-title' }, 'Drop shadow'),
      slider({
        label: 'Blur', min: 0, max: 60, step: 1, value: layer.shadow.blur, format: (v) => `${v} px`,
        onInput: (v) => { layer.shadow.blur = v; layer.shadow.enabled = v > 0 || layer.shadow.dx !== 0 || layer.shadow.dy !== 0; layer.rev++; host.refreshCanvas(layer); },
      }),
      el('div', { class: 'row' },
        slider({ label: 'X', min: -40, max: 40, step: 1, value: layer.shadow.dx, onInput: (v) => { layer.shadow.dx = v; layer.shadow.enabled = true; layer.rev++; host.refreshCanvas(layer); } }),
        slider({ label: 'Y', min: -40, max: 40, step: 1, value: layer.shadow.dy, onInput: (v) => { layer.shadow.dy = v; layer.shadow.enabled = true; layer.rev++; host.refreshCanvas(layer); } }),
      ),
    ),
    el('p', { class: 'hint' }, 'Fonts a phone does not have are substituted for the preview, but the original name is written back into the PSD so a desktop keeps the real typeface.'),
  );

  showSheet({
    title: `Text — ${layer.name}`,
    body,
    actions: [
      el('button', { class: 'btn', onclick: () => { host.rasterize(layer); closeSheet(); } }, 'Rasterize'),
      el('button', { class: 'btn primary', style: 'flex:1', onclick: () => closeSheet() }, 'Done'),
    ],
  });
}

function openColorSheetForText(host: PanelHost, layer: TextLayer): void {
  const current = layer.text.color;
  const body = el('div', { style: 'display:flex;flex-direction:column;gap:12px' });
  const swatches = el('div', { class: 'swatches' });
  for (const hex of QUICK_COLORS) {
    swatches.append(el('button', {
      class: 'swatch-btn', style: `background:${hex}`, title: hex,
      onclick: () => {
        host.updateLayerProp(layer, 'textColor', hexToRgba(hex, current.a), 'Text colour');
        host.refreshCanvas(layer);
        closeSheet();
      },
    }));
  }
  const native = el('input', {
    type: 'color', value: rgbaToHex(current),
    style: 'width:100%;height:48px;border:1px solid var(--line);border-radius:12px;background:transparent',
    oninput: (e: Event) => {
      host.updateLayerProp(layer, 'textColor', hexToRgba((e.target as HTMLInputElement).value, 255), 'Text colour', `color:${layer.id}`);
      host.refreshCanvas(layer);
    },
  });
  body.append(swatches, native);
  showSheet({ title: 'Text colour', body, actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Done')] });
}

/* ------------------------------------------------------------------ new document */

export function openNewDocSheet(host: PanelHost): void {
  const presets: [string, number, number][] = [
    ['Story 1080×1920', 1080, 1920],
    ['Post 1080×1080', 1080, 1080],
    ['Photo 2048×1536', 2048, 1536],
    ['A4 300dpi', 2480, 3508],
  ];
  const widthInput = el('input', { type: 'number', value: 1080, min: 16, max: 16000 });
  const heightInput = el('input', { type: 'number', value: 1920, min: 16, max: 16000 });
  const presetRow = el('div', { class: 'list' });
  for (const [label, w, h] of presets) {
    presetRow.append(el('button', {
      class: 'menu-item',
      onclick: () => { (widthInput as HTMLInputElement).value = String(w); (heightInput as HTMLInputElement).value = String(h); },
    }, el('span', { class: 'grow' }, label), el('small', {}, `${w} × ${h}`)));
  }
  showSheet({
    title: 'New document',
    body: el('div', { style: 'display:flex;flex-direction:column;gap:12px' },
      el('div', { class: 'row' },
        el('div', { class: 'field' }, el('label', {}, 'Width'), widthInput),
        el('div', { class: 'field' }, el('label', {}, 'Height'), heightInput),
      ),
      presetRow,
      el('p', { class: 'hint' }, 'New documents are RGB 8-bit, the format all other PSD software expects.'),
    ),
    actions: [
      el('button', { class: 'btn', onclick: () => { closeSheet(); host.newDocument(Number((widthInput as HTMLInputElement).value), Number((heightInput as HTMLInputElement).value), 'white'); } }, 'White'),
      el('button', { class: 'btn primary', style: 'flex:1', onclick: () => { closeSheet(); host.newDocument(Number((widthInput as HTMLInputElement).value), Number((heightInput as HTMLInputElement).value), 'transparent'); } }, 'Transparent'),
    ],
  });
}

/* ------------------------------------------------------------------ export + info */

export function openExportSheet(host: PanelHost): void {
  let trim = false;
  let invalidateText = true;
  let compress = false;
  let psb = false;
  const sizeHint = el('p', { class: 'hint' }, '');
  const update = () => {
    sizeHint.textContent = `${compress ? 'ZIP compressed' : 'RLE compressed'} layer data${trim ? ', transparent edges trimmed' : ''}${psb ? ', PSB (large document)' : ''}.`;
  };
  const toggle = (label: string, description: string, initial: boolean, set: (v: boolean) => void) => {
    let value = initial;
    const button = el('button', {
      class: 'menu-item',
      'aria-pressed': String(value),
      onclick: () => { value = !value; set(value); button.setAttribute('aria-pressed', String(value)); update(); },
    }, el('span', { class: 'grow' }, label), el('small', {}, description));
    return button;
  };
  update();
  showSheet({
    title: 'Save as PSD',
    body: el('div', { style: 'display:flex;flex-direction:column;gap:8px' },
      toggle('Redraw text layers', 'Lets Photoshop re-render the type with the exact fonts you used', true, (v) => { invalidateText = v; }),
      toggle('Trim layer edges', 'Stores smaller layers, moves nothing visually', false, (v) => { trim = v; }),
      toggle('Compress (ZIP)', 'Smaller file, slower to write, less compatible with old readers', false, (v) => { compress = v; }),
      toggle('Large document (PSB)', 'Only needed above 30 000 px', false, (v) => { psb = v; }),
      sizeHint,
    ),
    actions: [
      el('button', { class: 'btn', onclick: () => { closeSheet(); host.exportPng(); } }, 'Flattened PNG'),
      el('button', { class: 'btn primary', style: 'flex:1', onclick: () => { closeSheet(); host.exportPsd({ trim, invalidateText, compress, psb }); } }, 'Save PSD'),
    ],
  });
}

export function openInfoSheet(host: PanelHost): void {
  const doc = host.doc();
  const stats = host.documentStats();
  const body = el('div', { class: 'list' });
  if (doc) {
    body.append(
      el('div', { class: 'kv' }, el('span', {}, 'Size'), el('b', {}, `${doc.width} × ${doc.height} px`)),
      el('div', { class: 'kv' }, el('span', {}, 'Layers'), el('b', {}, String(stats.layers))),
      el('div', { class: 'kv' }, el('span', {}, 'Layer pixels'), el('b', {}, `${(stats.pixels / 1_000_000).toFixed(1)} Mpx`)),
      el('div', { class: 'kv' }, el('span', {}, 'Canvas memory'), el('b', {}, `${(stats.memoryBytes / 1024 / 1024).toFixed(1)} MB`)),
      el('div', { class: 'kv' }, el('span', {}, 'Zoom'), el('b', {}, `${Math.round(stats.scale * 100)}%`)),
    );
  }
  body.append(
    el('p', { class: 'hint' },
      'photo_cc runs entirely on this device: no uploads, no accounts, no AI, and it works offline once loaded. ' +
      'It is an independent open-source viewer/editor — not affiliated with, or endorsed by, Adobe. ' +
      'Files you open stay exactly as they are apart from the layers you change.'),
  );
  showSheet({ title: 'Document info', body, actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Close')] });
}

export function openHelpSheet(): void {
  const body = el('div', { style: 'display:flex;flex-direction:column;gap:10px' },
    el('p', { class: 'hint' }, 'One finger drags, two fingers pinch to zoom and pan. Everything important sits at the bottom of the screen.'),
    el('ul', { style: 'margin:0;padding-left:18px;color:var(--fg-dim);font-size:13px;display:flex;flex-direction:column;gap:6px' },
      el('li', {}, 'Tap a layer row to select it, tap the eye to hide it, tap “…” for rename / duplicate / delete.'),
      el('li', {}, 'Brush and eraser paint the selected layer. Filling needs a raster layer too — rasterize a text layer first.'),
      el('li', {}, 'The Text tool creates an editable type layer. Fonts missing on the phone are substituted for the preview only.'),
      el('li', {}, 'Adjustment layers, layer masks, layer styles and smart objects cannot be previewed here, but they are written back untouched.'),
      el('li', {}, 'Save always writes a full PSD with a flattened preview, so any other app can open it.'),
    ),
  );
  showSheet({ title: 'How to use it', body, actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Got it')] });
}

/* ------------------------------------------------------------------ layer menu */

export function openLayerMenu(
  _host: PanelHost,
  layer: Layer,
  actions: {
    onRename(): void;
    onDuplicate(): void;
    onDelete(): void;
    onMove(delta: number): void;
    onGroup?(): void;
    onRasterize?(): void;
    onBlend(mode: string): void;
    onOpacity(value: number): void;
    onTrim?(): void;
  },
): void {
  const body = el('div', { style: 'display:flex;flex-direction:column;gap:8px' });
  body.append(
    el('div', { class: 'kv' }, el('span', {}, 'Layer'), el('b', {}, layer.name)),
    el('div', { class: 'field' }, el('label', {}, 'Opacity', el('span', { class: 'value' }, `${Math.round(layer.opacity * 100)}%`)),
      (() => {
        const input = el('input', { type: 'range', min: 0, max: 100, step: 1, value: Math.round(layer.opacity * 100) });
        input.addEventListener('input', () => actions.onOpacity(Number(input.value) / 100));
        return input;
      })()),
    el('div', { class: 'field' }, el('label', {}, 'Blend mode'),
      (() => {
        const select = el('select');
        for (const mode of BLEND_MODES) {
          select.append(el('option', { value: mode, selected: layer.blend === mode }, blendLabel(mode)));
        }
        select.addEventListener('change', () => actions.onBlend(select.value));
        return select;
      })()),
    el('div', { class: 'row' },
      iconBtn(ICONS.up, 'Move up', () => actions.onMove(1)),
      iconBtn(ICONS.down, 'Move down', () => actions.onMove(-1)),
      iconBtn(ICONS.copy, 'Duplicate', () => { closeSheet(); actions.onDuplicate(); }),
      iconBtn(ICONS.text, 'Rename', () => { closeSheet(); actions.onRename(); }),
      iconBtn(ICONS.image, 'Trim edges', () => { if (actions.onTrim) { closeSheet(); actions.onTrim(); } }),
      iconBtn(ICONS.trash, 'Delete', () => {
        closeSheet();
        confirmSheet('Delete layer', `“${layer.name}” will be removed. You can undo this.`, 'Delete', actions.onDelete, true);
      }),
    ),
  );
  if (isText(layer) && actions.onRasterize) {
    body.append(el('button', { class: 'menu-item', onclick: () => { closeSheet(); actions.onRasterize!(); } },
      el('span', { class: 'grow' }, 'Rasterize text'),
      el('small', {}, 'Turns the layer into pixels so brushes can paint on it — the type is no longer editable')));
  }
  if (isRaster(layer) && layer.unsupported) {
    body.append(el('p', { class: 'hint' }, `This layer (${layer.unsupported}) is kept in the file exactly as it was, but it cannot be previewed here.`));
  }
  showSheet({ title: 'Layer options', body, actions: [el('button', { class: 'btn primary wide', onclick: () => closeSheet() }, 'Close')] });
}

/** Small helper used by the layer list to warn about approximations. */
export function blendNote(mode: string): string | null {
  switch (mode) {
    case 'normal': case 'pass through': case 'multiply': case 'screen': case 'overlay':
    case 'darken': case 'lighten': case 'color dodge': case 'color burn': case 'hard light':
    case 'soft light': case 'difference': case 'exclusion': case 'hue': case 'saturation':
    case 'color': case 'luminosity': case 'linear dodge':
      return null;
    default:
      return `“${blendLabel(mode)}” is not available in the browser and is shown as Normal here. The mode is written back into the file unchanged.`;
  }
}

export function layerSummary(layer: Layer): string {
  const bits: string[] = [];
  if (isGroup(layer)) bits.push(`${layer.children.length} layers`);
  else if (isText(layer)) bits.push(`${Math.round(layer.text.size)} px · ${layer.text.font.psdName}`);
  else if (isRaster(layer) && layer.pixels) bits.push(`${layer.w}×${layer.h}`);
  if (layer.blend !== 'normal' && layer.blend !== 'pass through') bits.push(blendLabel(layer.blend));
  if (layer.opacity < 1) bits.push(`${Math.round(layer.opacity * 100)}%`);
  if (layer.unsupported) bits.push('kept');
  return bits.join(' · ');
}

export function toastDocStats(doc: Doc): void {
  toast(`${doc.width} × ${doc.height} px · ${(documentPixelCount(doc) / 1_000_000).toFixed(1)} Mpx of layer data`, 'info', 2600);
}
