import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Navbar() {
  const { user, role, isAdmin, isAuditor, logout } = useAuth();
  const location = useLocation();

  if (!user) return null;

  const isActive = (path) => location.pathname === path;

  return (
    <header className="navbar">
      <div className="navbar-container">
        <div className="navbar-brand">
          <Link to="/" className="brand-logo">
            <span className="logo-symbol">⚖</span>
            <span className="logo-text">LedgerLine</span>
          </Link>
          <span className="system-tag">Double-Entry</span>
        </div>

        <nav className="navbar-links">
          <Link
            to="/"
            className={`nav-link ${isActive('/') ? 'active' : ''}`}
          >
            Accounts
          </Link>

          {(isAdmin || isAuditor) && (
            <Link
              to="/audit"
              className={`nav-link ${isActive('/audit') ? 'active' : ''}`}
            >
              Audit Log
            </Link>
          )}

          {isAdmin && (
            <Link
              to="/admin"
              className={`nav-link ${isActive('/admin') ? 'active' : ''}`}
            >
              Admin & Reconciliation
            </Link>
          )}
        </nav>

        <div className="navbar-user">
          <div className="user-info">
            <span className="user-name">{user.username}</span>
            <span className={`role-badge role-${role?.toLowerCase()}`}>
              {role}
            </span>
          </div>
          <button onClick={logout} className="btn-logout" title="Sign out">
            Sign Out
          </button>
        </div>
      </div>
    </header>
  );
}
