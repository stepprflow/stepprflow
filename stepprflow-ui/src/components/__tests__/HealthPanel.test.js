import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import HealthPanel from '@/components/HealthPanel.vue'

describe('HealthPanel', () => {
  it('does not render the circuitBreakers component, since it is already shown ' +
    'with full detail (and a Reset action) in the dedicated Circuit Breakers section', () => {
    const wrapper = mount(HealthPanel, {
      props: {
        health: {
          status: 'UP',
          components: {
            broker: { status: 'UP', details: { type: 'KAFKA', available: true } },
            circuitBreakers: {
              status: 'UP',
              details: { orderProcessing: 'CLOSED', totalCircuitBreakers: 1 }
            }
          }
        }
      }
    })

    expect(wrapper.text()).toContain('broker')
    expect(wrapper.text()).not.toContain('circuitBreakers')
    expect(wrapper.text()).not.toContain('orderProcessing')
  })

  it('still renders other components normally when there is no circuitBreakers entry', () => {
    const wrapper = mount(HealthPanel, {
      props: {
        health: {
          status: 'UP',
          components: {
            broker: { status: 'UP', details: { type: 'KAFKA', available: true } }
          }
        }
      }
    })

    expect(wrapper.text()).toContain('broker')
    expect(wrapper.text()).toContain('KAFKA')
  })

  it('renders a loading state when no health data is available yet', () => {
    const wrapper = mount(HealthPanel, { props: { health: null } })

    expect(wrapper.text()).toContain('Loading...')
  })
})
