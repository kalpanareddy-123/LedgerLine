/**
 * LedgerLine API Client
 *
 * Configured with:
 * - Dynamic API Base URL from VITE_API_URL or VITE_API_BASE_URL
 * - Token storage in sessionStorage (see security note below)
 * - Cryptographic UUIDv4 Idempotency-Key header injection
 * - Pure string-parsing rupee-to-paise conversion (zero float math)
 * - Friendly error messages for 400, 401, 403, 404, 422, 429, 500 & Network Failures
 * - Automatic 401 session clearing and login redirect
 */

const API_BASE = (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_URL) ||
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE_URL) || '';

/**
 * TOKEN STORAGE & SECURITY DOCUMENTATION:
 * We store the JWT bearer token in browser `sessionStorage`.
 *
 * HONEST SECURITY ASSESSMENT:
 * `sessionStorage` does NOT protect against Cross-Site Scripting (XSS).
 * Any malicious JavaScript running within this application origin can read
 * `sessionStorage.getItem('ledgerline_token')`.
 *
 * The sole advantage of `sessionStorage` over `localStorage` is lifecycle scoping:
 * the token is automatically purged when the user closes the browser tab or window,
 * preventing credentials from lingering indefinitely on shared or public computers.
 * (In an enterprise production deployment, HTTP-Only, SameSite=Strict cookies
 * with anti-CSRF tokens are recommended to fully defend against token exfiltration).
 */

export function getAuthToken() {
  return sessionStorage.getItem('ledgerline_token');
}

export function setAuthToken(token) {
  if (token) {
    sessionStorage.setItem('ledgerline_token', token);
  } else {
    sessionStorage.removeItem('ledgerline_token');
  }
}

export function getStoredUser() {
  const user = sessionStorage.getItem('ledgerline_user');
  return user ? JSON.parse(user) : null;
}

export function setStoredUser(user) {
  if (user) {
    sessionStorage.setItem('ledgerline_user', JSON.stringify(user));
  } else {
    sessionStorage.removeItem('ledgerline_user');
  }
}

export function clearAuth() {
  sessionStorage.removeItem('ledgerline_token');
  sessionStorage.removeItem('ledgerline_user');
}

/**
 * Generate a cryptographically strong UUIDv4 for Idempotency-Key
 */
export function generateUUID() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Format minor units (paise) to Indian Rupees formatted string
 * Example: 10050 -> ₹100.50, 10000000 -> ₹1,00,000.00
 */
export function formatRupees(paise) {
  if (paise === null || paise === undefined || isNaN(paise)) return '₹0.00';
  const rupees = paise / 100;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(rupees);
}

/**
 * Convert user-entered Rupees to paise (BIGINT integer) via pure string parsing.
 * NO FLOATING-POINT MATH IS USED.
 *
 * Demonstrates:
 * - "100"   -> 10000 paise
 * - "0.1"   -> 10 paise
 * - "0.29"  -> 29 paise (in IEEE-754 floats, 0.29 * 100 = 28.999999999999996; string parsing gives exactly 29)
 * - "1.005" -> Throws Error (sub-paise fractions > 2 decimal places are rejected)
 */
export function rupeesToPaise(rupeesInput) {
  if (rupeesInput === null || rupeesInput === undefined) return 0;
  const str = String(rupeesInput).trim();
  if (!str) return 0;

  // Validate format: positive number with optional dot and decimal digits
  if (!/^\d+(\.\d+)?$/.test(str)) {
    throw new Error('Invalid rupee amount: must be a positive number with at most 2 decimal places');
  }

  const parts = str.split('.');
  const wholePart = parts[0];
  const decimalPart = parts[1] || '';

  if (decimalPart.length > 2) {
    throw new Error('Invalid rupee amount: cannot specify sub-paise fractions (maximum 2 decimal places permitted)');
  }

  const paddedDecimal = decimalPart.padEnd(2, '0');
  const combined = `${wholePart}${paddedDecimal}`.replace(/^0+/, '') || '0';
  const paise = parseInt(combined, 10);

  if (isNaN(paise) || paise < 0) {
    throw new Error('Invalid rupee amount: Conversion failed');
  }

  return paise;
}

/**
 * Friendly user messages for HTTP status codes and network errors
 */
function getFriendlyErrorMessage(status, data, _originalErr) {
  if (!status) {
    return 'Network Error: Unable to reach LedgerLine banking server. Please check your internet connection or try again later.';
  }
  switch (status) {
    case 400:
      return data?.message || 'Invalid Request: Please check your input parameters and try again.';
    case 401:
      return 'Session Expired or Unauthorized: Please log in again to continue.';
    case 403:
      return data?.message || 'Access Denied: You do not have permission to perform this banking operation.';
    case 404:
      return data?.message || 'Resource Not Found: The requested account or transaction record does not exist.';
    case 422:
      return data?.message || 'Transaction Unprocessable: Insufficient funds or business rule validation error.';
    case 429:
      return data?.message || 'Rate Limit Exceeded: Too many requests. Please wait a few moments before retrying.';
    case 500:
      return data?.message || 'Internal Server Error: The core banking service encountered an unexpected error. Please contact support.';
    default:
      return data?.message || `Operation failed (HTTP ${status}).`;
  }
}

/**
 * Core HTTP Request Wrapper
 */
export async function apiRequest(endpoint, options = {}) {
  const url = `${API_BASE}${endpoint}`;
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };

  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const config = {
    ...options,
    headers,
  };

  try {
    let response;
    try {
      response = await fetch(url, config);
    } catch (networkErr) {
      const error = new Error(getFriendlyErrorMessage(0, null, networkErr));
      error.isNetworkError = true;
      throw error;
    }

    // Handle 401 Unauthorized globally
    if (response.status === 401 && !endpoint.includes('/api/auth/login')) {
      clearAuth();
      // Redirect to login page immediately
      if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      const error = new Error(getFriendlyErrorMessage(401));
      error.status = 401;
      error.code = 'UNAUTHORIZED';
      throw error;
    }

    // CSV or Blob download
    if (headers['Accept'] === 'text/csv' || endpoint.endsWith('/export') || endpoint.endsWith('/csv')) {
      if (!response.ok) {
        const errorJson = await response.json().catch(() => ({}));
        throw new Error(getFriendlyErrorMessage(response.status, errorJson));
      }
      return await response.text();
    }

    const data = await response.json().catch(() => null);

    if (!response.ok) {
      const friendlyMsg = getFriendlyErrorMessage(response.status, data);
      const error = new Error(friendlyMsg);
      error.status = response.status;
      error.code = data?.code || 'ERROR';
      error.data = data;
      throw error;
    }

    return data;
  } catch (err) {
    throw err;
  }
}
