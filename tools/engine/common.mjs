/**
 * Shared helpers for the engine verification tools.
 *
 * These scripts never ship with the app: they exist so the Kotlin engine can be
 * checked against a completely independent implementation (`ag-psd`) on files
 * written by Photoshop and by this project.
 */
import { readPsd, initializeCanvas } from 'ag-psd';
import { createCanvas as napiCreateCanvas } from '@napi-rs/canvas';
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const engineDir = dirname(fileURLToPath(import.meta.url));
export const repoRoot = resolve(engineDir, '..', '..');
export const fixtureDir = resolve(engineDir, 'fixtures');
export const outputDir = process.env.PHOTO_CC_OUT ? resolve(process.env.PHOTO_CC_OUT) : resolve(repoRoot, 'build/engine-test');

/** ag-psd needs a canvas factory for a few paths (thumbnails, composites). */
initializeCanvas((w, h) => napiCreateCanvas(w, h));

export function readPsdBuffer(bytes) {
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  return readPsd(buffer, {
    useImageData: true,
    skipThumbnail: true,
    throwForMissingFeatures: false,
    logMissingFeatures: false,
    skipLayerImageData: false,
    skipCompositeImageData: false,
    ignoreAlpha: false,
  });
}

/** FNV-1a 64-bit over straight r,g,b,a bytes, matching Digest in EngineSelfTest.kt. */
const OFFSET = 0xcbf29ce484222325n;
const PRIME = 0x100000001b3n;
const MASK = 0xffffffffffffffffn;

export function digestRgba(data, width, height, components) {
  let hash = OFFSET;
  const count = Math.min(data.length / components, width * height);
  for (let i = 0; i < count; i++) {
    const base = i * components;
    const r = data[base] & 0xff;
    const g = components > 1 ? data[base + 1] & 0xff : r;
    const b = components > 2 ? data[base + 2] & 0xff : r;
    const a = components > 3 ? data[base + 3] & 0xff : 255;
    for (const byte of [r, g, b, a]) {
      hash = (hash ^ BigInt(byte)) & MASK;
      hash = (hash * PRIME) & MASK;
    }
  }
  return hash.toString(16);
}

/** Walks an ag-psd document, flattening groups like the Kotlin model does. */
export function eachLayer(children, visit) {
  for (const layer of children ?? []) {
    visit(layer);
    if (layer.children) eachLayer(layer.children, visit);
  }
}

export function layerSummary(layer, includeTextPixels = false) {
  const isGroup = Array.isArray(layer.children);
  if (isGroup) return { name: layer.name ?? '', kind: 'group', width: 0, height: 0, digest: null };
  if (layer.text) {
    const summary = {
      name: layer.name ?? '',
      kind: 'text',
      width: 0,
      height: 0,
      digest: null,
      text: layer.text.text ?? '',
      fontName: layer.text.style?.font?.name ?? layer.text.styleRuns?.[0]?.style?.font?.name ?? null,
    };
    if (includeTextPixels) {
      const image = layer.imageData;
      if (image && image.width && image.height) {
        const rgba = new Uint8ClampedArray(image.data.buffer, image.data.byteOffset, image.data.length);
        summary.image = {
          width: image.width,
          height: image.height,
          digest: digestRgba(rgba, image.width, image.height, 4),
        };
      }
    }
    return summary;
  }
  const image = layer.imageData;
  if (!image || !image.width || !image.height) {
    return { name: layer.name ?? '', kind: 'raster', width: 0, height: 0, digest: null };
  }
  const components = image.data.BYTES_PER_ELEMENT === 2 ? 4 : 4;
  const sixteenBit = image.data.BYTES_PER_ELEMENT === 2;
  return {
    name: layer.name ?? '',
    kind: 'raster',
    width: image.width,
    height: image.height,
    // 16-bit files are down-shifted by the Kotlin reader, so digests are only
    // comparable for 8-bit data.
    digest: sixteenBit ? null : digestRgba(image.data, image.width, image.height, components),
  };
}

/** Minimal PNG encoder (no native dependency, good enough for verification dumps). */
export function writePng(path, rgba, width, height) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * width * 4, width * 4).copy(raw, y * (width * 4 + 1) + 1);
  }
  const chunk = (type, data) => {
    const out = Buffer.alloc(data.length + 12);
    out.writeUInt32BE(data.length, 0);
    out.write(type, 4, 'ascii');
    data.copy(out, 8);
    let crc = ~0;
    for (let i = 4; i < 8 + data.length; i++) {
      crc ^= out[i];
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
    out.writeUInt32BE((~crc) >>> 0, 8 + data.length);
    return out;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  writeFileSync(path, png);
}
