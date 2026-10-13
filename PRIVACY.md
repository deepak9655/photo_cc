# Privacy

photo_cc is an offline application. There is no backend, no account system, no
analytics, no crash reporting, no advertising and no third-party requests.

## What happens to your files

* Files you open are read by the browser tab on your device, in memory only.
  They are never uploaded anywhere: the app makes no network requests after it
  has loaded (and after the first visit it works with the network switched off).
* Edits stay in memory until you save. Saving writes the file to storage **you**
  choose (the browser's download folder, or a location you pick in the system
  file dialog).
* Closing or reloading the tab discards unsaved edits — the app warns you before
  that happens.

## What is stored on your device

* A **service worker cache** holding the app's own HTML/CSS/JS/icons so it can
  start offline. You can clear it from your browser settings at any time.
* Nothing else. No document data, no history, no preferences, no identifiers.

## Network access

The app requests nothing from the network at runtime. If you self-host it, the
only requests are for its own static files from the same origin.

## Permissions

A web app URL cannot ask for device permissions by itself. The installed PWA
uses the system file picker for opening and saving, and — if you opt in by
sharing a file from another app — the Android share sheet hands the file
straight to the page. No contacts, location, camera or microphone access is ever
requested.

## Changes

If a future version ever adds anything that talks to a network, it will be
opt-in, documented here first, and off by default. The project's stated goal is
that the editor keeps working with the phone in flight mode.
