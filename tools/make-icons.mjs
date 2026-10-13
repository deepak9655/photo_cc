/** Renders the PWA icons from icons/icon.svg with @napi-rs/canvas. Run after editing the SVG. */
import { createCanvas, loadImage } from '@napi-rs/canvas';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const svg = readFileSync(resolve(root, 'icons/icon.svg'), 'utf8');

async function render(size, file, padding = 0) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const dataUrl = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;
  const image = await loadImage(dataUrl);
  const inner = size - padding * 2;
  ctx.drawImage(image, padding, padding, inner, inner);
  writeFileSync(resolve(root, 'icons', file), canvas.toBuffer('image/png'));
  console.log('wrote icons/' + file);
}

await render(192, 'icon-192.png');
await render(512, 'icon-512.png');
await render(512, 'icon-maskable-512.png', Math.round(512 * 0.12));
