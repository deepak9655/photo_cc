/**
 * Service worker: makes the editor work with no network at all.
 *
 * Strategy (kept deliberately simple so it is easy to audit):
 *  - app shell: cache-first, refreshed in the background
 *  - anything else: network, falling back to the cache
 *  - no analytics, no external hosts, no push
 */
const VERSION = 'photo-cc-v1';
const APP_SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION)
      .then((cache) => cache.addAll(APP_SHELL).catch(() => undefined))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== VERSION).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

const SHARED_FILE_KEY = './__shared-file';

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (new URL(request.url).origin !== self.location.origin) return;

  // "Share to photo_cc" from the Android share sheet posts the file here; we
  // stash it and hand the page a one-shot URL to pick it up (no server needed).
  if (request.method === 'POST' && request.mode === 'navigate') {
    event.respondWith((async () => {
      try {
        const form = await request.formData();
        const file = form.get('file');
        if (file && typeof file !== 'string') {
          const cache = await caches.open(VERSION);
          await cache.put(SHARED_FILE_KEY, new Response(file, {
            headers: { 'content-type': file.type || 'application/octet-stream', 'x-file-name': encodeURIComponent(file.name || 'shared.psd') },
          }));
          return Response.redirect('./?shared=1', 303);
        }
      } catch (error) {
        // fall through to the app
      }
      return Response.redirect('./', 303);
    })());
    return;
  }

  if (request.method !== 'GET') return;

  // navigations: serve the shell, then update the cache in the background
  if (request.mode === 'navigate') {
    event.respondWith(
      caches.match('./index.html').then((cached) => {
        const network = fetch(request)
          .then((response) => {
            if (response.ok) caches.open(VERSION).then((cache) => cache.put('./index.html', response.clone()));
            return response;
          })
          .catch(() => cached);
        return cached ?? network;
      }),
    );
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) {
        fetch(request)
          .then((response) => { if (response.ok) caches.open(VERSION).then((cache) => cache.put(request, response.clone())); })
          .catch(() => undefined);
        return cached;
      }
      return fetch(request)
        .then((response) => {
          if (response.ok && response.type === 'basic') caches.open(VERSION).then((cache) => cache.put(request, response.clone()));
          return response;
        })
        .catch(() => cached ?? Response.error());
    }),
  );
});
