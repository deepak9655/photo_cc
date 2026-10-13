# photo_cc

**A layered PSD editor for phones.** Open a `.psd`, move layers, paint, retype the
text, and save a real PSD back — on a small screen, with no account, no upload
and no AI. Built for editing a layered file on the go when you are away from a
desktop.

```
npm install
npm run dev          # http://localhost:5173  (open it on the phone)
npm run build        # static site in dist/
npm run check        # 60+ automated checks: PSD round-trip + headless UI test
```

---

## Why it exists

Desktop-class image editors either need a workstation or a subscription, and the
lightweight Android alternatives often lag behind new Android releases. photo_cc
is the small, boring, offline tool: it opens the layered files you already have
and gives you the handful of edits you actually make on a phone.

It is deliberately **not** a Photoshop clone. There is no cloud, no AI, no
subscription, no telemetry, and no attempt to render every Photoshop feature.

## What it does

| Area | Support |
| --- | --- |
| Open | `.psd` / `.psb`, RGB · Grayscale · Indexed, 8-bit (16/32-bit and CMYK/Lab/Duotone are read as far as possible and reported honestly) |
| Layers | Raster, layer **groups**, text layers, hidden layers, per-layer opacity, ~26 blend modes |
| Paint | Brush with size/hardness/opacity, eraser, rectangle fill — all with per-stroke undo |
| Text | Create, retype, restyle: font, size, alignment, tracking, line height, bold/italic/underline, colour, outline, drop shadow |
| Move | Drag any layer (groups move together), live layer thumbnails, tap-to-select on canvas |
| Save | Layered PSD with an embedded flattened preview, text layers left **editable**, plus flattened PNG export |
| Extras | Undo/redo with pixel patches, layer reorder by dragging, layer rename/duplicate/delete/trim, "share to" support on Android, installable offline PWA |

Everything it cannot draw (adjustment layers, layer masks, layer styles it does
not know, vector paths, smart objects, colour profiles, metadata) is **kept in
the file and written back untouched** — see "Honesty about fidelity" below.

## Mobile-first design

* The document fills the middle of the screen; every control lives in the bottom
  third of the screen where a thumb can reach it.
* One finger drags/paints, two fingers pinch-zoom and pan. Nothing scrolls or
  reflows while you work.
* The layer strip is one tap away and doubles as the properties panel — no
  tab-hunting on a 5-inch screen.
* Everything is dark, high-contrast and sized for 44 px+ touch targets.

## Performance model

Phones have little RAM, so the renderer is built around that constraint:

* The document is composited once into an off-screen canvas at 1:1; panning and
  zooming only blit that canvas, so gestures never touch the layer stack.
* An edit invalidates a **rectangle**, and only that rectangle is recomposited —
  a brush dab repaints a dab, not the document.
* Layer buffers come from a canvas pool with an LRU budget; group buffers are the
  size of the group's content, not the document.
* Documents above 12 Mpx skip the off-screen buffer entirely and composite only
  the visible region, so a 6000 × 6000 file opens on a mid-range phone.
* Painting is pure typed-array maths (no per-dab canvas uploads), and undo stores
  only the pixel tiles a stroke touched — typically tens of KB, not the whole
  document.
* Text layout is measured once per style+string and cached.

Bundle: ~92 kB app code + 295 kB for the PSD codec (~120 kB gzipped in total),
no fonts, no images, no runtime dependencies beyond `ag-psd`.

## Honesty about fidelity

Photoshop is a huge format. Rather than silently dropping what it cannot do,
photo_cc tells you and preserves everything:

* Unsupported colour modes are refused **before** loading, with the reason
  ("CMYK … save as RGB 8-bit first"), instead of showing wrong colours.
* Blend modes the browser cannot reproduce (Linear Burn, Vivid Light, Divide, …)
  are previewed as Normal, and both the UI and the saved file keep the original
  mode untouched.
* Adjustment layers, masks, layer styles and smart objects are listed, marked
  `kept`, never drawn, and written back byte-for-byte.
* Fonts: a phone rarely has the desktop font a PSD was made with. photo_cc maps
  the PSD font name onto the closest device font **for the preview only**, and
  writes the original PostScript name back into the file, so the document still
  opens with the right type on a desktop.

## What it is not (and never will be)

No AI upscaling, no generative fill, no cloud sync, no account, no ads, no
subscription, no network calls at runtime. If you want those, this is the wrong
app — that is the point.

## Copyright and trademarks

This project is independent and open source (MIT). It is **not** affiliated with,
sponsored by, or endorsed by Adobe, and it ships no Adobe code, fonts, logos or
assets.

* **File format only.** The PSD/PSB support here is an independent, clean-room
  implementation of a publicly documented container format
  ([Photoshop File Format specification](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/));
  format compatibility is not a copyright concern. No Adobe binary was
  reverse-engineered or copied.
* **No Adobe trademarks in the product.** You will not find the wordmark, the "Ps"
  icon or Adobe's product names in the UI, the icon or the store listing. The
  app is described as a "PSD editor", not as "Photoshop for Android".
* **Adobe fonts are not bundled.** Adobe's bundled fonts (Myriad Pro, Minion Pro,
  …) are licensed only for Adobe applications. photo_cc never embeds a font: it
  asks the OS which fonts are installed and otherwise falls back to a generic
  family for the preview.
* **PSD files remain their authors' work.** You need the right to use the files
  you open; the app adds no watermark, DRM or licence claim of its own.
* Third-party components and their licences are listed in
  [`ATTRIBUTIONS.md`](ATTRIBUTIONS.md). `ag-psd` (MIT) is the only runtime
  dependency and it is a clean-room reader/writer as well.

## Privacy

There is no server. Files are read in the browser tab, edits live in memory,
exports are written by you to your own storage. The service worker caches the
app itself for offline use and nothing else. See [`PRIVACY.md`](PRIVACY.md).

## Install on a phone

Open the hosted build in the phone's browser and choose *Add to home screen*.
It then launches fullscreen, opens files from the system file picker, and
registers as a handler for `.psd` files (Android share sheet → photo_cc).
Building an Android App Bundle / Capacitor wrapper is a thin step on top; the
whole app is static files.

## Project layout

```
index.html            app shell (top bar, canvas stage, tool rail, layer strip)
public/               PWA manifest, service worker, icons
src/core/             engine — no DOM assumptions, fully unit-testable
  types.ts            the small data model everything else speaks
  psd.ts              PSD import/export (the ag-psd boundary lives only here)
  render.ts           compositing, regions, culling, text layout, canvas pool
  ops.ts              document operations, brush/eraser engine, undo patches
  fonts.ts            PSD font name ↔ device font stack mapping
src/ui/               mobile UI (plain DOM, no framework)
  app.ts              controller: tools, layers, history, panels, file I/O
  canvasView.ts       viewport: gestures, zoom, region invalidation
  panels.ts           bottom sheets: colour, brush, text, export, info
  dom.ts / history.ts / demo.ts
tools/                dev + test tooling (samples, PSD round-trip, UI smoke test)
```

The core never touches the DOM directly — it goes through a tiny canvas
indirection, which is why the entire engine runs headless in Node for tests.

## Testing

`npm run check` runs two suites:

1. **`tools/roundtrip.mjs`** builds sample PSDs (groups, text with tracking, layer
   styles, masks, blend modes, an adjustment layer), imports them, edits them
   (retype text, brush stroke, new layer, opacity), exports, re-parses the result
   with an independent parser path and asserts that every layer, mask, effect and
   adjustment layer survived.
2. **`tools/ui-smoke.mjs`** boots the real browser entry point inside jsdom with a
   real 2D canvas, then clicks the actual buttons: open document, paint, undo,
   redo, add/rename/hide/delete/reorder layers, create text, fill, save a PSD,
   re-open it, export a PNG.

Renderer output is written to `tools/out/*.png` for visual inspection.

Optional third suite — files this project did not write (worth running before a
release):

```bash
mkdir -p /tmp/realpsd
for f in test/read-write/text/src.psd test/layer-mask/src.psd test/multiple-strokes-test.psd; do
  gh api "repos/Agamnentzar/ag-psd/contents/$f" --jq .content | base64 -d > "/tmp/realpsd/$(basename $(dirname $f)).psd"
done
node tools/real-psd-test.mjs      # imports, renders, exports and re-parses each one
```

## Roadmap ideas

* Transform tool (scale/rotate a layer) — the model already stores rectangles.
* Layer masks: expose the ones that are already preserved.
* Cropping and document resize.
* Android wrapper (TWA/Capacitor) for the Play Store.
* Lasso selection + fill.

Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licence

MIT © the photo_cc contributors. See [`LICENSE`](LICENSE).
