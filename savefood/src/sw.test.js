import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { describe, expect, it, vi } from 'vitest';

const workerSource = readFileSync(`${process.cwd()}/public/sw.js`, 'utf8');

const loadWorker = () => {
  const listeners = {};
  const stores = new Map();
  let deferredPut = null;
  const showNotification = vi.fn().mockResolvedValue(undefined);
  const cacheFor = (name) => {
    if (!stores.has(name)) stores.set(name, new Map());
    const store = stores.get(name);
    return {
      addAll: vi.fn().mockResolvedValue(undefined),
      put: vi.fn(async (key, response) => {
        const pending = deferredPut;
        deferredPut = null;
        if (pending) await pending.promise;
        store.set(String(key), response.clone());
      }),
      match: vi.fn(async key => store.get(String(key))?.clone()),
    };
  };
  const self = {
    addEventListener: (type, listener) => { listeners[type] = listener; },
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn().mockResolvedValue(undefined) },
    registration: { showNotification },
    location: { origin: 'https://savefood.test' },
  };
  vm.runInNewContext(workerSource, {
    self,
    caches: {
      open: async name => cacheFor(name),
      keys: async () => [...stores.keys()],
      delete: async name => stores.delete(name),
      match: async () => undefined,
    },
    clients: self.clients,
    Response,
    URL,
    Promise,
  });
  return {
    listeners,
    showNotification,
    deferNextPut: () => {
      let release;
      const promise = new Promise(resolve => { release = resolve; });
      deferredPut = { promise, release };
      return release;
    },
  };
};

const dispatchExtendable = async (listener, fields) => {
  let work = Promise.resolve();
  listener({ ...fields, waitUntil: promise => { work = Promise.resolve(promise); } });
  await work;
};

describe('service worker private push policy', () => {
  it('does not display account A notifications after logout disables the browser', async () => {
    const { listeners, showNotification } = loadWorker();
    await dispatchExtendable(listeners.message, {
      data: { type: 'SET_PUSH_ENABLED', enabled: true, revision: 1 },
    });
    await dispatchExtendable(listeners.push, {
      data: { json: () => ({ title: 'SaveFood', body: 'Private chat from A' }) },
    });
    expect(showNotification).toHaveBeenCalledTimes(1);
    await dispatchExtendable(listeners.message, {
      data: { type: 'CLEAR_SESSION_CACHE' },
    });
    await dispatchExtendable(listeners.push, {
      data: { json: () => ({ title: 'SaveFood', body: 'Future private delivery for A' }) },
    });
    expect(showNotification).toHaveBeenCalledTimes(1);
  });

  it('fails closed when no account has reconciled push ownership', async () => {
    const { listeners, showNotification } = loadWorker();
    await dispatchExtendable(listeners.push, {
      data: { json: () => ({ title: 'SaveFood', body: 'Private chat' }) },
    });
    expect(showNotification).not.toHaveBeenCalled();
  });

  it('ignores a stale enable message that arrives after logout disable', async () => {
    const { listeners, showNotification } = loadWorker();
    await dispatchExtendable(listeners.message, {
      data: { type: 'SET_PUSH_ENABLED', enabled: false, revision: 2 },
    });
    await dispatchExtendable(listeners.message, {
      data: { type: 'SET_PUSH_ENABLED', enabled: true, revision: 1 },
    });
    await dispatchExtendable(listeners.push, {
      data: { json: () => ({ title: 'SaveFood', body: 'Stale account A push' }) },
    });
    expect(showNotification).not.toHaveBeenCalled();
  });

  it('waits for a pending logout disable before handling a push', async () => {
    const { listeners, showNotification, deferNextPut } = loadWorker();
    await dispatchExtendable(listeners.message, {
      data: { type: 'SET_PUSH_ENABLED', enabled: true, revision: 1 },
    });
    const releaseDisable = deferNextPut();
    let disableWork;
    listeners.message({
      data: { type: 'SET_PUSH_ENABLED', enabled: false, revision: 2 },
      waitUntil: promise => { disableWork = Promise.resolve(promise); },
    });
    let pushWork;
    listeners.push({
      data: { json: () => ({ title: 'SaveFood', body: 'Racing private push for A' }) },
      waitUntil: promise => { pushWork = Promise.resolve(promise); },
    });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(showNotification).not.toHaveBeenCalled();
    releaseDisable();
    await Promise.all([disableWork, pushWork]);
    expect(showNotification).not.toHaveBeenCalled();
  });
});
