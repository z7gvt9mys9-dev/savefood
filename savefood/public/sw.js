const CACHE_NAME = 'savefood-cache-v4';
const PUSH_STATE_CACHE = 'savefood-push-state-v1';
const PUSH_STATE_URL = '/__savefood_push_state__';
const KEEP_CACHES = [CACHE_NAME, PUSH_STATE_CACHE];
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
const clearSavefoodCaches = () => caches.keys()
  .then(keys => Promise.all(keys
    .filter(key => key.startsWith('savefood-') && key !== PUSH_STATE_CACHE)
    .map(key => caches.delete(key))));
let pushStateQueue = Promise.resolve();
self.setPushEnabled = (enabled, revision = Date.now() * 1000) => {
  const candidate = { enabled: !!enabled, revision };
  pushStateQueue = pushStateQueue.catch(() => {}).then(async () => {
    const cache = await caches.open(PUSH_STATE_CACHE);
    const response = await cache.match(PUSH_STATE_URL);
    const current = response ? await response.json() : { enabled: false, revision: 0 };
    if (candidate.revision < current.revision
        || (candidate.revision === current.revision && !current.enabled && candidate.enabled)) {
      return;
    }
    await cache.put(PUSH_STATE_URL, new Response(JSON.stringify(candidate), {
      headers: { 'Content-Type': 'application/json' },
    }));
  });
  return pushStateQueue;
};
self.isPushEnabled = () => pushStateQueue.catch(() => {})
  .then(() => caches.open(PUSH_STATE_CACHE))
  .then(cache => cache.match(PUSH_STATE_URL))
  .then(response => response ? response.json() : { enabled: false })
  .then(state => state.enabled === true)
  .catch(() => false);
self.addEventListener('message', event => {
  if (event.data?.type === 'CLEAR_SESSION_CACHE') {
    const disablePush = self.setPushEnabled(false);
    event.waitUntil(Promise.all([disablePush, clearSavefoodCaches()]));
  }
  if (event.data?.type === 'SET_PUSH_ENABLED') {
    const revision = Number.isSafeInteger(event.data.revision) ? event.data.revision : 0;
    event.waitUntil(self.setPushEnabled(event.data.enabled === true, revision));
  }
});
self.addEventListener('push', event => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) {}
  event.waitUntil(
    self.isPushEnabled().then(enabled => enabled
      ? self.registration.showNotification(data.title || 'SaveFood', {
        body: data.body || '',
        data: { url: data.url || '/' },
      })
      : undefined)
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
  if (event.request.method !== 'GET' || event.request.headers.has('authorization')) return;
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
    return;
  }
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() =>
        caches.match('/index.html')
      )
    );
    return;
  }
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
