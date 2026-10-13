# Contributing

Thanks for helping! The project has one guiding constraint: **it must stay a
small, fast, offline editor for small screens.** Features that pull against that
(a framework, a server, telemetry, a plugin runtime) will normally be declined.

## Getting set up

```bash
npm install
npm run dev        # http://localhost:5173 — open it in the phone's browser
npm run check      # PSD round-trip checks + headless UI smoke test
npm run typecheck
```

`npm run dev` binds to `0.0.0.0`, so a phone on the same Wi-Fi can open
`http://<your-computer>:5173` and test with a real finger.

## Where things live

* `src/core/` is the engine and has **no DOM assumptions**. If you need new
  behaviour from it that only makes sense in a browser, add it here behind the
  existing seam (`setCanvasFactory`) so the headless tests keep working.
* `src/ui/` is plain DOM. No framework, no virtual DOM: build nodes with the
  helpers in `dom.ts`, re-render a panel by clearing and rebuilding it.
* `src/core/psd.ts` is the only file allowed to import `ag-psd`. Everything else
  speaks the small model in `src/core/types.ts`.

## Rules of thumb

1. **Never lose data.** If the app cannot render or edit something, keep the
   original bytes and say so (`unsupported`, `kept`) instead of dropping it.
2. **Be honest in the UI.** Approximations (blend modes, substituted fonts) are
   stated, not hidden.
3. **Touch targets ≥ 44 px, controls in the bottom third.** Test on a narrow
   (360 px) viewport before opening a PR.
4. **Budget RAM.** Anything that allocates per-layer or per-frame full-resolution
   buffers needs to justify itself; measure with the numbers in the status sheet.
5. **No new runtime dependencies** without a discussion in an issue first.
6. **Keep the tests green** (`npm run check`) and add a check for anything you
   fix — `tools/roundtrip.mjs` and `tools/ui-smoke.mjs` are plain Node scripts
   with a `check(label, condition)` helper and no test framework.

## Testing against files you did not write

`tools/real-psd-test.mjs` imports, renders, exports and re-parses every `.psd` in
a directory (default `/tmp/realpsd`, override with `PHOTO_CC_PSD_CORPUS`). Point
it at real-world files when you touch the importer — a handful of PSDs from
different authors and Photoshop versions catches far more than synthetic
fixtures. Do not commit third-party PSD files to this repository.

## Reporting a PSD that misbehaves

Open an issue with: the app version, the phone/browser, and — if you can share it
— a **minimal** PSD that reproduces the problem (one or two layers, no private
content). Knowing which Photoshop version wrote the file helps a lot.

## Copyright

* Do not copy code from Adobe software, from proprietary editors, or from
  projects without a permissive licence.
* Do not add Adobe fonts, logos or trademarks to the UI, the icon or the
  documentation.
* By contributing you agree your work is licensed under the MIT licence.
