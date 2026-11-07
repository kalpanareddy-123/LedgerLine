import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      await login(username.trim(), password);
      navigate('/');
    } catch (err) {
      if (err.status === 429) {
        setError('Too many failed login attempts. Your IP is locked out for 5 minutes.');
      } else {
        setError(err.message || 'Invalid username or password.');
      }
    } finally {
      setLoading(false);
    }
  }

  function fillDemo(user, pass) {
    setUsername(user);
    setPassword(pass);
    setError(null);
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <div className="login-logo">
            <span className="logo-symbol">⚖</span>
            <h1>LedgerLine</h1>
          </div>
          <p className="login-subtitle">
            Secure, double-entry banking and money movement platform
          </p>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        <form onSubmit={handleSubmit} className="login-form">
          <div className="form-group">
            <label>Username</label>
            <input
              type="text"
              placeholder="Enter username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              disabled={loading}
              autoFocus
            />
          </div>

          <div className="form-group">
            <label>Password</label>
            <input
              type="password"
              placeholder="Enter password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              disabled={loading}
            />
          </div>

          <button type="submit" className="btn-primary btn-block" disabled={loading}>
            {loading ? 'Authenticating...' : 'Sign In to LedgerLine'}
          </button>
        </form>

        <div className="demo-accounts-section">
          <span className="demo-title">⚡ Quick Demo Logins (Click to autofill)</span>
          <div className="demo-buttons">
            <button
              type="button"
              className="btn-demo"
              onClick={() => fillDemo('admin', 'AdminPass123!')}
            >
              <strong>Admin</strong>
              <small>Full privileges</small>
            </button>
            <button
              type="button"
              className="btn-demo"
              onClick={() => fillDemo('auditor', 'AuditorPass123!')}
            >
              <strong>Auditor</strong>
              <small>Read-only audits</small>
            </button>
            <button
              type="button"
              className="btn-demo"
              onClick={() => fillDemo('alice', 'AlicePass123!')}
            >
              <strong>Alice</strong>
              <small>Account holder</small>
            </button>
            <button
              type="button"
              className="btn-demo"
              onClick={() => fillDemo('bob', 'BobPass123!')}
            >
              <strong>Bob</strong>
              <small>Account holder</small>
            </button>
          </div>
        </div>

        <div className="security-guarantee-footer">
          <small>
            🔒 All transactions stored in minor units (paise) with balanced double-entry ledger and append-only database triggers.
          </small>
        </div>
      </div>
    </div>
  );
}
