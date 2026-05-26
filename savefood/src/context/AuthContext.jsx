import React, { createContext, useState, useContext, useEffect } from 'react';

const AuthContext = createContext(null);

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
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, login, logout, loading }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
