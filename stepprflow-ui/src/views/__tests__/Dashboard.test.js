import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Dashboard from '@/views/Dashboard.vue'
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

describe('Dashboard - Registered Workflows step count', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('counts steps from the `steps` array returned by the registry API, not a nonexistent `stepCount` field', async () => {
    dashboardApi.getOverview.mockResolvedValue({
      stats: {},
      recentExecutions: [],
      workflows: [
        {
          topic: 'order.created',
          serviceName: 'sales-service',
          steps: [{ name: 'validate' }, { name: 'charge' }]
        }
      ]
    })

    const wrapper = mount(Dashboard, {
      global: { stubs: { RouterLink: true } }
    })

    await vi.waitFor(() => expect(wrapper.text()).not.toContain('No workflows registered'))

    expect(wrapper.text()).toContain('2 steps')
    expect(wrapper.text()).not.toContain('? steps')
  })

  it('falls back to "?" when a registered workflow has no steps data at all', async () => {
    dashboardApi.getOverview.mockResolvedValue({
      stats: {},
      recentExecutions: [],
      workflows: [{ topic: 'legacy.topic', serviceName: 'legacy-service' }]
    })

    const wrapper = mount(Dashboard, {
      global: { stubs: { RouterLink: true } }
    })

    await vi.waitFor(() => expect(wrapper.text()).not.toContain('No workflows registered'))

    expect(wrapper.text()).toContain('? steps')
  })
})
