import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth.js'
import { authApi } from '@/services/api.js'

vi.mock('@/services/api.js', () => ({
  authApi: {
    getConfig: vi.fn(),
    getMe: vi.fn(),
    loginBasic: vi.fn(),
    logout: vi.fn()
  }
}))

// auth.js imports the router for redirects; stub it so the store test stays
// isolated from the real route table.
vi.mock('@/router', () => ({
  default: {
    push: vi.fn(),
    currentRoute: { value: { name: 'Dashboard', fullPath: '/' } }
  }
}))

function unauthorized() {
  const e = new Error('Unauthorized')
  e.status = 401
  return e
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('loadMe', () => {
    it('fills the identity and marks the user authenticated on 200', async () => {
      authApi.getMe.mockResolvedValue({
        username: 'ops',
        authorities: ['OPERATOR', 'VIEWER'],
        operator: true
      })
      const auth = useAuthStore()

      const ok = await auth.loadMe()

      expect(ok).toBe(true)
      expect(auth.authenticated).toBe(true)
      expect(auth.username).toBe('ops')
      expect(auth.authorities).toEqual(['OPERATOR', 'VIEWER'])
      expect(auth.operator).toBe(true)
    })

    it('clears the identity and stays unauthenticated on 401', async () => {
      authApi.getMe.mockRejectedValue(unauthorized())
      const auth = useAuthStore()
      // Pretend a previous session was present.
      auth.authenticated = true
      auth.username = 'stale'
      auth.authorities = ['VIEWER']

      const ok = await auth.loadMe()

      expect(ok).toBe(false)
      expect(auth.authenticated).toBe(false)
      expect(auth.username).toBe('')
      expect(auth.authorities).toEqual([])
      expect(auth.operator).toBe(false)
    })

    it('rethrows non-401 errors instead of silently logging the user out', async () => {
      const boom = new Error('Server error')
      boom.status = 500
      authApi.getMe.mockRejectedValue(boom)
      const auth = useAuthStore()

      await expect(auth.loadMe()).rejects.toThrow('Server error')
    })
  })

  describe('loginBasic', () => {
    it('posts the credentials then confirms the session via getMe', async () => {
      authApi.loginBasic.mockResolvedValue({})
      authApi.getMe.mockResolvedValue({ username: 'ops', authorities: ['OPERATOR'], operator: true })
      const auth = useAuthStore()

      const ok = await auth.loginBasic('ops', 's3cret')

      expect(ok).toBe(true)
      expect(authApi.loginBasic).toHaveBeenCalledWith('ops', 's3cret')
      expect(authApi.getMe).toHaveBeenCalled()
      expect(auth.authenticated).toBe(true)
    })

    it('returns false on bad credentials (login 401 then me 401), without throwing', async () => {
      authApi.loginBasic.mockRejectedValue(unauthorized())
      authApi.getMe.mockRejectedValue(unauthorized())
      const auth = useAuthStore()

      const ok = await auth.loginBasic('ops', 'wrong')

      expect(ok).toBe(false)
      expect(auth.authenticated).toBe(false)
    })

    it('treats a 200 login that still has no session as a failure', async () => {
      // Default Spring redirects a bad login to /login?error (HTTP 200),
      // so the POST may resolve even though auth failed — getMe is the truth.
      authApi.loginBasic.mockResolvedValue({})
      authApi.getMe.mockRejectedValue(unauthorized())
      const auth = useAuthStore()

      const ok = await auth.loginBasic('ops', 'wrong')

      expect(ok).toBe(false)
      expect(auth.authenticated).toBe(false)
    })
  })

  describe('isOperator', () => {
    it('is true for an operator', async () => {
      authApi.getMe.mockResolvedValue({ username: 'ops', authorities: ['OPERATOR'], operator: true })
      const auth = useAuthStore()
      await auth.loadMe()

      expect(auth.isOperator).toBe(true)
    })

    it('is false for a viewer', async () => {
      authApi.getMe.mockResolvedValue({ username: 'view', authorities: ['VIEWER'], operator: false })
      const auth = useAuthStore()
      auth.mode = 'basic' // a real auth mode is active, so the open-backend shortcut doesn't apply
      await auth.loadMe()

      expect(auth.isOperator).toBe(false)
    })

    it('is true when no auth mode is configured (open backend)', async () => {
      authApi.getConfig.mockResolvedValue({ mode: '', oidc: false })
      const auth = useAuthStore()
      await auth.init()

      expect(auth.authDisabled).toBe(true)
      expect(auth.isOperator).toBe(true)
      expect(auth.authenticated).toBe(true)
    })
  })

  describe('init', () => {
    it('loads config then probes the session in basic mode', async () => {
      authApi.getConfig.mockResolvedValue({ mode: 'basic', oidc: false })
      authApi.getMe.mockRejectedValue(unauthorized())
      const auth = useAuthStore()

      await auth.init()

      expect(auth.mode).toBe('basic')
      expect(auth.isBasic).toBe(true)
      expect(auth.initialized).toBe(true)
      expect(auth.authenticated).toBe(false)
      expect(authApi.getMe).toHaveBeenCalled()
    })
  })
})
