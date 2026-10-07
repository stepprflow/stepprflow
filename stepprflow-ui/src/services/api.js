import axios from 'axios'

// Session-based auth (cookie JSESSIONID), CSRF double-submit cookie.
// The backend sets a non-HttpOnly `XSRF-TOKEN` cookie; axios mirrors it
// back into the `X-XSRF-TOKEN` header on every mutating request. All of
// this only works with credentials (cookies) sent on same-origin XHR.
const csrfConfig = {
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN'
}

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
  ...csrfConfig
})

// Form login / logout live at the servlet root (`/login`, `/logout`), not
// under `/api`, so they need their own client with an empty baseURL.
const rootClient = axios.create({
  timeout: 10000,
  ...csrfConfig
})

export class ApiError extends Error {
  constructor(message, status, code, details) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }

  get isNotFound() {
    return this.status === 404
  }

  get isValidation() {
    return this.status === 400 || this.status === 422
  }

  get isServerError() {
    return this.status >= 500
  }

  get isConflict() {
    return this.status === 409
  }
}

async function handleError(error) {
  if (error.response) {
    const { status, data } = error.response
    const message = data?.message || data?.error || error.message
    const code = data?.code || `HTTP_${status}`

    // 401: session missing/expired. Unless the caller opted out
    // (the auth probe `/auth/me` and the login attempt expect it), kick
    // the user back to the login screen via the auth store.
    if (status === 401 && !error.config?.skipAuthRedirect) {
      const { useAuthStore } = await import('@/stores/auth.js')
      useAuthStore().handleUnauthorized()
    }

    // 403: authenticated but lacking OPERATOR. The action is simply not
    // allowed — surface it, but keep the user logged in.
    if (status === 403) {
      const { useToastStore } = await import('@/stores/toast.js')
      useToastStore().push('error', 'Action réservée aux opérateurs.')
    }

    throw new ApiError(message, status, code, data)
  }
  if (error.request) {
    throw new ApiError('Network error — unable to reach the server', 0, 'NETWORK_ERROR')
  }
  throw new ApiError(error.message, 0, 'REQUEST_ERROR')
}

api.interceptors.response.use(response => response, handleError)
rootClient.interceptors.response.use(response => response, handleError)

export const authApi = {
  // Public — tells the UI which login screen to render.
  getConfig: () => api.get('/auth/config', { skipAuthRedirect: true }).then(r => r.data),

  // Authenticated probe. 401 is an expected answer (not logged in), so it
  // opts out of the global redirect; callers interpret the 401 themselves.
  getMe: () => api.get('/auth/me', { skipAuthRedirect: true }).then(r => r.data),

  // Spring form login — form-urlencoded. A bad password does not necessarily
  // come back as 401 (default Spring redirects to /login?error), so callers
  // must confirm success with a follow-up getMe() rather than trusting this.
  loginBasic: (username, password) =>
    rootClient.post('/login', new URLSearchParams({ username, password }), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      skipAuthRedirect: true
    }),

  logout: () => rootClient.post('/logout')
}

export const dashboardApi = {
  getOverview: () => api.get('/dashboard/overview').then(r => r.data),
  getConfig: () => api.get('/dashboard/config').then(r => r.data),
  getWorkflows: (params) => api.get('/dashboard/workflows', { params }).then(r => r.data)
}

export const executionApi = {
  get: (id) => api.get(`/dashboard/executions/${id}`).then(r => r.data),

  list: (params) => api.get('/dashboard/executions', { params }).then(r => r.data),

  recent: () => api.get('/workflows/recent').then(r => r.data),

  stats: () => api.get('/workflows/stats').then(r => r.data),

  resume: (id) => api.post(`/workflows/${id}/resume`).then(r => r.data),

  cancel: (id) => api.delete(`/workflows/${id}`).then(r => r.data),

  updatePayloadField: (id, fieldPath, newValue, reason) =>
    api.patch(`/workflows/${id}/payload`, { fieldPath, newValue, reason }).then(r => r.data),

  restorePayload: (id) => api.post(`/workflows/${id}/payload/restore`).then(r => r.data)
}

export const metricsApi = {
  getDashboard: () => api.get('/metrics').then(r => r.data),
  getByTopic: (topic) => api.get(`/metrics/${encodeURIComponent(topic)}`).then(r => r.data),
  getSummary: () => api.get('/metrics/summary').then(r => r.data)
}

export const circuitBreakerApi = {
  list: () => api.get('/circuit-breakers').then(r => r.data),
  getConfig: () => api.get('/circuit-breakers/config').then(r => r.data),
  get: (name) => api.get(`/circuit-breakers/${encodeURIComponent(name)}`).then(r => r.data),
  reset: (name) => api.post(`/circuit-breakers/${encodeURIComponent(name)}/reset`).then(r => r.data)
}

export const healthApi = {
  get: () => api.get('/health').then(r => r.data)
}

export const outboxApi = {
  stats: () => api.get('/outbox/stats').then(r => r.data)
}

export default api
