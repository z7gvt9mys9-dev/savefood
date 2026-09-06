import React, { createContext, useState, useContext, useEffect } from 'react';
import {
  clearSession,
  getSession,
  revokeAndClearSession,
  setBrowserPushDisplayEnabled,
  storeSession,
  subscribeSession,
} from '../api';
const AuthContext = createContext(null);
const clearSessionCaches = () => {
  if (typeof window === 'undefined') return;
  setBrowserPushDisplayEnabled(false);
  if ('caches' in window) {
    window.caches.keys()
      .then(names => Promise.all(names
        .filter(name => name.startsWith('savefood-'))
        .map(name => window.caches.delete(name))))
      .catch(() => {});
  }
  if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
    navigator.serviceWorker.controller?.postMessage({ type: 'CLEAR_SESSION_CACHE' });
  }
};
export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    if (!getSession()) clearSession();
    const applySession = (session) => {
      setUser(session ? {
        token: session.accessToken,
        role: session.role,
        relatedId: session.relatedId,
      } : null);
      if (!session) clearSessionCaches();
    };
    applySession(getSession());
    const unsubscribe = subscribeSession(applySession);
    const onStorage = (event) => {
      if (event.key === 'savefood_auth_session') applySession(getSession());
    };
    window.addEventListener('storage', onStorage);
    setLoading(false);
    return () => {
      unsubscribe();
      window.removeEventListener('storage', onStorage);
    };
  }, []);
  const login = (accessToken, refreshToken, role, relatedId) => {
    storeSession(accessToken, refreshToken, role, relatedId);
  };
  const logout = () => {
    clearSessionCaches();
    return revokeAndClearSession();
  };
  return (
    <AuthContext.Provider value={{ user, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
};
export const useAuth = () => useContext(AuthContext);
