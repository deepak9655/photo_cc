/**
 * Creates the sample PSD files used by `npm run check` and by the app's
 * "Open sample" button. Test/development tool — not part of the app bundle.
 *
 * Usage: node tools/make-samples.mjs
 */
import { writePsdBuffer, initializeCanvas } from 'ag-psd';
import { createCanvas } from '@napi-rs/canvas';

// ag-psd needs a canvas implementation when it runs outside a browser
initializeCanvas((w, h) => createCanvas(w, h));
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(root, 'samples');
mkdirSync(outDir, { recursive: true });

function canvas(w, h, draw) {
  const c = createCanvas(w, h);
  const ctx = c.getContext('2d');
  draw(ctx, w, h);
  return c;
}

/** A small "layered poster" that exercises groups, text, masks, blend modes and effects. */
function buildPosterDocument() {
  const W = 1080, H = 1080;

  const background = canvas(W, H, (ctx, w, h) => {
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, '#1b1035');
    g.addColorStop(0.55, '#2c1a5e');
    g.addColorStop(1, '#0d7a8a');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
    // a few soft blobs so blending modes have something to chew on
    for (let i = 0; i < 26; i++) {
      const r = 40 + Math.random() * 180;
      ctx.globalAlpha = 0.10 + Math.random() * 0.22;
      ctx.fillStyle = ['#ff8a00', '#ff2d75', '#42e2b8', '#7c4dff'][i % 4];
      ctx.beginPath();
      ctx.arc(Math.random() * w, Math.random() * h, r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  });

  const grain = canvas(W, H, (ctx, w, h) => {
    const img = ctx.createImageData(w, h);
    for (let i = 0; i < img.data.length; i += 4) {
      const v = 120 + ((Math.random() * 120) | 0);
      img.data[i] = v; img.data[i + 1] = v; img.data[i + 2] = v;
      img.data[i + 3] = 26;
    }
    ctx.putImageData(img, 0, 0);
  });

  const badge = canvas(320, 320, (ctx) => {
    ctx.fillStyle = '#ffd166';
    ctx.beginPath();
    ctx.arc(160, 160, 150, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ff2d75';
    ctx.beginPath();
    ctx.arc(160, 160, 96, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 120px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('cc', 160, 168);
  });

  const chip = canvas(240, 90, (ctx) => {
    ctx.fillStyle = 'rgba(255,255,255,0.92)';
    ctx.beginPath();
    ctx.roundRect(0, 0, 240, 90, 45);
    ctx.fill();
  });

  const maskCanvas = canvas(1080, 300, (ctx, w, h) => {
    const g = ctx.createLinearGradient(0, 0, 0, h);
    g.addColorStop(0, '#ffffff');
    g.addColorStop(1, '#000000');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
  });

  const strip = canvas(1080, 300, (ctx, w, h) => {
    for (let x = 0; x < w; x += 60) {
      ctx.fillStyle = x % 120 === 0 ? '#42e2b8' : '#7c4dff';
      ctx.fillRect(x, 0, 30, h);
    }
  });

  const psd = {
    width: W,
    height: H,
    children: [
      { name: 'Background', canvas: background, opacity: 1 },
      { name: 'Grain', canvas: grain, blendMode: 'overlay', opacity: 0.9 },
      {
        name: 'Footer strip',
        canvas: strip,
        opacity: 0.55,
        blendMode: 'screen',
        top: 700, left: 0, bottom: 1000, right: 1080,
        mask: {
          top: 700, left: 0, bottom: 1000, right: 1080,
          defaultColor: 0,
          disabled: false,
          position: false,
          canvas: maskCanvas,
        },
      },
      {
        name: 'Headline group',
        opened: true,
        blendMode: 'pass through',
        children: [
          { name: 'Badge', canvas: badge, top: 380, left: 380, bottom: 700, right: 700, opacity: 0.95 },
          {
            name: 'Title',
            top: 700,
            left: 60,
            text: {
              text: 'photo_cc',
              transform: [1, 0, 0, 1, 60, 800],
              left: 60, top: 700, right: 700, bottom: 810,
              shapeType: 'point',
              antiAlias: 'smooth',
              orientation: 'horizontal',
              index: 0,
              style: {
                font: { name: 'Arial-BoldMT', script: 0, type: 0, synthetic: 0 },
                fontSize: 96,
                fauxBold: false,
                fauxItalic: false,
                autoLeading: true,
                tracking: 20,
                fillColor: { r: 255, g: 255, b: 255, a: 255 },
              },
              paragraphStyle: { justification: 0, autoHyphenate: false },
            },
            effects: {
              dropShadow: [{
                enabled: true, present: true, showInDialog: true,
                color: { r: 0, g: 0, b: 0, a: 255 },
                opacity: 0.75, angle: 120, distance: { units: 'Pixels', value: 12 },
                size: { units: 'Pixels', value: 18 }, blendMode: 'multiply',
              }],
            },
          },
          {
            name: 'Subtitle',
            top: 820,
            left: 62,
            text: {
              text: 'layered editing · PSD in, PSD out',
              transform: [1, 0, 0, 1, 62, 860],
              left: 62, top: 820, right: 700, bottom: 880,
              shapeType: 'point',
              antiAlias: 'smooth',
              orientation: 'horizontal',
              index: 1,
              style: {
                font: { name: 'Roboto-Regular', script: 0, type: 0, synthetic: 0 },
                fontSize: 38,
                autoLeading: true,
                tracking: 0,
                fillColor: { r: 190, g: 230, b: 255, a: 255 },
              },
              paragraphStyle: { justification: 0, autoHyphenate: false },
            },
          },
        ],
      },
      {
        name: 'Chip',
        canvas: chip,
        top: 60, left: 60, bottom: 150, right: 300,
        opacity: 1,
        blendMode: 'normal',
      },
      {
        name: 'Brightness/Contrast 1',
        adjustment: { type: 'brightness/contrast', brightness: 12, contrast: 18 },
        opacity: 1,
      },
      {
        name: 'Hidden scratch layer',
        canvas: canvas(200, 200, (ctx) => { ctx.fillStyle = '#ff0000'; ctx.fillRect(0, 0, 200, 200); }),
        top: 800, left: 800, bottom: 1000, right: 1000,
        hidden: true,
      },
    ],
  };

  return writePsdBuffer(psd, { generateThumbnail: true });
}

/** A second sample: a plain single-layer "photo" (the common mobile case). */
function buildFlatPhoto() {
  const W = 900, H = 1200;
  const photo = canvas(W, H, (ctx, w, h) => {
    const g = ctx.createLinearGradient(0, 0, 0, h);
    g.addColorStop(0, '#0b1a2b');
    g.addColorStop(0.6, '#134e6f');
    g.addColorStop(1, '#f7b267');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = '#f25c54';
    ctx.beginPath();
    ctx.arc(w * 0.5, h * 0.42, 130, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,0.85)';
    ctx.font = '48px sans-serif';
    ctx.fillText('single flat layer', 40, 80);
  });
  return writePsdBuffer({
    width: W,
    height: H,
    children: [{ name: 'Background', canvas: photo }],
  });
}

const poster = buildPosterDocument();
writeFileSync(resolve(outDir, 'poster.psd'), Buffer.from(poster));
const flat = buildFlatPhoto();
writeFileSync(resolve(outDir, 'flat.psd'), Buffer.from(flat));
console.log(`wrote samples/poster.psd (${poster.byteLength} bytes), samples/flat.psd (${flat.byteLength} bytes)`);
