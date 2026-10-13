/**
 * Node shim so the core modules (which are written for the browser) can be
 * exercised headlessly: a canvas implementation plus the few DOM globals the
 * code touches. Test-only.
 */
import { createCanvas, ImageData as NapiImageData } from '@napi-rs/canvas';

if (!globalThis.document) {
  globalThis.document = {
    createElement(tag) {
      if (tag !== 'canvas') throw new Error(`dom-shim: unexpected element <${tag}>`);
      const c = createCanvas(1, 1);
      // the core sets width/height directly
      return c;
    },
  };
}
if (!globalThis.ImageData) {
  globalThis.ImageData = NapiImageData;
}
export { createCanvas, NapiImageData as ImageData };
