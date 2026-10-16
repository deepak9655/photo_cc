# Strata

A layered image editor for Android that opens and writes real `.psd` files, entirely
offline.

Strata is a native Android app — no web view, no account, no network code and no
permissions at all. It reads the Photoshop file format directly (layers, masks, blend
modes, groups, text layers) and writes it back, so the file you save opens in any other
editor and the file you were sent opens here with its layers intact.

## What it does

**Files**

- Opens `.psd` and `.psb` (large document) files, including layer groups, clipping
  masks, layer masks, blend modes, adjustment layers and live text layers.
- Saves back to `.psd`, preserving layer blocks it does not itself edit — a text
  layer's engine data, effects and any unknown resources are carried through byte for
  byte rather than being discarded.
- Opens ordinary images (PNG, JPEG, WebP, BMP, TIFF) as a new document, or places one
  as a layer.
- Exports a flattened PNG, JPEG or WebP.
- Opening and saving go through the system file picker: the app can only touch the
  files you hand it.

**Editing**

- 18 tools: brush, eraser, bucket fill, gradient, text, shape, marquee, lasso, magic
  wand, clone stamp, dodge/burn/sponge/saturate, blur/sharpen/smudge, move, transform,
  crop, colour picker, pan and zoom.
- Layers: reorder, duplicate, delete, group, clip, lock, hide, masks (add, invert,
  apply, delete), 28 blend modes, opacity, merge down, merge visible, flatten.
- 14 adjustment layers that keep changing what is under them: brightness/contrast,
  levels, curves, exposure, vibrance, hue/saturation, colour balance, channel mixer,
  photo filter, gradient map, invert, posterise, threshold, desaturate.
- 19 destructive filters, applied inside the selection if there is one.
- Selections with feathering, anti-aliasing and add/subtract/intersect modes.
- Text layers with font family, size, weight, style, alignment, leading and tracking,
  written into the PSD as real text so they stay editable elsewhere.
- Canvas and image size, crop, trim, 90° rotations, flips, layer scale and rotate.
- Undo and redo with a memory budget: a stroke records only the tiles it touched, and
  older steps drop off the end rather than growing without limit.

**Made for a phone**

- One canvas with pinch-to-zoom and two-finger pan; tools take a single finger.
- Every control is at least 48 dp and sits in the bottom half of the screen, within
  reach of a thumb: a tool rail, panel tabs and one scrollable panel.
- Nothing is behind a long-press or a hidden menu, and nothing needs a stylus.

## Building

The project builds with the Android Gradle plugin and has no third-party dependencies
at all — not even AndroidX. Everything is framework code and the engine in this
repository.

```
gradle assembleDebug
```

(There is no wrapper script checked in; CI installs Gradle 8.9 and the Android SDK
itself. Any Gradle 8.x will do.)

The debug APK lands in `app/build/outputs/apk/debug/`. Continuous integration builds it
on every push, and attaches it to the workflow run as `strata-debug-apk`.

Release builds are minified with R8. To sign one, set `STRATA_KEYSTORE`,
`STRATA_KEYSTORE_PASSWORD`, `STRATA_KEY_ALIAS` and `STRATA_KEY_PASSWORD`; without them
the release build is simply left unsigned.

Requires JDK 17, Android SDK 35, `minSdk 26` (Android 8.0).

## How it is put together

```
app/src/main/java/com/strata/editor/
  core/       the engine — no Android imports at all
  platform/   the Android half of the engine: bitmaps, fonts
  ui/         the canvas, the gestures and every control
```

The engine is deliberately **free of Android**: it is plain Kotlin over `IntArray`
pixels. That is what makes it testable on a desktop JVM (`tools/engine-test.sh` compiles
and runs the suites with nothing but a Kotlin compiler), and it keeps the fiddly parts —
the PSD codec, the compositor, the adjustments — away from anything to do with screens.

Points worth knowing if you are reading the code:

- **Compositing is banded.** A large document is rendered a horizontal strip at a time
  at full resolution and then reduced, so peak memory is one band plus the output, not
  two full-size buffers. `Compositor.BAND_PIXELS` is the budget.
- **The canvas caches what is on screen.** Panning and pinching reuse the cached
  bitmap; a brush stroke recomposites only the rectangle it touched.
- **Undo is tiled.** `History.PixelBuilder` snapshots 256×256 tiles the first time a
  tool writes to them, so painting one corner of a large layer costs kilobytes.
- **Adjustment layers are applied by the compositor** to a copy of the accumulated
  backdrop, not by a layer renderer, which is what lets them affect everything beneath
  them.
- **Text layers keep their engine data.** A text layer is rasterised for display but
  stored as text plus the original PSD descriptor, so round-tripping a file never turns
  live text into a flat image.

## Tests

```
tools/engine-test.sh [path-to-kotlinc] [path-to-kotlin-stdlib.jar]
```

321 checks across four suites — pixels and colours, blend modes, adjustments, filters,
brushes, selections and history (`EngineTest`); reading and writing the PSD format,
including byte-for-byte round trips and the text-layer engine data (`PsdTest`); text
rasterisation and layer rendering (`ContentTest`); and image import/export (`IoTest`).
The script exits non-zero on the first failure, which is what CI runs.

## Privacy

There is nothing to configure and nothing to opt out of. The app has no permissions,
makes no network requests, contains no analytics, no ads and no accounts. Files are read
and written only where the system file picker says they may be.

If the app is force-closed by a crash it writes the stack trace to its own private
directory and mentions it on the next launch; that file never leaves the device and is
deleted as soon as it has been shown.

## Licence

MIT. The code is original; the name, the icon and the interface were designed for this
project. It reads and writes a file format, which is not the same thing as being
affiliated with the application that defined it, and no part of another editor is
included or imitated.
