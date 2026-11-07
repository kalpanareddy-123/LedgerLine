import React, { createContext, useContext, useState, useEffect } from 'react';
import { apiRequest, getAuthToken, setAuthToken, getStoredUser, setStoredUser, clearAuth } from '../api';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [token, setToken] = useState(getAuthToken());
  const [user, setUser] = useState(getStoredUser());
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function verifyUser() {
      const existingToken = getAuthToken();
      if (existingToken) {
        try {
          const profile = await apiRequest('/api/auth/me');
          setUser(profile);
          setStoredUser(profile);
        } catch (err) {
          console.warn('Auto-login verification failed:', err.message);
          logout();
        }
      }
      setLoading(false);
    }
    verifyUser();
  }, []);

  async function login(username, password) {
    const res = await apiRequest('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });

    setToken(res.token);
    setAuthToken(res.token);

    const profile = {
      id: res.id,
      username: res.username,
      email: res.email,
      role: res.role,
    };

    setUser(profile);
    setStoredUser(profile);
    return profile;
  }

  function logout() {
    setToken(null);
    setUser(null);
    clearAuth();
  }

  const value = {
    user,
    token,
    isAuthenticated: !!token && !!user,
    role: user?.role || null,
    isAdmin: user?.role === 'ADMIN',
    isAuditor: user?.role === 'AUDITOR',
    isAccountHolder: user?.role === 'ACCOUNT_HOLDER',
    login,
    logout,
  };

  if (loading) {
    return (
      <div className="loading-screen">
        <div className="spinner"></div>
        <p>Initializing LedgerLine...</p>
      </div>
    );
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
