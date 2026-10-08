import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Workflows from '@/views/Workflows.vue'
import { dashboardApi } from '@/services/api.js'

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

function mountWorkflows() {
  return mount(Workflows, { global: { stubs: { Teleport: true } } })
}

describe('Workflows - registered-by badges', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('reads service badges from `registeredBy` (list of {serviceName, instanceId, host, port} ' +
    'per DashboardController#getCombinedWorkflowDefinitions), not the nonexistent `registeredServices`', async () => {
    dashboardApi.getWorkflows.mockResolvedValue([
      {
        topic: 'order.created',
        serviceName: 'sales-service',
        status: 'ACTIVE',
        steps: [{ name: 'validate' }, { name: 'charge' }],
        registeredBy: [
          { serviceName: 'sales-service', instanceId: 'sales-1', host: '10.0.0.1', port: 8080 },
          { serviceName: 'billing-service', instanceId: 'billing-1', host: '10.0.0.2', port: 8081 }
        ]
      }
    ])

    const wrapper = mountWorkflows()

    await vi.waitFor(() => expect(wrapper.text()).not.toContain('No workflows found'))

    expect(wrapper.text()).toContain('sales-service')
    expect(wrapper.text()).toContain('billing-service')
    // Must render the serviceName string, not "[object Object]".
    expect(wrapper.text()).not.toContain('[object Object]')
  })

  it('shows no service badges when a workflow has no registeredBy entries', async () => {
    dashboardApi.getWorkflows.mockResolvedValue([
      {
        topic: 'legacy.topic',
        serviceName: 'discovered',
        status: 'ACTIVE',
        steps: []
      }
    ])

    const wrapper = mountWorkflows()

    await vi.waitFor(() => expect(wrapper.text()).not.toContain('No workflows found'))

    expect(wrapper.find('.rounded.bg-gray-100.px-1\\.5').exists()).toBe(false)
  })
})
