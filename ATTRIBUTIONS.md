# Third-party components

photo_cc ships no Adobe code, fonts, icons or assets. Everything below is
permissively licensed and unmodified except where noted.

## Runtime

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| [ag-psd](https://github.com/Agamnentzar/ag-psd) | 31.0.3 | MIT | Reading and writing the PSD/PSB container (the only runtime dependency) |
| [base64-js](https://github.com/beatgammit/base64-js) | 1.5.x | MIT | Transitive dependency of `ag-psd` (base64 for embedded type engine data) |

`ag-psd` is an independent, clean-room implementation of the documented PSD
format. It bundles no Adobe software.

## Development only (never shipped)

| Component | Licence | Used for |
| --- | --- | --- |
| [Vite](https://vite.dev) | MIT | Dev server and production build |
| [TypeScript](https://www.typescriptlang.org) | Apache-2.0 | Type checking |
| [esbuild](https://esbuild.github.io) | MIT | Bundling the test harness |
| [jsdom](https://github.com/jsdom/jsdom) | MIT | Headless UI smoke test |
| [@napi-rs/canvas](https://github.com/Brooooooklyn/canvas) | MIT | A real 2D canvas inside Node for the tests and the sample generator |

## Fonts

No font files are included in this repository, and none are embedded into the
PSD files it writes. The font picker refers to fonts that are already installed
on the device (system UI, Roboto, Noto, Arial, …) or to generic CSS families
(`serif`, `sans-serif`, `monospace`). When a document uses a font the device does
not have, the preview substitutes the closest available family and the original
PostScript font name is preserved in the file for whoever opens it next.

## Trademarks

"Adobe", "Photoshop", "PSD" and "PSB" are trademarks of Adobe Inc., used here
only descriptively to state what file formats this independent project reads and
writes. This project is not affiliated with, endorsed by, or sponsored by Adobe.
See the copyright section in [README.md](README.md).
