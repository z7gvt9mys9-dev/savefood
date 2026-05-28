const CACHE_NAME = 'savefood-cache-v2';

self.addEventListener('install', event => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll([
      '/',
      '/index.html',
      '/manifest.json',
    ]))
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);

  // Pass API, WebSocket upgrades, and cross-origin requests straight to network
  if (
    url.pathname.startsWith('/auth') ||
    url.pathname.startsWith('/shops') ||
    url.pathname.startsWith('/lots') ||
    url.pathname.startsWith('/volunteers') ||
    url.pathname.startsWith('/needy') ||
    url.pathname.startsWith('/admin') ||
    url.pathname.startsWith('/stats') ||
    url.pathname.startsWith('/uploads') ||
    url.pathname.startsWith('/needy_uploads') ||
    url.pathname.startsWith('/volunteer_uploads') ||
    url.pathname.startsWith('/telegram') ||
    url.pathname.startsWith('/ws') ||
    url.origin !== self.location.origin
  ) {
    return; // let browser handle it normally
  }

  // Network-first for navigation (HTML)
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() =>
        caches.match('/index.html')
      )
    );
    return;
  }

  // Cache-first for static assets (/static/*)
  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) return cached;
      return fetch(event.request).then(response => {
        if (response && response.status === 200) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone));
        }
        return response;
      });
    })
  );
});
