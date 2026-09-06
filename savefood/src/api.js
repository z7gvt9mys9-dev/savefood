export const API_URL = import.meta.env.VITE_API_URL || '';
export const SESSION_STORAGE_KEY = 'savefood_auth_session';
export const PUSH_ENDPOINT_STORAGE_KEY = 'savefood_web_push_endpoint';
export const PUSH_OWNER_STORAGE_KEY = 'savefood_web_push_owner';
const PUSH_STATE_REVISION_STORAGE_KEY = 'savefood_web_push_state_revision';
const sessionListeners = new Set();
let refreshPromise = null;
const readStoredSession = () => {
  if (typeof window === 'undefined') return null;
  try {
    const value = JSON.parse(window.localStorage.getItem(SESSION_STORAGE_KEY));
    if (!value?.accessToken || !value?.refreshToken || !value?.role) return null;
    return {
      accessToken: value.accessToken,
      refreshToken: value.refreshToken,
      role: value.role,
      relatedId: value.relatedId == null ? null : Number(value.relatedId),
    };
  } catch {
    return null;
  }
};
const notifySession = (session) => {
  sessionListeners.forEach(listener => listener(session));
};
export const getSession = () => readStoredSession();
export const storeSession = (accessToken, refreshToken, role, relatedId) => {
  if (!accessToken || !refreshToken || !role) {
    throw new Error('Incomplete authentication response');
  }
  const session = {
    accessToken,
    refreshToken,
    role,
    relatedId: relatedId == null ? null : Number(relatedId),
  };
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  notifySession(session);
  return session;
};
export const clearSession = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
    window.localStorage.removeItem('token');
    window.localStorage.removeItem('role');
    window.localStorage.removeItem('related_id');
  }
  notifySession(null);
};
export const subscribeSession = (listener) => {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
};
export const getAccessToken = () => readStoredSession()?.accessToken ?? null;
const tokenSubject = (token) => {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=');
    const subject = JSON.parse(window.atob(padded)).sub;
    return subject == null ? null : String(subject);
  } catch {
    return null;
  }
};
export const samePushAccount = (firstAccessToken, secondAccessToken) => {
  const first = tokenSubject(firstAccessToken);
  return first != null && first === tokenSubject(secondAccessToken);
};
export const rememberBrowserPushOwnership = (endpoint, accessToken) => {
  const owner = tokenSubject(accessToken);
  if (!endpoint || !owner || typeof window === 'undefined') return false;
  window.localStorage.setItem(PUSH_ENDPOINT_STORAGE_KEY, endpoint);
  window.localStorage.setItem(PUSH_OWNER_STORAGE_KEY, owner);
  return true;
};
export const browserPushBelongsTo = (endpoint, accessToken) => {
  const owner = tokenSubject(accessToken);
  return typeof window !== 'undefined'
    && owner != null
    && !!endpoint
    && window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY) === endpoint
    && window.localStorage.getItem(PUSH_OWNER_STORAGE_KEY) === owner;
};
export const browserPushOwnedBy = (accessToken) => {
  const owner = tokenSubject(accessToken);
  return typeof window !== 'undefined'
    && owner != null
    && !!window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY)
    && window.localStorage.getItem(PUSH_OWNER_STORAGE_KEY) === owner;
};
export const forgetBrowserPushOwnership = () => {
  if (typeof window === 'undefined') return;
  window.localStorage.removeItem(PUSH_ENDPOINT_STORAGE_KEY);
  window.localStorage.removeItem(PUSH_OWNER_STORAGE_KEY);
};
const nextPushStateRevision = () => {
  const now = Date.now() * 1000;
  if (typeof window === 'undefined') return now;
  const previous = Number(window.localStorage.getItem(PUSH_STATE_REVISION_STORAGE_KEY)) || 0;
  const revision = Math.max(now, previous + 1);
  window.localStorage.setItem(PUSH_STATE_REVISION_STORAGE_KEY, String(revision));
  return revision;
};
export const setBrowserPushDisplayEnabled = async (enabled) => {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
  const message = {
    type: 'SET_PUSH_ENABLED',
    enabled,
    revision: nextPushStateRevision(),
  };
  try {
    if (navigator.serviceWorker.controller) {
      navigator.serviceWorker.controller.postMessage(message);
      return;
    }
    const registration = await navigator.serviceWorker.getRegistration();
    registration?.active?.postMessage(message);
  } catch {
  }
};
const tokenExpiresSoon = (token, leewayMs = 60_000) => {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=');
    const payload = JSON.parse(window.atob(padded));
    return typeof payload.exp !== 'number' || payload.exp * 1000 <= Date.now() + leewayMs;
  } catch {
    return true;
  }
};
const performRotation = async (session) => {
  let response;
  try {
    response = await fetch(`${API_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: session.refreshToken }),
    });
  } catch (error) {
    throw error;
  }
  if (!response.ok) {
    const current = readStoredSession();
    if (current && current.refreshToken !== session.refreshToken) {
      return current.accessToken;
    }
    if (response.status === 401 || response.status === 403) clearSession();
    const error = new Error(`Refresh failed (${response.status})`);
    error.refreshResponse = response;
    throw error;
  }
  const data = await response.json();
  if (!data.access_token || !data.refresh_token) {
    clearSession();
    throw new Error('Incomplete refresh response');
  }
  const current = readStoredSession();
  if (!current || current.refreshToken !== session.refreshToken) {
    return current?.accessToken ?? null;
  }
  return storeSession(
    data.access_token,
    data.refresh_token,
    current.role,
    current.relatedId,
  ).accessToken;
};
const rotateSession = async () => {
  if (refreshPromise) return refreshPromise;
  const session = readStoredSession();
  if (!session?.refreshToken) throw new Error('No refresh session');
  const rotateCurrentPair = () => {
    const current = readStoredSession();
    if (!current) return null;
    if (current.refreshToken !== session.refreshToken) return current.accessToken;
    return performRotation(session);
  };
  const rotation = typeof navigator !== 'undefined' && navigator.locks?.request
    ? navigator.locks.request('savefood-token-refresh', rotateCurrentPair)
    : rotateCurrentPair();
  refreshPromise = Promise.resolve(rotation).finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
};
export const getFreshAccessToken = async () => {
  const session = readStoredSession();
  if (!session) return null;
  if (!tokenExpiresSoon(session.accessToken)) return session.accessToken;
  return rotateSession();
};
const requestWithAccessToken = (input, init, accessToken) => {
  const sourceHeaders = init?.headers
    || (typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined);
  const headers = new Headers(sourceHeaders);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  else headers.delete('Authorization');
  return fetch(input, { ...init, headers });
};
export const authFetch = async (input, init = {}) => {
  let accessToken = getAccessToken();
  let refreshAttempted = false;
  if (accessToken && tokenExpiresSoon(accessToken)) {
    refreshAttempted = true;
    try {
      accessToken = await rotateSession();
    } catch (error) {
      if (error.refreshResponse) return error.refreshResponse;
      accessToken = getAccessToken();
    }
  }
  const response = await requestWithAccessToken(input, init, accessToken);
  if (response.status !== 401 || refreshAttempted || !readStoredSession()?.refreshToken) {
    return response;
  }
  const currentAccessToken = getAccessToken();
  if (currentAccessToken && currentAccessToken !== accessToken) {
    return requestWithAccessToken(input, init, currentAccessToken);
  }
  try {
    accessToken = await rotateSession();
  } catch (error) {
    if (error.refreshResponse) return error.refreshResponse;
    return response;
  }
  if (!accessToken) return response;
  return requestWithAccessToken(input, init, accessToken);
};
export const revokeAndClearSession = async () => {
  const session = readStoredSession();
  const rememberedEndpoint = typeof window === 'undefined'
    ? null : window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY);
  const rememberedOwner = typeof window === 'undefined'
    ? null : window.localStorage.getItem(PUSH_OWNER_STORAGE_KEY);
  let logoutPromise = Promise.resolve();
  if (session?.refreshToken) {
    const body = { refresh_token: session.refreshToken };
    if (rememberedEndpoint) body.push_endpoint = rememberedEndpoint;
    try {
      logoutPromise = fetch(`${API_URL}/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        keepalive: true,
      });
    } catch {
    }
  }
  const pushCleanupPromise = (async () => {
    await setBrowserPushDisplayEnabled(false);
    let subscription = null;
    try {
      const registration = typeof navigator !== 'undefined' && 'serviceWorker' in navigator
        ? await navigator.serviceWorker.getRegistration() : null;
      subscription = registration ? await registration.pushManager.getSubscription() : null;
    } catch {
    }
    if (rememberedEndpoint && session?.accessToken) {
      try {
        await fetch(`${API_URL}/push/unsubscribe`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${session.accessToken}`,
          },
          body: JSON.stringify({ endpoint: rememberedEndpoint }),
          keepalive: true,
        });
      } catch {
      }
    }
    const stillOwnsSnapshot = typeof window !== 'undefined'
      && window.localStorage.getItem(PUSH_ENDPOINT_STORAGE_KEY) === rememberedEndpoint
      && window.localStorage.getItem(PUSH_OWNER_STORAGE_KEY) === rememberedOwner;
    if (subscription && rememberedEndpoint && stillOwnsSnapshot
        && subscription.endpoint === rememberedEndpoint) {
      try {
        await subscription.unsubscribe();
      } catch {
      }
    }
    if (stillOwnsSnapshot) forgetBrowserPushOwnership();
  })();
  clearSession();
  await Promise.allSettled([logoutPromise, pushCleanupPromise]);
};
