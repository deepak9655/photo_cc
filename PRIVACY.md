# Privacy

The Android app is offline by construction. There is no backend, no account
system, no analytics, no crash reporting, no advertising and no third-party code
in the app at all.

## Permissions

**None.** `AndroidManifest.xml` declares no `<uses-permission>` elements — not
even `INTERNET`, so the app cannot open a socket, and not storage permissions,
because files are opened and saved through the system file picker (SAF), which
hands the app only the one file you picked.

That is a design decision, not an oversight: because the app cannot reach the
network, "your files never leave the phone" is enforced by the operating system
rather than promised by a policy.

## What happens to your files

* A file you open is read into memory on the device; the PSD is decoded there.
* Edits stay in memory (with undo history) until you save.
* Saving writes to the location **you** choose in the system save dialog. The app
  never writes anywhere else.
* `allowBackup` is disabled, so document data is not copied into cloud backups by
  the system.

## What is stored on the device

Nothing. No preferences, no recent-files list, no caches, no identifiers. Close
the document (or the app) and no copy remains beyond the file you saved yourself.

## Network access

None. The app has no networking code and no permission to use the network.

## Android "share to" and "open with"

If you share a photo to LayerPad, or open a `.psd` with it from a file manager,
the file you chose is handed to the app by the operating system. Nothing else is
accessible and nothing is sent anywhere.

## Changes

If a future version ever adds anything that talks to a network, it will be
opt-in, documented here first, and off by default. The project's stated goal is
that the editor keeps working with the phone in flight mode.

## Web companion

`web/` is an optional browser build of the same idea, kept for convenience. It
runs entirely in the tab, makes no network requests after loading, and stores
only its own service-worker cache so it can start offline. It is not required by
the Android app.
