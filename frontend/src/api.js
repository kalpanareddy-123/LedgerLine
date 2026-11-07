/**
 * LedgerLine API Client
 * Configured with environment variables, JWT bearer auth from sessionStorage,
 * Idempotency Key header injection, and 401 redirect handling.
 */

const API_BASE = import.meta.env.VITE_API_BASE_URL || '';

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
 * Convert user-entered Rupees to paise (BIGINT integer)
 * Example: "100.50" -> 10050
 */
export function rupeesToPaise(rupeesInput) {
  const parsed = parseFloat(rupeesInput);
  if (isNaN(parsed) || parsed <= 0) return 0;
  return Math.round(parsed * 100);
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
    const response = await fetch(url, config);

    // Handle 401 Unauthorized globally
    if (response.status === 401 && !endpoint.includes('/api/auth/login')) {
      clearAuth();
      window.location.href = '/login';
      throw new Error('Session expired. Please log in again.');
    }

    // CSV or Blob download
    if (headers['Accept'] === 'text/csv' || endpoint.endsWith('/export')) {
      if (!response.ok) {
        const errorJson = await response.json().catch(() => ({}));
        throw new Error(errorJson.message || `Download failed: ${response.status}`);
      }
      return await response.text();
    }

    const data = await response.json().catch(() => null);

    if (!response.ok) {
      const errorMsg = data?.message || data?.details || `Request failed with status ${response.status}`;
      const error = new Error(errorMsg);
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
