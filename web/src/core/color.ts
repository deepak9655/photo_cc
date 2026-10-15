import type { RGBA } from './types';

/** `#rrggbb` (or `#rgb`) -> RGBA with a=255. Falls back to black. */
export function hexToRgba(hex: string, alpha = 255): RGBA {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return { r: 0, g: 0, b: 0, a: alpha };
  let h = m[1];
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
    a: alpha,
  };
}

export function rgbaToHex(c: RGBA): string {
  const p = (n: number) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
  return `#${p(c.r)}${p(c.g)}${p(c.b)}`;
}

export function rgbaToCss(c: RGBA, alphaScale = 1): string {
  const a = (c.a / 255) * alphaScale;
  return a >= 1 ? rgbaToHex(c) : `rgba(${c.r},${c.g},${c.b},${a})`;
}

/** Reads the alpha as a 0..1 float (PSD stores 0..255 in our model). */
export function alpha01(c: RGBA): number { return c.a / 255; }

export function clamp01(v: number): number { return v < 0 ? 0 : v > 1 ? 1 : v; }

/**
 * ag-psd hands colours over in whichever of the PSD colour spaces the file used.
 * We only need RGBA in the UI, so this collapses everything to straight RGBA.
 */
export function agColorToRgba(c: any): RGBA {
  if (!c) return { r: 0, g: 0, b: 0, a: 255 };
  if ('fr' in c) return { r: Math.round(c.fr * 255), g: Math.round(c.fg * 255), b: Math.round(c.fb * 255), a: 255 };
  if ('c' in c) { // CMYK — quick naive conversion, good enough for a swatch preview
    const k = c.k ?? 0;
    return {
      r: Math.round(255 * (1 - Math.min(1, (c.c ?? 0) + k))),
      g: Math.round(255 * (1 - Math.min(1, (c.m ?? 0) + k))),
      b: Math.round(255 * (1 - Math.min(1, (c.y ?? 0) + k))),
      a: 255,
    };
  }
  if ('k' in c && !('r' in c)) { const v = Math.round((c.k ?? 0) * 255); return { r: v, g: v, b: v, a: 255 }; }
  return { r: c.r | 0, g: c.g | 0, b: c.b | 0, a: c.a === undefined ? 255 : c.a };
}

/** Colour for writing back into a PSD type layer (Photoshop expects RGBA 0..255 for text). */
export function rgbaToAg(c: RGBA): { r: number; g: number; b: number; a: number } {
  return { r: c.r, g: c.g, b: c.b, a: c.a };
}
