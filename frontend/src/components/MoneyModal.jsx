import React, { useState, useEffect } from 'react';
import { apiRequest, generateUUID, rupeesToPaise, formatRupees } from '../api';

export default function MoneyModal({ type, account, accounts = [], onClose, onSuccess }) {
  // type can be 'DEPOSIT', 'WITHDRAW', 'TRANSFER'
  const [selectedAccountId, setSelectedAccountId] = useState(account?.id || accounts[0]?.id || '');
  const [destinationAccountId, setDestinationAccountId] = useState('');
  const [amountRupees, setAmountRupees] = useState('');
  const [reference, setReference] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState(generateUUID());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [successMsg, setSuccessMsg] = useState(null);

  // Selected source account object
  const currentAcc = accounts.find((a) => a.id === selectedAccountId) || account;

  useEffect(() => {
    // Generate fresh idempotency key when opening modal or changing transaction type
    setIdempotencyKey(generateUUID());
    setError(null);
    setSuccessMsg(null);
  }, [type]);

  /**
   * Whenever amount is edited, generate a NEW idempotency key.
   * On submission failure or network error, the key is KEPT so retry is safe and idempotent.
   */
  function handleAmountChange(e) {
    const val = e.target.value;
    setAmountRupees(val);
    setError(null);
    // Requirement: new key after editing amount
    setIdempotencyKey(generateUUID());
  }

  async function handleSubmit(e) {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);

    let paise = 0;
    try {
      paise = rupeesToPaise(amountRupees);
    } catch (valErr) {
      setError(valErr.message);
      return;
    }

    if (!paise || paise <= 0) {
      setError('Please enter a valid amount greater than ₹0.00');
      return;
    }

    if (type === 'WITHDRAW' && currentAcc && currentAcc.balance < paise) {
      setError(`Insufficient funds: Account balance is ${formatRupees(currentAcc.balance)}, cannot withdraw ${formatRupees(paise)}.`);
      return;
    }

    if (type === 'TRANSFER') {
      if (!destinationAccountId) {
        setError('Please select or specify a destination account.');
        return;
      }
      if (selectedAccountId === destinationAccountId) {
        setError('Self-transfer is prohibited: Source and destination accounts must differ.');
        return;
      }
    }

    setSubmitting(true);

    try {
      let endpoint = '';
      let payload = {};

      if (type === 'DEPOSIT') {
        endpoint = `/api/accounts/${selectedAccountId}/deposit`;
        payload = { amount: paise, reference: reference.trim() || 'Manual Deposit' };
      } else if (type === 'WITHDRAW') {
        endpoint = `/api/accounts/${selectedAccountId}/withdraw`;
        payload = { amount: paise, reference: reference.trim() || 'Manual Withdrawal' };
      } else if (type === 'TRANSFER') {
        endpoint = '/api/transfers';
        payload = {
          sourceAccountId: selectedAccountId,
          destinationAccountId: destinationAccountId.trim(),
          amount: paise,
          reference: reference.trim() || 'Manual Transfer',
        };
      }

      const result = await apiRequest(endpoint, {
        method: 'POST',
        headers: {
          'Idempotency-Key': idempotencyKey,
        },
        body: JSON.stringify(payload),
      });

      setSuccessMsg(`Transaction completed! Ref: ${result.reference || result.id}`);
      // Requirement: new key after success
      setIdempotencyKey(generateUUID());

      // Refresh parent after short delay
      setTimeout(() => {
        onSuccess();
        onClose();
      }, 1000);
    } catch (err) {
      // NOTE: On failure / network error, we KEEP the same idempotencyKey so retrying the submission is safe & idempotent!
      setError(err.message || 'Operation failed. You can safely retry.');
    } finally {
      setSubmitting(false);
    }
  }

  const titleMap = {
    DEPOSIT: 'Deposit Funds',
    WITHDRAW: 'Withdraw Funds',
    TRANSFER: 'Transfer Funds',
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>{titleMap[type]}</h3>
          <button className="btn-close" onClick={onClose}>&times;</button>
        </div>

        <form onSubmit={handleSubmit} className="modal-form">
          {error && <div className="alert alert-error">{error}</div>}
          {successMsg && <div className="alert alert-success">{successMsg}</div>}

          {/* Account Selection */}
          <div className="form-group">
            <label>
              {type === 'TRANSFER' ? 'From Account' : 'Account'}
            </label>
            {accounts.length > 1 && !account ? (
              <select
                value={selectedAccountId}
                onChange={(e) => setSelectedAccountId(e.target.value)}
                disabled={submitting}
              >
                {accounts.map((acc) => (
                  <option key={acc.id} value={acc.id}>
                    {acc.accountNumber} ({formatRupees(acc.balance)})
                  </option>
                ))}
              </select>
            ) : (
              <div className="account-preview-chip">
                <span className="mono">{currentAcc?.accountNumber || selectedAccountId}</span>
                <span className="balance-badge">{formatRupees(currentAcc?.balance || 0)}</span>
              </div>
            )}
          </div>

          {/* Destination for Transfer */}
          {type === 'TRANSFER' && (
            <div className="form-group">
              <label>Destination Account ID / Number</label>
              <input
                type="text"
                placeholder="Paste destination Account UUID or Account Number"
                value={destinationAccountId}
                onChange={(e) => setDestinationAccountId(e.target.value)}
                disabled={submitting}
                required
              />
              {accounts.length > 1 && (
                <div className="quick-dest-options">
                  <span className="subtext">Or pick another account you own:</span>
                  <div className="dest-chips">
                    {accounts
                      .filter((a) => a.id !== selectedAccountId)
                      .map((a) => (
                        <button
                          key={a.id}
                          type="button"
                          className="chip-btn"
                          onClick={() => setDestinationAccountId(a.id)}
                        >
                          {a.accountNumber}
                        </button>
                      ))}
                  </div>
                </div>
              )}
            </div>
          )}

          {/* Amount input in Rupees */}
          <div className="form-group">
            <label>Amount (INR ₹)</label>
            <div className="input-rupee-wrapper">
              <span className="rupee-symbol">₹</span>
              <input
                type="text"
                inputMode="decimal"
                placeholder="0.00"
                value={amountRupees}
                onChange={handleAmountChange}
                disabled={submitting}
                required
              />
            </div>
            {amountRupees && (
              <span className="paise-hint">
                {(() => {
                  try {
                    const p = rupeesToPaise(amountRupees);
                    return `= ${p.toLocaleString()} paise (minor units stored)`;
                  } catch (err) {
                    return `⚠️ ${err.message}`;
                  }
                })()}
              </span>
            )}
          </div>

          {/* Reference */}
          <div className="form-group">
            <label>Reference / Memo</label>
            <input
              type="text"
              placeholder="e.g. Invoice #1024, Salary, Rent"
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              disabled={submitting}
            />
          </div>

          {/* Idempotency Key Notice */}
          <div className="idempotency-badge-box">
            <div className="idemp-header">
              <span className="idemp-label">🛡 Idempotency-Key Header</span>
              <button
                type="button"
                className="btn-text-small"
                onClick={() => setIdempotencyKey(generateUUID())}
                disabled={submitting}
                title="Generate new UUID key"
              >
                Regenerate
              </button>
            </div>
            <code className="mono idemp-key">{idempotencyKey}</code>
            <p className="idemp-info">
              Guarantees this transaction executes exactly once. On retry after network failure, the identical key is sent.
            </p>
          </div>

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={submitting}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={submitting}>
              {submitting ? 'Executing Transaction...' : `Confirm ${titleMap[type]}`}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
