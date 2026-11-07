import React, { useState, useEffect } from 'react';
import { apiRequest, formatRupees } from '../api';

export default function Admin() {
  const [reconciliation, setReconciliation] = useState(null);
  const [reconciling, setReconciling] = useState(false);
  const [accounts, setAccounts] = useState([]);
  const [loadingAccounts, setLoadingAccounts] = useState(false);

  // User form
  const [newUsername, setNewUsername] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newRole, setNewRole] = useState('ACCOUNT_HOLDER');
  const [userMsg, setUserMsg] = useState(null);
  const [userErr, setUserErr] = useState(null);
  const [creatingUser, setCreatingUser] = useState(false);

  // Account form
  const [ownerId, setOwnerId] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [accountMsg, setAccountMsg] = useState(null);
  const [accountErr, setAccountErr] = useState(null);
  const [creatingAccount, setCreatingAccount] = useState(false);

  useEffect(() => {
    runReconciliation();
    loadAccounts();
  }, []);

  async function runReconciliation() {
    setReconciling(true);
    try {
      const data = await apiRequest('/api/admin/reconciliation');
      setReconciliation(data);
    } catch (err) {
      console.error('Reconciliation check failed:', err);
    } finally {
      setReconciling(false);
    }
  }

  async function loadAccounts() {
    setLoadingAccounts(true);
    try {
      const data = await apiRequest('/api/accounts');
      setAccounts(data);
    } catch (err) {
      console.error('Failed to load accounts for admin:', err);
    } finally {
      setLoadingAccounts(false);
    }
  }

  async function handleCreateUser(e) {
    e.preventDefault();
    setUserMsg(null);
    setUserErr(null);
    setCreatingUser(true);
    try {
      const user = await apiRequest('/api/admin/users', {
        method: 'POST',
        body: JSON.stringify({
          username: newUsername.trim(),
          email: newEmail.trim(),
          password: newPassword,
          role: newRole,
        }),
      });
      setUserMsg(`User created successfully: ${user.username} (${user.id})`);
      setOwnerId(user.id);
      setNewUsername('');
      setNewEmail('');
      setNewPassword('');
    } catch (err) {
      setUserErr(err.message || 'Failed to create user');
    } finally {
      setCreatingUser(false);
    }
  }

  async function handleCreateAccount(e) {
    e.preventDefault();
    setAccountMsg(null);
    setAccountErr(null);
    setCreatingAccount(true);
    try {
      const acc = await apiRequest('/api/admin/accounts', {
        method: 'POST',
        body: JSON.stringify({
          ownerId: ownerId.trim(),
          accountNumber: accountNumber.trim(),
        }),
      });
      setAccountMsg(`Account ${acc.accountNumber} created for owner!`);
      setAccountNumber('');
      loadAccounts();
      runReconciliation();
    } catch (err) {
      setAccountErr(err.message || 'Failed to create account');
    } finally {
      setCreatingAccount(false);
    }
  }

  async function handleStatusChange(accId, newStatus) {
    try {
      await apiRequest(`/api/admin/accounts/${accId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status: newStatus }),
      });
      loadAccounts();
    } catch (err) {
      alert(`Status update failed: ${err.message}`);
    }
  }

  return (
    <div className="page-container">
      <div className="page-title-bar">
        <div>
          <h2>System Administration</h2>
          <p className="subtext">
            Double-entry book reconciliation, user provisioning, and account lifecycle control
          </p>
        </div>
      </div>

      {/* Reconciliation Card */}
      <div className="card reconciliation-card">
        <div className="reconciliation-header">
          <div>
            <h3>Double-Entry Book Reconciliation</h3>
            <p className="subtext">
              Verifies global conservation: Σ(all ledger entries) = 0 and Account.balance = Σ(account entries)
            </p>
          </div>
          <button
            className="btn-primary"
            onClick={runReconciliation}
            disabled={reconciling}
          >
            {reconciling ? 'Auditing Ledger...' : '⚡ Run Instant Reconcile'}
          </button>
        </div>

        {reconciliation && (
          <div className="reconciliation-body">
            <div className="reconcile-stats">
              <div className="reconcile-stat">
                <span className="stat-label">System Status</span>
                <span
                  className={`status-pill status-${
                    reconciliation.status === 'OK' ? 'ok' : 'failed'
                  }`}
                >
                  {reconciliation.status === 'OK' ? '✔ BALANCED (OK)' : '✖ MISMATCH DETECTED'}
                </span>
              </div>
              <div className="reconcile-stat">
                <span className="stat-label">Total Net Ledger Sum</span>
                <span className="stat-value mono">
                  {formatRupees(reconciliation.totalLedgerSum)}
                  {reconciliation.totalLedgerSum === 0 ? ' (0 paise)' : ' (IMBALANCE)'}
                </span>
              </div>
              <div className="reconcile-stat">
                <span className="stat-label">Accounts Checked</span>
                <span className="stat-value">{reconciliation.totalAccountsChecked} accounts</span>
              </div>
              <div className="reconcile-stat">
                <span className="stat-label">Last Audited</span>
                <span className="stat-value subtext mono">
                  {new Date(reconciliation.timestamp).toLocaleTimeString()}
                </span>
              </div>
            </div>

            {reconciliation.mismatches?.length > 0 && (
              <div className="alert alert-error" style={{ marginTop: '1rem' }}>
                <h4>Account Balance Mismatches Found ({reconciliation.mismatches.length}):</h4>
                <ul>
                  {reconciliation.mismatches.map((m) => (
                    <li key={m.accountId}>
                      Account: {m.accountNumber} | Stored: {m.storedBalance} | Calculated: {m.calculatedBalance} | Diff: {m.difference}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Forms Grid */}
      <div className="admin-forms-grid">
        {/* User Creation Form */}
        <div className="card">
          <div className="card-header">
            <h3>Create User</h3>
          </div>
          {userMsg && <div className="alert alert-success">{userMsg}</div>}
          {userErr && <div className="alert alert-error">{userErr}</div>}
          <form onSubmit={handleCreateUser} className="admin-form">
            <div className="form-group">
              <label>Username</label>
              <input
                type="text"
                placeholder="e.g. john_doe"
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
                required
                disabled={creatingUser}
              />
            </div>
            <div className="form-group">
              <label>Email</label>
              <input
                type="email"
                placeholder="john@example.com"
                value={newEmail}
                onChange={(e) => setNewEmail(e.target.value)}
                required
                disabled={creatingUser}
              />
            </div>
            <div className="form-group">
              <label>Password</label>
              <input
                type="password"
                placeholder="Minimum 8 characters"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                required
                disabled={creatingUser}
              />
            </div>
            <div className="form-group">
              <label>Role</label>
              <select
                value={newRole}
                onChange={(e) => setNewRole(e.target.value)}
                disabled={creatingUser}
              >
                <option value="ACCOUNT_HOLDER">ACCOUNT_HOLDER (Customer)</option>
                <option value="AUDITOR">AUDITOR (Read-only auditor)</option>
                <option value="ADMIN">ADMIN (System Administrator)</option>
              </select>
            </div>
            <button type="submit" className="btn-primary" disabled={creatingUser}>
              {creatingUser ? 'Creating...' : 'Create User'}
            </button>
          </form>
        </div>

        {/* Account Creation Form */}
        <div className="card">
          <div className="card-header">
            <h3>Create Bank Account</h3>
          </div>
          {accountMsg && <div className="alert alert-success">{accountMsg}</div>}
          {accountErr && <div className="alert alert-error">{accountErr}</div>}
          <form onSubmit={handleCreateAccount} className="admin-form">
            <div className="form-group">
              <label>Owner User ID (UUID)</label>
              <input
                type="text"
                placeholder="Paste owner's User UUID"
                value={ownerId}
                onChange={(e) => setOwnerId(e.target.value)}
                required
                disabled={creatingAccount}
              />
            </div>
            <div className="form-group">
              <label>Account Number</label>
              <input
                type="text"
                placeholder="e.g. ACC-100234"
                value={accountNumber}
                onChange={(e) => setAccountNumber(e.target.value)}
                required
                disabled={creatingAccount}
              />
            </div>
            <button type="submit" className="btn-primary" disabled={creatingAccount}>
              {creatingAccount ? 'Opening Account...' : 'Open New Account'}
            </button>
          </form>
        </div>
      </div>

      {/* Account Lifecycle & Freeze / Unfreeze Table */}
      <div className="card table-card" style={{ marginTop: '2rem' }}>
        <div className="card-header">
          <h3>Account Freeze & Lifecycle Control</h3>
          <span className="subtext">Freeze or unfreeze accounts to block/allow money movements</span>
        </div>

        <div className="table-responsive">
          <table className="data-table">
            <thead>
              <tr>
                <th>Account Number</th>
                <th>Owner ID</th>
                <th>Balance</th>
                <th>Current Status</th>
                <th className="text-right">Lifecycle Actions</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((acc) => (
                <tr key={acc.id}>
                  <td>
                    <span className="account-num mono">{acc.accountNumber}</span>
                    {acc.system && <span className="badge badge-system" style={{ marginLeft: '6px' }}>SYSTEM</span>}
                  </td>
                  <td className="mono subtext text-small">
                    {acc.ownerId ? `${acc.ownerId.substring(0, 8)}...` : 'System'}
                  </td>
                  <td className="mono font-semibold">{formatRupees(acc.balance)}</td>
                  <td>
                    <span className={`status-pill status-${acc.status?.toLowerCase()}`}>
                      {acc.status}
                    </span>
                  </td>
                  <td className="text-right">
                    {!acc.system && (
                      <div className="table-actions">
                        {acc.status === 'ACTIVE' && (
                          <button
                            className="btn-action btn-danger"
                            onClick={() => handleStatusChange(acc.id, 'FROZEN')}
                          >
                            ❄ Freeze
                          </button>
                        )}
                        {acc.status === 'FROZEN' && (
                          <button
                            className="btn-action btn-success"
                            onClick={() => handleStatusChange(acc.id, 'ACTIVE')}
                          >
                            Unfreeze
                          </button>
                        )}
                        {acc.status !== 'CLOSED' && (
                          <button
                            className="btn-action"
                            onClick={() => handleStatusChange(acc.id, 'CLOSED')}
                          >
                            Close
                          </button>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
