# Third-party components

photo_cc ships no Adobe code, fonts, icons or assets. Everything below is
permissively licensed and unmodified except where noted.

## Runtime — Android app

**None.** The app has no runtime dependencies at all: no AndroidX, no Compose, no
image library, no networking library. The PSD container, the image codecs, the
compositor and the text layout are implemented in this repository
(`app/src/main/java/com/photocc/app/core/`), using only the Android framework.

That is a deliberate constraint: it keeps the APK small, the start-up instant and
the app free of anything that could reach the network.

## Test-only — never shipped

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| [ag-psd](https://github.com/Agamnentzar/ag-psd) | 31.0.3 | MIT | Independent reference implementation: the Kotlin reader is compared against it, and the files this project *writes* are verified with it (`tools/engine/`) |
| [@napi-rs/canvas](https://github.com/Brooooooklyn/canvas) | MIT | A 2D canvas inside Node, required by `ag-psd` in the cross-check |
| Kotlin compiler / Gradle / AGP | Apache-2.0 | Build tooling only |

`ag-psd` is an independent implementation of the documented PSD format. It
bundles no Adobe software, and nothing from it is compiled into the APK — it runs
in CI, on the other side of the verification, which is exactly what makes the
comparison meaningful.

## Development only (never shipped)

| Component | Licence | Used for |
| --- | --- | --- |
| [Vite](https://vite.dev) | MIT | Web companion build (`web/`) |
| [TypeScript](https://www.typescriptlang.org) | Apache-2.0 | Web companion type checking |
| [esbuild](https://esbuild.github.io) | MIT | Bundling the web companion's test harness |
| [jsdom](https://github.com/jsdom/jsdom) | MIT | Headless UI smoke test of the web companion |

## Fonts and icons

No font files and no icon files are included in this repository, and none are
embedded into the PSD files the app writes. The font picker refers to families
that are already installed on the device (system UI, Roboto, Noto, …), and when a
document uses a font the phone does not have, the preview substitutes the closest
available family while the original PostScript font name is preserved in the file
for whoever opens it next. The layer preview is rendered live from the text, so
no font is ever shipped with the app.

Every icon in the toolbar and the launcher icon are **original**, drawn for this
project: the toolbar glyphs are built in code (`app/src/main/java/com/photocc/app/ui/Icons.kt`)
and the launcher mark is a vector/shape rendered by `app/src/main/res/*`.

## Trademarks

"Adobe", "Photoshop", "PSD" and "PSB" are trademarks of Adobe Inc., used here
only descriptively to state what file formats this independent project reads and
writes. This project is not affiliated with, endorsed by, or sponsored by Adobe.
See the copyright section in [README.md](README.md).
