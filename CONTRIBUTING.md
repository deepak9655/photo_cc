# Contributing

Thanks for helping! The project has one guiding constraint: **it must stay a
small, fast, offline editor for small screens.** Features that pull against that
(a framework, a server, telemetry, a plugin runtime, a heavy dependency) will
normally be declined.

## Getting set up

Requirements: JDK 17, Android SDK 35, Gradle 8.9+. Nothing else — the app has no
runtime dependencies.

```bash
gradle :app:assembleDebug     # debug APK, side-loadable as-is
gradle engineTest             # engine self-test on a desktop JVM
tools/engine-test.sh          # self-test + independent cross-check (needs Node)
```

`tools/engine-test.sh` is the fastest full check: it compiles the engine with
`kotlinc`, runs it against real Photoshop files, and verifies what it writes with
an independent reader (`ag-psd`, see `tools/engine/`). Run it before opening a PR
if you touched anything under `core/` or `doc/`.

## Where things live

* `app/src/main/java/com/photocc/app/core/` is the engine and must stay
  **android-free**: it is compiled and tested on a desktop JVM, and that is what
  makes the PSD codec testable at all. If you need platform behaviour here, add it
  behind the existing seams (`TextEngine.measurer`, `TextEngine.rasterizer`,
  `Render`'s buffer pool) instead of importing `android.*`.
* `app/src/main/java/com/photocc/app/doc/Session.kt` owns editing operations and
  undo/redo, and is android-free too.
* `app/src/main/java/com/photocc/app/platform/` is where Android graphics code
  belongs (text measuring, rasterising, thumbnails).
* `app/src/main/java/com/photocc/app/ui/` is the interface, built in code — no
  layout XML, no Compose, no AndroidX. Add widgets with the helpers in `Widgets.kt`
  and icons as paths in `Icons.kt` (the artwork must be original).
* `app/src/test/java/com/photocc/app/core/EngineSelfTest.kt` is the test harness:
  a plain `main()`, no JUnit, `check(label, condition, detail)` calls grouped in
  sections.

## Rules of thumb

1. **Never lose data.** If the app cannot render or edit something, keep the
   original bytes (`extraInfo`, `unsupported`) and say so in the UI instead of
   dropping it.
2. **Be honest in the UI.** Approximations (blend modes, substituted fonts,
   16-bit down-shifting) are stated, not hidden.
3. **Touch targets ≥ 44 dp, controls in the bottom third.** Check on a narrow
   (360 dp) screen before opening a PR.
4. **Budget RAM and CPU.** Undo must stay patch-based (see `PixelEdit`), the
   canvas must composite only the visible region, and nothing may allocate a
   full-document buffer per frame.
5. **No new dependencies** — runtime *or* test — without an issue first. The
   zero-dependency property is a feature.
6. **Keep the checks green** and add one for anything you fix. A new PSD quirk
   needs a fixture and a `check(...)` that fails without your change.

## Testing against files you did not write

Point the engine test at your own corpus instead of the downloaded fixtures:

```bash
PHOTO_CC_FIXTURES=~/psd-corpus tools/engine-test.sh
```

A handful of PSDs from different authors and Photoshop versions catches far more
than synthetic fixtures. **Do not commit third-party PSD files** to this
repository: `tools/engine/fixtures/` is git-ignored and fetched on demand.

## Reporting a PSD that misbehaves

Open an issue with: the app version, the phone and Android version, and — if you
can share it — a **minimal** PSD that reproduces the problem (one or two layers,
no private content). Knowing which Photoshop version wrote the file helps a lot.

## Copyright

* Do not copy code from Adobe software, from proprietary editors, or from
  projects without a permissive licence.
* Do not add Adobe fonts, logos or trademarks to the UI, the icon, the name or
  the documentation. The app's name, icon and all artwork are original.
* By contributing you agree your work is licensed under the MIT licence.
