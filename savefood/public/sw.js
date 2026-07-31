const CACHE_NAME = 'savefood-cache-v4';
const KEEP_CACHES = [CACHE_NAME];

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
      Promise.all(keys.filter(k => !KEEP_CACHES.includes(k)).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// A logout may happen while the worker is stopped or being upgraded, so both
// the page and worker issue this deletion. Cache Storage must not retain data
// belonging to the previous person on a shared device.
const clearSavefoodCaches = () => caches.keys()
  .then(keys => Promise.all(keys
    .filter(key => key.startsWith('savefood-'))
    .map(key => caches.delete(key))));

self.addEventListener('message', event => {
  if (event.data?.type === 'CLEAR_SESSION_CACHE') {
    event.waitUntil(clearSavefoodCaches());
  }
});

// Web Push (VAPID): payload is JSON {title, body, url} from backend/push_service.py
self.addEventListener('push', event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) {}
  event.waitUntil(
    self.registration.showNotification(data.title || 'SaveFood', {
      body: data.body || '',
      data: { url: data.url || '/' },
    })
  );
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      const existing = list.find(c => 'focus' in c);
      if (existing) { existing.navigate(url); return existing.focus(); }
      return clients.openWindow(url);
    })
  );
});

self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);

  // Never intercept authenticated requests. In particular, route points and
  // ticket chat contain home-address data and must not land in Cache Storage.
  if (event.request.method !== 'GET' || event.request.headers.has('authorization')) return;

  // Pass API, WebSocket upgrades, and cross-origin requests straight to network.
  // `/tickets` is owner-protected too; keeping it explicit avoids a future
  // cache-first fallback accidentally storing chat history.
  if (
    url.pathname.startsWith('/auth') ||
    url.pathname.startsWith('/push') ||
    url.pathname.startsWith('/api') ||
    url.pathname.startsWith('/impact/') ||
    url.pathname.startsWith('/shops') ||
    url.pathname.startsWith('/lots') ||
    url.pathname.startsWith('/volunteers') ||
    url.pathname.startsWith('/needy') ||
    url.pathname.startsWith('/admin') ||
    url.pathname.startsWith('/tickets') ||
    url.pathname.startsWith('/stats') ||
    url.pathname.startsWith('/uploads') ||
    url.pathname.startsWith('/needy_uploads') ||
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

  // Cache only the compiled application shell/assets. A broad cache-first
  // fallback is unsafe because a newly added authenticated endpoint could be
  // persisted before this allow-list is updated.
  const isStaticAsset = url.pathname.startsWith('/assets/')
    || url.pathname === '/manifest.json'
    || /\.(?:css|js|mjs|woff2?|ttf|svg|png|jpe?g|webp|ico)$/i.test(url.pathname);
  if (!isStaticAsset) return;

  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) return cached;
      return fetch(event.request).then(response => {
        const cacheControl = response?.headers?.get('Cache-Control') || '';
        if (response && response.status === 200 && !/private|no-store/i.test(cacheControl)) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone));
        }
        return response;
      });
    })
  );
});
