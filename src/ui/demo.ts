/**
 * A layered demo document, built in memory so the app has something to show
 * without shipping a large binary. Mirrors what a real PSD import produces:
 * a gradient background, a blended shape layer, a group with two text layers
 * and an adjustment layer that is kept but not previewed.
 */

import { createCanvas, ctx2d } from '../core/render';
import { createTextData, type Doc, type Pixels, type RasterLayer } from '../core/types';
import { createDocument, createGroupLayer, createRasterLayer, createTextLayer } from '../core/ops';

function pixelsFromCanvas(canvas: { width: number; height: number; getContext(id: '2d'): any }): Pixels {
  const ctx = canvas.getContext('2d');
  const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
  return { width: img.width, height: img.height, data: img.data };
}

function paintLayer(name: string, x: number, y: number, w: number, h: number, draw: (ctx: CanvasRenderingContext2D, w: number, h: number) => void): RasterLayer {
  const canvas = createCanvas(w, h);
  draw(ctx2d(canvas), w, h);
  const px = pixelsFromCanvas(canvas as any);
  const layer = createRasterLayer(name, x, y, w, h, px);
  return layer;
}

export function buildDemoDocument(width = 1080, height = 1080): Doc {
  const doc = createDocument(width, height);

  const background = paintLayer('Background', 0, 0, width, height, (ctx, w, h) => {
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, '#1b1035');
    g.addColorStop(0.55, '#33206b');
    g.addColorStop(1, '#0e7f92');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, w, h);
    for (let i = 0; i < 22; i++) {
      const r = 40 + Math.random() * 150;
      ctx.globalAlpha = 0.10 + Math.random() * 0.2;
      ctx.fillStyle = ['#ff8a00', '#ff2d75', '#42e2b8', '#8b6cff'][i % 4];
      ctx.beginPath();
      ctx.arc(Math.random() * w, Math.random() * h, r, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  });

  const blob = paintLayer('Glow', 240, 240, 600, 600, (ctx) => {
    const g = ctx.createRadialGradient(300, 300, 20, 300, 300, 300);
    g.addColorStop(0, 'rgba(255,138,0,0.95)');
    g.addColorStop(0.5, 'rgba(255,45,117,0.55)');
    g.addColorStop(1, 'rgba(255,45,117,0)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(300, 300, 300, 0, Math.PI * 2);
    ctx.fill();
  });
  blob.blend = 'screen';
  blob.opacity = 0.9;

  const badge = paintLayer('Badge', 330, 330, 420, 420, (ctx) => {
    ctx.fillStyle = '#ffd166';
    ctx.beginPath();
    ctx.arc(210, 210, 200, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ff2d75';
    ctx.beginPath();
    ctx.arc(210, 210, 132, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 150px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('cc', 210, 216);
  });

  const group = createGroupLayer('Headline');
  const title = createTextLayer('Title', 70, 800, createTextData({
    text: 'photo_cc',
    size: 104,
    tracking: 20,
    color: { r: 255, g: 255, b: 255, a: 255 },
    font: { family: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif', psdName: 'Roboto-Regular', weight: 700, style: 'normal' },
  }));
  title.shadow = { enabled: true, color: { r: 0, g: 0, b: 0, a: 190 }, blur: 14, dx: 0, dy: 6 };
  const subtitle = createTextLayer('Subtitle', 74, 920, createTextData({
    text: 'layered PSD editing on a phone',
    size: 40,
    color: { r: 200, g: 232, b: 255, a: 255 },
  }));
  group.children.push(title, subtitle);

  const adjustment: RasterLayer = {
    ...createRasterLayer('Brightness/Contrast 1', 0, 0, 0, 0, null),
    unsupported: 'Adjustment layer (kept as-is, not previewed)',
  };

  doc.children.push(background, blob, badge, group, adjustment);
  return doc;
}
