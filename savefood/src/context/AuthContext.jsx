import React, { createContext, useState, useContext, useEffect } from 'react';
import { API_URL } from '../api';

const AuthContext = createContext(null);

const parseJwt = (token) => {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
};

// Authenticated API responses must never survive on a shared device after the
// account signs out. The service worker also receives the message, but clear
// Cache Storage here as well in case it has just been updated or is stopped.
const clearSessionCaches = () => {
  if (typeof window === 'undefined') return;
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
    const token = localStorage.getItem('token');
    const role = localStorage.getItem('role');
    const relatedId = localStorage.getItem('related_id');
    if (token && role) {
      setUser({ token, role, relatedId: relatedId ? Number(relatedId) : null });
    }
    setLoading(false);
  }, []);

  // Refresh token when it has less than 2 hours left
  useEffect(() => {
    if (!user?.token) return;
    const tryRefresh = async () => {
      const payload = parseJwt(user.token);
      if (!payload.exp) return;
      const msLeft = payload.exp * 1000 - Date.now();
      if (msLeft > 2 * 3600 * 1000) return;
      try {
        const res = await fetch(`${API_URL}/auth/refresh`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${user.token}` },
        });
        if (res.ok) {
          const data = await res.json();
          localStorage.setItem('token', data.access_token);
          setUser(prev => ({ ...prev, token: data.access_token }));
        }
      } catch {}
    };
    tryRefresh();
    const interval = setInterval(tryRefresh, 30 * 60 * 1000);
    return () => clearInterval(interval);
  }, [user?.token]);

  const login = (token, role, relatedId) => {
    localStorage.setItem('token', token);
    localStorage.setItem('role', role);
    localStorage.setItem('related_id', relatedId != null ? String(relatedId) : '');
    setUser({ token, role, relatedId: relatedId != null ? Number(relatedId) : null });
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('role');
    localStorage.removeItem('related_id');
    clearSessionCaches();
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
