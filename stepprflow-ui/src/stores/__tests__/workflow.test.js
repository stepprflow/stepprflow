import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkflowStore } from '@/stores/workflow.js'
import { dashboardApi, executionApi } from '@/services/api.js'

vi.mock('@/services/api.js', () => ({
  dashboardApi: {
    getOverview: vi.fn(),
    getWorkflows: vi.fn()
  },
  executionApi: {
    get: vi.fn(),
    list: vi.fn(),
    recent: vi.fn(),
    stats: vi.fn(),
    resume: vi.fn(),
    cancel: vi.fn(),
    updatePayloadField: vi.fn(),
    restorePayload: vi.fn()
  }
}))

describe('workflow store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('resumeExecution', () => {
    it('returns true on success and calls the resume endpoint', async () => {
      executionApi.resume.mockResolvedValue({})
      const store = useWorkflowStore()

      const ok = await store.resumeExecution('exec-1')

      expect(ok).toBe(true)
      expect(executionApi.resume).toHaveBeenCalledWith('exec-1')
    })

    it('refetches the execution when it matches the currently displayed one', async () => {
      executionApi.resume.mockResolvedValue({})
      executionApi.get.mockResolvedValue({ executionId: 'exec-1', status: 'IN_PROGRESS' })
      const store = useWorkflowStore()
      store.currentExecution = { executionId: 'exec-1', status: 'FAILED' }

      const ok = await store.resumeExecution('exec-1')

      expect(ok).toBe(true)
      expect(executionApi.get).toHaveBeenCalledWith('exec-1')
      expect(store.currentExecution.status).toBe('IN_PROGRESS')
    })

    it('returns false and surfaces the error on failure, without pretending success', async () => {
      executionApi.resume.mockRejectedValue(new Error('Execution is not resumable (409)'))
      const store = useWorkflowStore()
      store.currentExecution = { executionId: 'exec-1', status: 'FAILED' }

      const ok = await store.resumeExecution('exec-1')

      expect(ok).toBe(false)
      expect(store.error).toBe('Execution is not resumable (409)')
      // Must not have tried to refresh as if the resume had worked.
      expect(executionApi.get).not.toHaveBeenCalled()
    })
  })

  describe('cancelExecution', () => {
    it('returns true on success', async () => {
      executionApi.cancel.mockResolvedValue({})
      const store = useWorkflowStore()

      const ok = await store.cancelExecution('exec-2')

      expect(ok).toBe(true)
      expect(store.error).toBeNull()
      expect(executionApi.cancel).toHaveBeenCalledWith('exec-2')
    })

    it('returns false and sets the error on failure (e.g. 403 Forbidden)', async () => {
      executionApi.cancel.mockRejectedValue(new Error('Forbidden'))
      const store = useWorkflowStore()

      const ok = await store.cancelExecution('exec-2')

      expect(ok).toBe(false)
      expect(store.error).toBe('Forbidden')
    })
  })

  describe('updatePayloadField', () => {
    it('applies the update and returns true on success', async () => {
      const updated = { executionId: 'exec-3', payload: { amount: 42 } }
      executionApi.updatePayloadField.mockResolvedValue(updated)
      const store = useWorkflowStore()

      const ok = await store.updatePayloadField('exec-3', 'amount', 42, 'fix typo')

      expect(ok).toBe(true)
      expect(store.currentExecution).toEqual(updated)
      expect(executionApi.updatePayloadField).toHaveBeenCalledWith('exec-3', 'amount', 42, 'fix typo')
    })

    it('leaves currentExecution untouched and returns false on failure', async () => {
      const original = { executionId: 'exec-3', payload: { amount: 1 } }
      executionApi.updatePayloadField.mockRejectedValue(new Error('Validation failed (422)'))
      const store = useWorkflowStore()
      store.currentExecution = original

      const ok = await store.updatePayloadField('exec-3', 'amount', 'not-a-number', 'oops')

      expect(ok).toBe(false)
      expect(store.error).toBe('Validation failed (422)')
      expect(store.currentExecution).toEqual(original)
    })
  })

  describe('error mapping', () => {
    it('fetchExecution stores the thrown error message and stops loading', async () => {
      executionApi.get.mockRejectedValue(new Error('Network error — unable to reach the server'))
      const store = useWorkflowStore()

      await store.fetchExecution('exec-4')

      expect(store.error).toBe('Network error — unable to reach the server')
      expect(store.currentExecution).toBeNull()
      expect(store.loading).toBe(false)
    })

    it('fetchExecution clears a previous error once the retry succeeds', async () => {
      executionApi.get.mockResolvedValue({ executionId: 'exec-4', status: 'COMPLETED' })
      const store = useWorkflowStore()
      store.error = 'previous failure'

      await store.fetchExecution('exec-4')

      expect(store.error).toBeNull()
      expect(store.currentExecution).toEqual({ executionId: 'exec-4', status: 'COMPLETED' })
    })

    it('clearError resets the error to null', () => {
      const store = useWorkflowStore()
      store.error = 'boom'

      store.clearError()

      expect(store.error).toBeNull()
    })
  })

  describe('fetchOverview', () => {
    it('captures the error message when the overview call fails', async () => {
      dashboardApi.getOverview.mockRejectedValue(new Error('Internal Server Error'))
      const store = useWorkflowStore()

      await store.fetchOverview()

      expect(store.error).toBe('Internal Server Error')
    })
  })
})
