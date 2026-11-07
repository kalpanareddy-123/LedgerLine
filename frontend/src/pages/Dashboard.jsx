import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { apiRequest, formatRupees } from '../api';
import { useAuth } from '../context/AuthContext';
import MoneyModal from '../components/MoneyModal';

export default function Dashboard() {
  const { user, isAdmin, isAuditor } = useAuth();
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Modal state
  const [modalType, setModalType] = useState(null); // 'DEPOSIT' | 'WITHDRAW' | 'TRANSFER' | null
  const [activeAccount, setActiveAccount] = useState(null);

  useEffect(() => {
    loadAccounts();
  }, []);

  async function loadAccounts() {
    setLoading(true);
    setError(null);
    try {
      const data = await apiRequest('/api/accounts');
      setAccounts(data);
    } catch (err) {
      setError(err.message || 'Failed to load accounts.');
    } finally {
      setLoading(false);
    }
  }

  function openModal(type, account = null) {
    setModalType(type);
    setActiveAccount(account);
  }

  function closeModal() {
    setModalType(null);
    setActiveAccount(null);
  }

  // Calculate total balance across visible user accounts (excluding system clearing account)
  const totalBalancePaise = accounts
    .filter((a) => !a.system)
    .reduce((sum, a) => sum + (a.balance || 0), 0);

  return (
    <div className="page-container">
      {/* Top Header Summary */}
      <div className="dashboard-header">
        <div>
          <h2>
            {isAdmin || isAuditor ? 'System Accounts Overview' : `Welcome, ${user.username}`}
          </h2>
          <p className="subtext">
            {isAdmin || isAuditor
              ? 'Monitoring all accounts across the LedgerLine network'
              : 'Manage your active accounts and money movements'}
          </p>
        </div>

        {!isAuditor && (
          <div className="quick-actions">
            <button
              className="btn-primary"
              onClick={() => openModal('DEPOSIT', accounts[0])}
              disabled={accounts.length === 0}
            >
              + Deposit
            </button>
            <button
              className="btn-secondary"
              onClick={() => openModal('WITHDRAW', accounts[0])}
              disabled={accounts.length === 0}
            >
              - Withdraw
            </button>
            <button
              className="btn-secondary"
              onClick={() => openModal('TRANSFER', accounts[0])}
              disabled={accounts.length === 0}
            >
              ⇄ Transfer
            </button>
          </div>
        )}
      </div>

      {/* Summary KPI Cards */}
      <div className="metrics-grid">
        <div className="metric-card">
          <span className="metric-label">Total Liquid Balance</span>
          <span className="metric-value">{formatRupees(totalBalancePaise)}</span>
          <span className="metric-hint">Fixed currency: INR</span>
        </div>
        <div className="metric-card">
          <span className="metric-label">Active Accounts</span>
          <span className="metric-value">{accounts.filter((a) => a.status === 'ACTIVE').length}</span>
          <span className="metric-hint">Total: {accounts.length}</span>
        </div>
        <div className="metric-card">
          <span className="metric-label">Ledger Model</span>
          <span className="metric-value text-accent">Double-Entry</span>
          <span className="metric-hint">Paise minor units (BIGINT)</span>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Accounts List */}
      <div className="card table-card">
        <div className="card-header">
          <h3>Accounts</h3>
          <button className="btn-text" onClick={loadAccounts} disabled={loading}>
            {loading ? 'Refreshing...' : '↻ Refresh'}
          </button>
        </div>

        {loading ? (
          <div className="table-loader">
            <div className="spinner"></div>
            <p>Loading accounts...</p>
          </div>
        ) : accounts.length === 0 ? (
          <div className="empty-state">
            <p>No accounts found.</p>
            {isAdmin && (
              <Link to="/admin" className="btn-primary">
                Create First Account
              </Link>
            )}
          </div>
        ) : (
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Account Number</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th className="text-right">Balance</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((acc) => (
                  <tr key={acc.id}>
                    <td>
                      <div className="account-cell">
                        <span className="account-num mono">{acc.accountNumber}</span>
                        <span className="account-id subtext mono">{acc.id}</span>
                      </div>
                    </td>
                    <td>
                      {acc.system ? (
                        <span className="badge badge-system">SYSTEM CLEARING</span>
                      ) : (
                        <span className="badge badge-user">HOLDER ACCOUNT</span>
                      )}
                    </td>
                    <td>
                      <span className={`status-pill status-${acc.status?.toLowerCase()}`}>
                        {acc.status}
                      </span>
                    </td>
                    <td className="text-right">
                      <span className="balance-amount mono font-semibold">
                        {formatRupees(acc.balance)}
                      </span>
                      <div className="subtext mono">
                        {acc.balance?.toLocaleString()} paise
                      </div>
                    </td>
                    <td className="text-right">
                      <div className="table-actions">
                        <Link
                          to={`/accounts/${acc.id}/statement`}
                          className="btn-action"
                          title="View statement & ledger history"
                        >
                          Statement
                        </Link>
                        {!isAuditor && !acc.system && acc.status === 'ACTIVE' && (
                          <>
                            <button
                              className="btn-action"
                              onClick={() => openModal('DEPOSIT', acc)}
                              title="Deposit funds"
                            >
                              Deposit
                            </button>
                            <button
                              className="btn-action"
                              onClick={() => openModal('WITHDRAW', acc)}
                              title="Withdraw funds"
                            >
                              Withdraw
                            </button>
                            <button
                              className="btn-action"
                              onClick={() => openModal('TRANSFER', acc)}
                              title="Transfer to another account"
                            >
                              Transfer
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Money Action Modal */}
      {modalType && (
        <MoneyModal
          type={modalType}
          account={activeAccount}
          accounts={accounts.filter((a) => !a.system && a.status === 'ACTIVE')}
          onClose={closeModal}
          onSuccess={loadAccounts}
        />
      )}
    </div>
  );
}
