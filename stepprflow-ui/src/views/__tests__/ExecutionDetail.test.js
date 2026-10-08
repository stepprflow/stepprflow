import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ExecutionDetail from '@/views/ExecutionDetail.vue'
import { executionApi } from '@/services/api.js'

vi.mock('@/services/api.js', () => ({
  executionApi: {
    get: vi.fn(),
    list: vi.fn(),
    recent: vi.fn(),
    stats: vi.fn(),
    resume: vi.fn(),
    cancel: vi.fn(),
    updatePayloadField: vi.fn(),
    restorePayload: vi.fn()
  },
  authApi: {
    getConfig: vi.fn().mockResolvedValue({ mode: '' }),
    me: vi.fn().mockResolvedValue(null)
  }
}))

function mountExecutionDetail(id = 'exec-1') {
  return mount(ExecutionDetail, {
    props: { id },
    global: {
      stubs: {
        RouterLink: true,
        // Keep the test focused on the Retry Information block.
        PayloadEditor: true,
        ConfirmModal: true,
        ChangeReasonModal: true
      }
    }
  })
}

describe('ExecutionDetail - Retry Information', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('reads `attempt`/`maxAttempts` from retryInfo (the real RetryInfo DTO field names), ' +
    'not the nonexistent `currentAttempt`/`maxRetries`', async () => {
    executionApi.get.mockResolvedValue({
      executionId: 'exec-1',
      topic: 'order.created',
      status: 'RETRY_PENDING',
      retryInfo: {
        attempt: 2,
        maxAttempts: 5,
        nextRetryAt: '2026-10-08T10:00:00Z',
        lastError: 'downstream timeout'
      }
    })

    const wrapper = mountExecutionDetail()

    await vi.waitFor(() => expect(wrapper.text()).toContain('Retry Information'))

    expect(wrapper.text()).toContain('Attempt')
    expect(wrapper.text()).toContain('2')
    expect(wrapper.text()).toContain('Max Retries')
    expect(wrapper.text()).toContain('5')
  })

  it('shows "-" placeholders rather than crashing when retryInfo fields are absent', async () => {
    executionApi.get.mockResolvedValue({
      executionId: 'exec-2',
      topic: 'order.created',
      status: 'RETRY_PENDING',
      retryInfo: {}
    })

    const wrapper = mountExecutionDetail('exec-2')

    await vi.waitFor(() => expect(wrapper.text()).toContain('Retry Information'))

    const dds = wrapper.findAll('dd')
    const texts = dds.map((dd) => dd.text())
    expect(texts).toContain('-')
  })
})
