/**
 * Entry point. Keeps boot synchronous and tiny: build the UI shell, then let
 * the app open a file (or the demo document) on demand.
 */
import './style.css';
import { App } from './ui/app';
import { toast } from './ui/dom';

declare global {
  interface Window { photoCC?: App }
}

function boot(): void {
  const app = new App();
  window.photoCC = app;

  // Opening a file straight from the OS "open with" sheet (installed PWA only).
  if ('launchQueue' in window) {
    (window as any).launchQueue.setConsumer(async (params: any) => {
      const handle = params?.files?.[0];
      if (!handle) return;
      const file = await handle.getFile();
      void app.openFile(file);
    });
  }

  void consumeSharedFile(app);
  registerServiceWorker();

  // Warn once if the browser cannot do 2D canvas well enough.
  const probe = document.createElement('canvas').getContext('2d');
  if (!probe) toast('This browser does not support 2D canvas — the editor cannot run.', 'warn', 8000);

  window.addEventListener('appinstalled', () => toast('Installed. It now works offline.', 'good'));
}

/** Registers the offline service worker (skipped on file:// and during dev). */
function registerServiceWorker(): void {
  if (!('serviceWorker' in navigator) || location.protocol === 'file:') return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js', { scope: './' })
      .catch(() => { /* offline support is a bonus, never a blocker */ });
  });
}

/**
 * "Share to photo_cc" on Android posts the file to the service worker, which
 * redirects here with `?shared=1` after stashing it in the cache.
 */
async function consumeSharedFile(app: App): Promise<void> {
  const params = new URLSearchParams(location.search);
  if (!params.has('shared') || !('caches' in window)) return;
  history.replaceState(null, '', location.pathname);
  try {
    const cache = await caches.open('photo-cc-v1');
    const response = await cache.match('./__shared-file');
    if (!response) return;
    const blob = await response.blob();
    const name = decodeURIComponent(response.headers.get('x-file-name') || 'shared.psd');
    await cache.delete('./__shared-file');
    toast(`Opening ${name}…`, 'info', 2000);
    await app.openFile(new File([blob], name, { type: blob.type || 'image/vnd.adobe.photoshop' }));
  } catch {
    toast('Could not read the shared file.', 'warn');
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}
