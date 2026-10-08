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

// StatsCard animates its displayed value with requestAnimationFrame, which is
// irrelevant noise for these assertions. Stub it with a plain synchronous
// render of the label/value props so we can assert on the actual numbers
// the Dashboard wires in, without waiting on the animation.
const statsCardStub = {
  props: ['label', 'value'],
  template: '<div class="card-compact stats-card-stub">{{ label }}: {{ value }}</div>'
}

function mountDashboard() {
  return mount(Dashboard, {
    global: { stubs: { RouterLink: true, StatsCard: statsCardStub } }
  })
}

describe('Dashboard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  describe('Registered Workflows step count', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('reads the step count directly from `steps` (an integer in the /api/dashboard/overview ' +
      'contract, per DashboardController#getOverview mapping "steps" -> stepCount), not a ' +
      '`steps` array and not a nonexistent `stepCount` field', async () => {
      dashboardApi.getOverview.mockResolvedValue({
        stats: {},
        recentExecutions: [],
        workflows: [
          {
            topic: 'order.created',
            serviceName: 'sales-service',
            steps: 2
          }
        ]
      })

      const wrapper = mountDashboard()

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

      const wrapper = mountDashboard()

      await vi.waitFor(() => expect(wrapper.text()).not.toContain('No workflows registered'))

      expect(wrapper.text()).toContain('? steps')
    })
  })

  describe('Stats cards', () => {
    it('reads "Active" from stats.inProgress, since the overview has no "active" field ' +
      '(see WorkflowQueryService#getStatistics)', async () => {
      dashboardApi.getOverview.mockResolvedValue({
        stats: {
          total: 10,
          inProgress: 4,
          completed: 3,
          failed: 1,
          pending: 1,
          retryPending: 1,
          cancelled: 0
        },
        recentExecutions: [],
        workflows: []
      })

      const wrapper = mountDashboard()

      await vi.waitFor(() => {
        const card = wrapper.findAll('.stats-card-stub').find((c) => c.text().startsWith('Active'))
        expect(card?.text()).toBe('Active: 4')
      })
    })

    it('shows a "Retry pending" card reading stats.retryPending', async () => {
      dashboardApi.getOverview.mockResolvedValue({
        stats: {
          total: 10,
          inProgress: 0,
          completed: 3,
          failed: 1,
          pending: 1,
          retryPending: 5,
          cancelled: 0
        },
        recentExecutions: [],
        workflows: []
      })

      const wrapper = mountDashboard()

      await vi.waitFor(() => {
        const card = wrapper.findAll('.stats-card-stub').find((c) => c.text().startsWith('Retry pending'))
        expect(card?.text()).toBe('Retry pending: 5')
      })
    })
  })
})
