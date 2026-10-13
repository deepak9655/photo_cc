/**
 * Font handling.
 *
 * A PSD stores a PostScript font name ("ArialMT", "Roboto-Bold", ...). A phone
 * only has whatever the OS ships. There is no way to embed a font in a PSD, so:
 *
 *  - on import we map the PostScript name onto the best available device font
 *    stack (and remember the original name for writing back),
 *  - on export we write the PostScript name again so Photoshop resolves the
 *    real font when the document is opened on a desktop.
 *
 * Text layers stay *editable* in the saved file: our own rendering is only used
 * for the flattened preview image.
 */

export interface FontRefCss { family: string; weight: number; style: 'normal' | 'italic' }

export interface FontEntry { label: string; css: string; psd: string; weight?: number; italic?: boolean }

/**
 * Fonts a phone/tablet realistically has, plus the universal generic families.
 * Kept short on purpose: a 6-item picker beats a 3000-item menu on a 5" screen.
 */
export const FONT_LIBRARY: FontEntry[] = [
  { label: 'System UI', css: 'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif', psd: 'Roboto-Regular' },
  { label: 'Roboto', css: 'Roboto, system-ui, sans-serif', psd: 'Roboto-Regular' },
  { label: 'Noto Sans', css: '"Noto Sans", Roboto, sans-serif', psd: 'NotoSans-Regular' },
  { label: 'Sans (default)', css: 'sans-serif', psd: 'ArialMT' },
  { label: 'Serif', css: 'serif', psd: 'TimesNewRomanPSMT' },
  { label: 'Monospace', css: 'ui-monospace, "Roboto Mono", monospace', psd: 'CourierNewPSMT' },
  { label: 'Arial', css: 'Arial, "Liberation Sans", "Helvetica Neue", sans-serif', psd: 'ArialMT' },
  { label: 'Helvetica', css: '"Helvetica Neue", Helvetica, Arial, sans-serif', psd: 'HelveticaNeue' },
  { label: 'Times New Roman', css: '"Times New Roman", Times, "Liberation Serif", serif', psd: 'TimesNewRomanPSMT' },
  { label: 'Georgia', css: 'Georgia, "Times New Roman", serif', psd: 'Georgia' },
  { label: 'Verdana', css: 'Verdana, Geneva, sans-serif', psd: 'Verdana' },
  { label: 'Tahoma', css: 'Tahoma, Verdana, sans-serif', psd: 'Tahoma' },
  { label: 'Trebuchet MS', css: '"Trebuchet MS", "Lucida Grande", sans-serif', psd: 'TrebuchetMS' },
  { label: 'Courier New', css: '"Courier New", Courier, monospace', psd: 'CourierNewPSMT' },
  { label: 'Calibri', css: 'Calibri, Candara, "Segoe UI", sans-serif', psd: 'Calibri' },
  { label: 'Segoe UI', css: '"Segoe UI", system-ui, sans-serif', psd: 'SegoeUI' },
  { label: 'Comic Sans MS', css: '"Comic Sans MS", "Comic Neue", cursive', psd: 'ComicSansMS' },
  { label: 'Impact', css: 'Impact, Haettenschweiler, sans-serif', psd: 'Impact' },
  { label: 'Open Sans', css: '"Open Sans", "Segoe UI", sans-serif', psd: 'OpenSans-Regular' },
  { label: 'Lato', css: 'Lato, "Segoe UI", sans-serif', psd: 'Lato-Regular' },
  { label: 'Montserrat', css: 'Montserrat, "Segoe UI", sans-serif', psd: 'Montserrat-Regular' },
  { label: 'PT Sans', css: '"PT Sans", Roboto, sans-serif', psd: 'PTSans-Regular' },
  { label: 'Noto Serif', css: '"Noto Serif", Georgia, serif', psd: 'NotoSerif-Regular' },
  { label: 'Liberation Sans', css: '"Liberation Sans", Arial, sans-serif', psd: 'LiberationSans' },
  { label: 'DejaVu Sans', css: '"DejaVu Sans", Verdana, sans-serif', psd: 'DejaVuSans' },
];

export const FALLBACK_FONT = FONT_LIBRARY[0];

/** PostScript name -> device font stack. Unknown names degrade to a generic family. */
const PSD_TO_CSS: Record<string, FontRefCss> = buildPsdMap();

function buildPsdMap(): Record<string, FontRefCss> {
  const map: Record<string, FontRefCss> = {};
  for (const f of FONT_LIBRARY) {
    map[stripSuffix(f.psd)] = { family: f.css, weight: 400, style: 'normal' };
  }
  const add = (psd: string, family: string, weight = 400, style: 'normal' | 'italic' = 'normal') => {
    map[stripSuffix(psd)] = { family, weight, style };
  };
  // Adobe's classic PostScript names
  add('ArialMT', 'Arial, "Liberation Sans", "Helvetica Neue", sans-serif');
  add('Arial-BoldMT', 'Arial, "Liberation Sans", sans-serif', 700);
  add('Arial-ItalicMT', 'Arial, sans-serif', 400, 'italic');
  add('Arial-BoldItalicMT', 'Arial, sans-serif', 700, 'italic');
  add('Arial-Black', 'Arial Black, Arial, sans-serif', 900);
  add('ArialNarrow', 'Arial Narrow, Arial, sans-serif', 400);
  add('HelveticaNeue', '"Helvetica Neue", Helvetica, Arial, sans-serif');
  add('HelveticaNeue-Bold', '"Helvetica Neue", Helvetica, Arial, sans-serif', 700);
  add('Helvetica', 'Helvetica, Arial, sans-serif');
  add('Helvetica-Bold', 'Helvetica, Arial, sans-serif', 700);
  add('TimesNewRomanPSMT', '"Times New Roman", Times, serif');
  add('TimesNewRomanPS-BoldMT', '"Times New Roman", Times, serif', 700);
  add('TimesNewRomanPS-ItalicMT', '"Times New Roman", Times, serif', 400, 'italic');
  add('Times-Roman', 'Times, "Times New Roman", serif');
  add('CourierNewPSMT', '"Courier New", Courier, monospace');
  add('CourierNewPS-BoldMT', '"Courier New", Courier, monospace', 700);
  add('MyriadPro-Regular', 'system-ui, -apple-system, "Segoe UI", sans-serif');
  add('MyriadPro-Bold', 'system-ui, -apple-system, "Segoe UI", sans-serif', 700);
  add('MyriadPro-Cond', 'system-ui, sans-serif', 400);
  add('MinionPro-Regular', 'Georgia, "Times New Roman", serif');
  add('Futura-Book', 'Futura, "Century Gothic", "Trebuchet MS", sans-serif');
  add('CenturyGothic', '"Century Gothic", Futura, "Trebuchet MS", sans-serif');
  add('GillSans', '"Gill Sans", "Trebuchet MS", sans-serif');
  add('Rockwell', 'Rockwell, Georgia, serif');
  add('Baskerville', 'Baskerville, "Times New Roman", serif');
  add('Didot', 'Didot, "Times New Roman", serif');
  add('Copperplate', 'Copperplate, "Trebuchet MS", sans-serif');
  add('BrushScriptMT', '"Brush Script MT", cursive');
  add('PalatinoLinotype-Roman', '"Palatino Linotype", Palatino, Georgia, serif');
  add('Candara', 'Candara, Calibri, sans-serif');
  add('Consolas', 'Consolas, "Courier New", monospace');
  add('Assistant-Regular', 'Assistant, system-ui, sans-serif');
  add('Inter', 'Inter, system-ui, sans-serif');
  add('Inter-Regular', 'Inter, system-ui, sans-serif');
  add('Inter-Bold', 'Inter, system-ui, sans-serif', 700);
  add('Poppins-Regular', 'Poppins, system-ui, sans-serif');
  add('Poppins-Bold', 'Poppins, system-ui, sans-serif', 700);
  add('Rubik-Regular', 'Rubik, system-ui, sans-serif');
  add('Karla', 'Karla, system-ui, sans-serif');
  add('Merriweather', 'Merriweather, Georgia, serif');
  add('PlayfairDisplay-Regular', '"Playfair Display", Georgia, serif');
  add('SourceSansPro-Regular', '"Source Sans Pro", "Noto Sans", sans-serif');
  add('SourceSansPro-Bold', '"Source Sans Pro", "Noto Sans", sans-serif', 700);
  add('HelveticaNeueLTStd-Roman', '"Helvetica Neue", Helvetica, sans-serif');
  add('AdobeInvisFont', FALLBACK_FONT.css);
  return map;
}

function stripSuffix(name: string): string {
  return name.replace(/[-_](Regular|Roman|It|Bold|Light|Medium|Semibold|SemiBold|Black|Thin|Condensed|Cond)$/i, '')
    .replace(/(Regular|Roman)$/i, '')
    .toLowerCase();
}

const BOLD_HINTS = /bold|black|heavy|semibold|semi|demi|extrabold|ultra/i;
const ITALIC_HINTS = /italic|oblique|it$/i;

/** Best device font for a PostScript name found in a PSD. */
export function psdFontToCss(psdName: string): FontRefCss {
  if (!psdName) return { family: FALLBACK_FONT.css, weight: 400, style: 'normal' };
  const direct = PSD_TO_CSS[stripSuffix(psdName)];
  const weight = BOLD_HINTS.test(psdName) ? 700 : direct?.weight ?? 400;
  const style: 'normal' | 'italic' = ITALIC_HINTS.test(psdName) ? 'italic' : direct?.style ?? 'normal';
  if (direct) return { family: direct.family, weight, style };
  // Unknown font: keep the family name (the OS substitutes if it is missing) so
  // that saving from a device with the font installed stays faithful.
  const cleaned = psdName.replace(/[-_](Regular|Roman|MT|PSMT|Std|Pro)$/i, '').replace(/([a-z])([A-Z])/g, '$1 $2').trim();
  if (/^[A-Za-z0-9 ]+$/.test(cleaned) && cleaned.length > 1) {
    return { family: `"${cleaned}", ${FALLBACK_FONT.css}`, weight, style };
  }
  return { family: FALLBACK_FONT.css, weight, style };
}

/** CSS family stack -> PostScript-ish name for writing into a PSD. */
export function cssToPsdFontName(cssFamily: string, weight = 400, italic = false): string {
  const first = cssFamily.split(',')[0].trim().replace(/^["']|["']$/g, '');
  const known = FONT_LIBRARY.find((f) => f.css.startsWith(first) || f.label.toLowerCase() === first.toLowerCase());
  let base = known ? known.psd : `${first.replace(/\s+/g, '')}-Regular`;
  if (!/-/.test(base)) base = `${base}-Regular`;
  if (weight >= 700) base = base.replace(/-Regular$/, '-Bold');
  if (italic) base = base.replace(/-Regular$/, '-Italic');
  return base;
}

/* --------------------------------------------------------- availability probe */

const probeCache = new Map<string, boolean>();
let probeCtx: CanvasRenderingContext2D | null = null;

/**
 * Rough "is this family actually installed" probe: compare the width of a test
 * string against the generic families. Not exact, but it lets the font picker
 * mark entries that will obviously be substituted.
 */
export function fontLikelyAvailable(family: string): boolean {
  const key = family;
  const cached = probeCache.get(key);
  if (cached !== undefined) return cached;
  try {
    if (!probeCtx) {
      const canvas = document.createElement('canvas');
      canvas.width = 8; canvas.height = 8;
      probeCtx = canvas.getContext('2d');
    }
    const ctx = probeCtx!;
    const sample = 'WgQjmm1234 iIlL';
    ctx.font = '16px monospace';
    const mono = ctx.measureText(sample).width;
    ctx.font = '16px serif';
    const serif = ctx.measureText(sample).width;
    ctx.font = '16px sans-serif';
    const sans = ctx.measureText(sample).width;
    ctx.font = `16px ${family.includes(',') ? family.split(',')[0].trim() : family}, monospace`;
    const probe = ctx.measureText(sample).width;
    const result = probe !== mono || mono === serif || mono === sans ? probe !== mono : false;
    probeCache.set(key, result);
    return result;
  } catch {
    return true;
  }
}
