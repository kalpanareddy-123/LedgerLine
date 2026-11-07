import React, { useState, useEffect } from 'react';
import { useParams, Link } from 'react-router-dom';
import { apiRequest, formatRupees, getAuthToken } from '../api';

export default function Statement() {
  const { id } = useParams();
  const [statement, setStatement] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    loadStatement(page);
  }, [id, page]);

  async function loadStatement(pageNumber = 0) {
    setLoading(true);
    setError(null);
    try {
      let url = `/api/accounts/${id}/statement?page=${pageNumber}&size=${pageSize}`;
      if (fromDate) {
        url += `&from=${new Date(fromDate).toISOString()}`;
      }
      if (toDate) {
        url += `&to=${new Date(toDate).toISOString()}`;
      }
      const data = await apiRequest(url);
      setStatement(data);
    } catch (err) {
      setError(err.message || 'Failed to load statement.');
    } finally {
      setLoading(false);
    }
  }

  function handleFilterSubmit(e) {
    e.preventDefault();
    setPage(0);
    loadStatement(0);
  }

  function resetFilters() {
    setFromDate('');
    setToDate('');
    setPage(0);
    setTimeout(() => loadStatement(0), 0);
  }

  async function handleExportCsv() {
    setExporting(true);
    try {
      let url = `/api/accounts/${id}/statement/export`;
      if (fromDate) {
        url += `?from=${new Date(fromDate).toISOString()}`;
      }
      if (toDate) {
        url += `${fromDate ? '&' : '?'}to=${new Date(toDate).toISOString()}`;
      }

      const csvContent = await apiRequest(url, {
        headers: {
          Accept: 'text/csv',
        },
      });

      // Trigger browser download
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
      const downloadUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.setAttribute('download', `statement_${statement?.accountNumber || id}.csv`);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (err) {
      alert('CSV Export failed: ' + err.message);
    } finally {
      setExporting(false);
    }
  }

  const entries = statement?.entries?.content || [];
  const totalPages = statement?.entries?.totalPages || 0;
  const totalElements = statement?.entries?.totalElements || 0;

  return (
    <div className="page-container">
      <div className="statement-header">
        <div>
          <Link to="/" className="btn-back">← Back to Accounts</Link>
          <h2>Account Statement</h2>
          <p className="subtext">
            Official immutable double-entry ledger statement
          </p>
        </div>

        <div className="statement-actions">
          <button
            className="btn-secondary"
            onClick={handleExportCsv}
            disabled={exporting || loading}
          >
            {exporting ? 'Generating CSV...' : '📥 Export CSV'}
          </button>
        </div>
      </div>

      {/* Account Info Banner */}
      {statement && (
        <div className="statement-account-bar">
          <div className="bar-item">
            <span className="bar-label">Account Number</span>
            <span className="bar-val mono">{statement.accountNumber}</span>
          </div>
          <div className="bar-item">
            <span className="bar-label">Currency</span>
            <span className="bar-val mono">{statement.currency}</span>
          </div>
          <div className="bar-item">
            <span className="bar-label">Current Book Balance</span>
            <span className="bar-val font-semibold text-accent mono">
              {formatRupees(statement.currentBalance)}
            </span>
            <small className="subtext mono">
              ({statement.currentBalance?.toLocaleString()} paise)
            </small>
          </div>
        </div>
      )}

      {/* Filter Toolbar */}
      <form onSubmit={handleFilterSubmit} className="statement-filters-card">
        <div className="filter-inputs">
          <div className="filter-group">
            <label>From Date</label>
            <input
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
            />
          </div>
          <div className="filter-group">
            <label>To Date</label>
            <input
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
            />
          </div>
        </div>
        <div className="filter-buttons">
          <button type="submit" className="btn-secondary">
            Apply Date Filter
          </button>
          {(fromDate || toDate) && (
            <button type="button" className="btn-text" onClick={resetFilters}>
              Reset
            </button>
          )}
        </div>
      </form>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Entries Table */}
      <div className="card table-card">
        <div className="card-header">
          <h3>Ledger Entries ({totalElements})</h3>
          <span className="subtext">Append-only audit trail</span>
        </div>

        {loading ? (
          <div className="table-loader">
            <div className="spinner"></div>
            <p>Loading entries...</p>
          </div>
        ) : entries.length === 0 ? (
          <div className="empty-state">
            <p>No ledger entries found for this period.</p>
          </div>
        ) : (
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Timestamp</th>
                  <th>Direction</th>
                  <th className="text-right">Amount</th>
                  <th className="text-right">Running Balance After</th>
                  <th>Transaction ID</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id}>
                    <td className="mono subtext">
                      {new Date(entry.createdAt).toLocaleString('en-IN', {
                        year: 'numeric',
                        month: 'short',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}
                    </td>
                    <td>
                      <span
                        className={`direction-pill direction-${entry.direction?.toLowerCase()}`}
                      >
                        {entry.direction === 'CREDIT' ? '+ CREDIT' : '- DEBIT'}
                      </span>
                    </td>
                    <td className="text-right">
                      <span
                        className={`mono font-semibold ${
                          entry.direction === 'CREDIT' ? 'text-green' : 'text-red'
                        }`}
                      >
                        {entry.direction === 'CREDIT' ? '+' : '-'} {formatRupees(entry.amount)}
                      </span>
                    </td>
                    <td className="text-right">
                      <span className="mono font-semibold">
                        {formatRupees(entry.balanceAfter)}
                      </span>
                    </td>
                    <td>
                      <span className="mono subtext text-small" title={entry.transactionId}>
                        {entry.transactionId?.substring(0, 8)}...
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination controls */}
        {totalPages > 1 && (
          <div className="pagination-bar">
            <button
              className="btn-page"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0 || loading}
            >
              Previous
            </button>
            <span className="page-indicator">
              Page {page + 1} of {totalPages}
            </span>
            <button
              className="btn-page"
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1 || loading}
            >
              Next
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
