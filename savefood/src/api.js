export const API_URL = import.meta.env.VITE_API_URL || '';

export const SESSION_STORAGE_KEY = 'savefood_auth_session';

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
  // A single value keeps a rotated access/refresh pair from being observed half-written.
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  notifySession(session);
  return session;
};

export const clearSession = () => {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
    // Old access-only sessions intentionally require one login after deployment.
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
    // Offline/timeouts are not proof that the refresh session is invalid.
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

  // Do not resurrect a session that was logged out while refresh was in flight.
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
      // Try the request once with the existing access token. The session remains
      // available after transient refresh failures and can recover later.
      accessToken = getAccessToken();
    }
  }

  const response = await requestWithAccessToken(input, init, accessToken);
  if (response.status !== 401 || refreshAttempted || !readStoredSession()?.refreshToken) {
    return response;
  }

  // Another concurrent request (or browser tab) may already have rotated while
  // this request was in flight. Retry with that pair instead of rotating again.
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
  const refreshToken = readStoredSession()?.refreshToken;
  clearSession();
  if (!refreshToken) return;
  try {
    await fetch(`${API_URL}/auth/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken }),
      keepalive: true,
    });
  } catch {
    // Local logout remains complete when revocation cannot reach the server.
  }
};
