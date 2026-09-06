import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SESSION_STORAGE_KEY,
  PUSH_ENDPOINT_STORAGE_KEY,
  PUSH_OWNER_STORAGE_KEY,
  authFetch,
  browserPushBelongsTo,
  getFreshAccessToken,
  getSession,
  rememberBrowserPushOwnership,
  revokeAndClearSession,
  setBrowserPushDisplayEnabled,
  storeSession,
} from './api';
const jwt = (expSeconds, claims = {}) => [
  window.btoa(JSON.stringify({ alg: 'none' })).replace(/=+$/, ''),
  window.btoa(JSON.stringify({ exp: expSeconds, ...claims })).replace(/=+$/, ''),
  'signature',
].join('.');
const response = (status, data = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  json: vi.fn().mockResolvedValue(data),
});
describe('web refresh-token session', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  it('stores an interactive login as one atomic access/refresh pair', () => {
    storeSession('access-1', 'refresh-1', 'volunteer', 12);
    expect(JSON.parse(window.localStorage.getItem(SESSION_STORAGE_KEY))).toEqual({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      role: 'volunteer',
      relatedId: 12,
    });
    expect(window.localStorage.getItem('token')).toBeNull();
  });
  it('rotates both credentials and succeeds after access expiry', async () => {
    const expired = jwt(Math.floor(Date.now() / 1000) - 1);
    const fresh = jwt(Math.floor(Date.now() / 1000) + 900);
    storeSession(expired, 'refresh-1', 'needy', 4);
    const fetchMock = vi.fn((url, init) => {
      if (String(url).endsWith('/auth/refresh')) {
        return Promise.resolve(response(200, { access_token: fresh, refresh_token: 'refresh-2' }));
      }
      return Promise.resolve(response(init.headers.get('Authorization') === `Bearer ${fresh}` ? 200 : 401));
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await authFetch('/needy/4/tickets');
    expect(result.status).toBe(200);
    expect(getSession()).toMatchObject({ accessToken: fresh, refreshToken: 'refresh-2' });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ refresh_token: 'refresh-1' });
  });
  it('uses one refresh for two concurrent 401 responses', async () => {
    const oldAccess = jwt(Math.floor(Date.now() / 1000) + 600);
    const newAccess = jwt(Math.floor(Date.now() / 1000) + 900);
    storeSession(oldAccess, 'refresh-1', 'shop', 3);
    let refreshCalls = 0;
    const fetchMock = vi.fn((url, init) => {
      if (String(url).endsWith('/auth/refresh')) {
        refreshCalls += 1;
        return Promise.resolve(response(200, { access_token: newAccess, refresh_token: 'refresh-2' }));
      }
      return Promise.resolve(response(
        init.headers.get('Authorization') === `Bearer ${newAccess}` ? 200 : 401,
      ));
    });
    vi.stubGlobal('fetch', fetchMock);
    const [first, second] = await Promise.all([
      authFetch('/shops/3'),
      authFetch('/shops/3/lots'),
    ]);
    expect([first.status, second.status]).toEqual([200, 200]);
    expect(refreshCalls).toBe(1);
  });
  it('does not loop or discard the session on a transient refresh failure', async () => {
    const expired = jwt(Math.floor(Date.now() / 1000) - 1);
    storeSession(expired, 'refresh-1', 'volunteer', 9);
    const fetchMock = vi.fn((url) => {
      if (String(url).endsWith('/auth/refresh')) return Promise.reject(new TypeError('offline'));
      return Promise.resolve(response(401));
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await authFetch('/volunteers/9/location');
    expect(result.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getSession()?.refreshToken).toBe('refresh-1');
  });
  it('clears the session when refresh is invalid or revoked', async () => {
    const expired = jwt(Math.floor(Date.now() / 1000) - 1);
    storeSession(expired, 'revoked-refresh', 'needy', 2);
    vi.stubGlobal('fetch', vi.fn((url) => Promise.resolve(
      response(String(url).endsWith('/auth/refresh') ? 401 : 401),
    )));
    await authFetch('/needy/2/profile');
    expect(getSession()).toBeNull();
  });
  it('logout clears locally and asks the server to revoke the refresh session', async () => {
    storeSession('access-1', 'refresh-secret', 'admin', null);
    const fetchMock = vi.fn().mockResolvedValue(response(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await revokeAndClearSession();
    expect(getSession()).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0][0])).toBe('/auth/logout');
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ refresh_token: 'refresh-secret' });
  });
  it('logout removes browser push ownership and still revokes when local cleanup fails', async () => {
    const access = jwt(Math.floor(Date.now() / 1000) + 900, { sub: '41' });
    storeSession(access, 'refresh-secret', 'needy', 4);
    rememberBrowserPushOwnership('https://push.example/shared-browser', access);
    const unsubscribe = vi.fn().mockRejectedValue(new Error('browser failure'));
    const postMessage = vi.fn();
    vi.stubGlobal('navigator', {
      serviceWorker: {
        controller: { postMessage },
        getRegistration: vi.fn().mockResolvedValue({
          active: { postMessage },
          pushManager: {
            getSubscription: vi.fn().mockResolvedValue({
              endpoint: 'https://push.example/shared-browser', unsubscribe,
            }),
          },
        }),
      },
    });
    const fetchMock = vi.fn((url) => String(url).endsWith('/push/unsubscribe')
      ? Promise.reject(new TypeError('offline'))
      : Promise.resolve(response(200, { ok: true })));
    vi.stubGlobal('fetch', fetchMock);
    await revokeAndClearSession();
    expect(getSession()).toBeNull();
    const logout = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/logout'));
    expect(JSON.parse(logout[1].body)).toEqual({
      refresh_token: 'refresh-secret',
      push_endpoint: 'https://push.example/shared-browser',
    });
    expect(unsubscribe).toHaveBeenCalled();
    expect(postMessage).toHaveBeenCalledWith(expect.objectContaining({
      type: 'SET_PUSH_ENABLED', enabled: false,
    }));
    expect(window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY)).toBeNull();
  });
  it('does not consider another account owner of an existing browser subscription', () => {
    const accountA = jwt(Math.floor(Date.now() / 1000) + 900, { sub: '41' });
    const accountB = jwt(Math.floor(Date.now() / 1000) + 900, { sub: '52' });
    const endpoint = 'https://push.example/shared-browser';
    expect(rememberBrowserPushOwnership(endpoint, accountA)).toBe(true);
    expect(browserPushBelongsTo(endpoint, accountA)).toBe(true);
    expect(browserPushBelongsTo(endpoint, accountB)).toBe(false);
  });
  it('a delayed account A cleanup cannot unsubscribe or clear account B ownership', async () => {
    const accountA = jwt(Math.floor(Date.now() / 1000) + 900, { sub: '41' });
    const accountB = jwt(Math.floor(Date.now() / 1000) + 900, { sub: '52' });
    storeSession(accountA, 'refresh-a', 'needy', 4);
    rememberBrowserPushOwnership('https://push.example/account-a', accountA);
    let releaseRegistration;
    const registrationPending = new Promise(resolve => { releaseRegistration = resolve; });
    const postMessage = vi.fn();
    const unsubscribeB = vi.fn().mockResolvedValue(true);
    vi.stubGlobal('navigator', {
      serviceWorker: {
        controller: { postMessage },
        getRegistration: vi.fn().mockReturnValue(registrationPending),
      },
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, { ok: true })));
    const logoutA = revokeAndClearSession();
    storeSession(accountB, 'refresh-b', 'needy', 5);
    rememberBrowserPushOwnership('https://push.example/account-b', accountB);
    await setBrowserPushDisplayEnabled(true);
    releaseRegistration({
      pushManager: {
        getSubscription: vi.fn().mockResolvedValue({
          endpoint: 'https://push.example/account-b', unsubscribe: unsubscribeB,
        }),
      },
    });
    await logoutA;
    expect(unsubscribeB).not.toHaveBeenCalled();
    expect(window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY))
      .toBe('https://push.example/account-b');
    expect(window.localStorage.getItem(PUSH_OWNER_STORAGE_KEY)).toBe('52');
    expect(postMessage.mock.calls.at(-1)[0]).toEqual(expect.objectContaining({
      type: 'SET_PUSH_ENABLED', enabled: true,
    }));
  });
  it('versions display state at invocation so a delayed stale enable cannot beat logout', async () => {
    let releaseRegistration;
    const registrationPending = new Promise(resolve => { releaseRegistration = resolve; });
    const postMessage = vi.fn();
    const serviceWorker = {
      controller: null,
      getRegistration: vi.fn().mockReturnValue(registrationPending),
    };
    vi.stubGlobal('navigator', { serviceWorker });
    const staleEnable = setBrowserPushDisplayEnabled(true);
    serviceWorker.controller = { postMessage };
    await setBrowserPushDisplayEnabled(false);
    releaseRegistration({ active: { postMessage } });
    await staleEnable;
    const [logoutDisable, delayedEnable] = postMessage.mock.calls.map(([message]) => message);
    expect(logoutDisable.enabled).toBe(false);
    expect(delayedEnable.enabled).toBe(true);
    expect(logoutDisable.revision).toBeGreaterThan(delayedEnable.revision);
  });
  it('long-running requests and socket reconnects read the newest access token', async () => {
    const firstAccess = jwt(Math.floor(Date.now() / 1000) + 600);
    const rotatedAccess = jwt(Math.floor(Date.now() / 1000) + 900);
    storeSession(firstAccess, 'refresh-1', 'volunteer', 6);
    const locations = [];
    const fetchMock = vi.fn((url, init) => {
      locations.push(String(url));
      return Promise.resolve(response(200, {
        authorization: init.headers.get('Authorization'),
      }));
    });
    vi.stubGlobal('fetch', fetchMock);
    await authFetch('/volunteers/6/location', { method: 'PATCH' });
    storeSession(rotatedAccess, 'refresh-2', 'volunteer', 6);
    const poll = await authFetch('/volunteers/6/location');
    const reconnectToken = await getFreshAccessToken();
    expect((await poll.json()).authorization).toBe(`Bearer ${rotatedAccess}`);
    expect(reconnectToken).toBe(rotatedAccess);
    expect(locations.join(' ')).not.toContain('refresh-1');
    expect(locations.join(' ')).not.toContain('refresh-2');
  });
});
