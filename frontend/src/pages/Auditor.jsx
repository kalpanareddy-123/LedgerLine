import React, { useState, useEffect } from 'react';
import { apiRequest } from '../api';

export default function Auditor() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [page, setPage] = useState(0);
  const [pageSize] = useState(15);
  const [actionFilter, setActionFilter] = useState('');
  const [outcomeFilter, setOutcomeFilter] = useState('');
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  useEffect(() => {
    loadAuditLogs(page);
  }, [page, outcomeFilter]);

  async function loadAuditLogs(pageNumber = 0) {
    setLoading(true);
    setError(null);
    try {
      let url = `/api/audit?page=${pageNumber}&size=${pageSize}`;
      if (actionFilter) {
        url += `&action=${encodeURIComponent(actionFilter)}`;
      }
      if (outcomeFilter) {
        url += `&outcome=${encodeURIComponent(outcomeFilter)}`;
      }

      const data = await apiRequest(url);
      setLogs(data.content || []);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
    } catch (err) {
      setError(err.message || 'Failed to load audit logs.');
    } finally {
      setLoading(false);
    }
  }

  function handleFilterSubmit(e) {
    e.preventDefault();
    setPage(0);
    loadAuditLogs(0);
  }

  function resetFilters() {
    setActionFilter('');
    setOutcomeFilter('');
    setPage(0);
    setTimeout(() => loadAuditLogs(0), 0);
  }

  return (
    <div className="page-container">
      <div className="page-title-bar">
        <div>
          <h2>System Audit Trail</h2>
          <p className="subtext">
            Append-only, immutable audit record of all transactions, authentication, and security events
          </p>
        </div>
      </div>

      {/* Filter Bar */}
      <form onSubmit={handleFilterSubmit} className="statement-filters-card">
        <div className="filter-inputs">
          <div className="filter-group">
            <label>Filter by Action</label>
            <input
              type="text"
              placeholder="e.g. DEPOSIT, TRANSFER, LOGIN"
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
            />
          </div>
          <div className="filter-group">
            <label>Filter by Outcome</label>
            <select
              value={outcomeFilter}
              onChange={(e) => setOutcomeFilter(e.target.value)}
            >
              <option value="">All Outcomes</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="FAILURE">FAILURE</option>
              <option value="DENIED">DENIED</option>
            </select>
          </div>
        </div>
        <div className="filter-buttons">
          <button type="submit" className="btn-secondary">
            Filter Logs
          </button>
          {(actionFilter || outcomeFilter) && (
            <button type="button" className="btn-text" onClick={resetFilters}>
              Reset
            </button>
          )}
        </div>
      </form>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Table Card */}
      <div className="card table-card">
        <div className="card-header">
          <h3>Recorded Events ({totalElements})</h3>
          <span className="subtext">Strictly append-only DB triggers enforce immutability</span>
        </div>

        {loading ? (
          <div className="table-loader">
            <div className="spinner"></div>
            <p>Loading audit trail...</p>
          </div>
        ) : logs.length === 0 ? (
          <div className="empty-state">
            <p>No audit events match your criteria.</p>
          </div>
        ) : (
          <div className="table-responsive">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Timestamp</th>
                  <th>Action</th>
                  <th>Outcome</th>
                  <th>Actor ID</th>
                  <th>Target Type & ID</th>
                  <th>IP Address</th>
                  <th>Request ID</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => (
                  <tr key={log.id}>
                    <td className="mono subtext text-small">
                      {new Date(log.createdAt).toLocaleString('en-IN', {
                        month: 'short',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })}
                    </td>
                    <td>
                      <span className="font-semibold mono">{log.action}</span>
                    </td>
                    <td>
                      <span
                        className={`status-pill status-${
                          log.outcome === 'SUCCESS'
                            ? 'ok'
                            : log.outcome === 'DENIED'
                            ? 'failed'
                            : 'warn'
                        }`}
                      >
                        {log.outcome}
                      </span>
                    </td>
                    <td className="mono subtext text-small">
                      {log.actorId ? `${log.actorId.substring(0, 8)}...` : 'Anonymous'}
                    </td>
                    <td className="subtext text-small mono">
                      {log.targetType}: {log.targetId ? `${log.targetId.substring(0, 8)}...` : '-'}
                    </td>
                    <td className="mono text-small subtext">{log.ip}</td>
                    <td className="mono text-small subtext" title={log.requestId}>
                      {log.requestId ? `${log.requestId.substring(0, 8)}...` : '-'}
                    </td>
                    <td className="text-small mono details-cell">
                      <span className="details-snippet" title={log.details}>
                        {log.details || '{}'}
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
