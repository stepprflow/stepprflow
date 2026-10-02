import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmModal from '@/components/ConfirmModal.vue'

function mountModal(props = {}) {
  return mount(ConfirmModal, {
    props: { show: true, title: 'Cancel Execution', message: 'Are you sure?', ...props },
    global: { stubs: { Teleport: true, Transition: false } }
  })
}

describe('ConfirmModal', () => {
  it('exposes dialog semantics with an accessible, labelled title', () => {
    const wrapper = mountModal()

    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.attributes('aria-modal')).toBe('true')

    const labelledBy = dialog.attributes('aria-labelledby')
    expect(labelledBy).toBeTruthy()
    expect(wrapper.find(`#${labelledBy}`).text()).toBe('Cancel Execution')
  })

  it('emits cancel when Escape is pressed on the dialog', async () => {
    const wrapper = mountModal()

    await wrapper.find('[role="dialog"]').trigger('keydown.esc')

    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('does not emit cancel on Escape while a confirmed action is in flight', async () => {
    const wrapper = mountModal({ loading: true })

    await wrapper.find('[role="dialog"]').trigger('keydown.esc')

    expect(wrapper.emitted('cancel')).toBeUndefined()
  })

  it('disables both actions while loading', () => {
    const wrapper = mountModal({ loading: true })

    const buttons = wrapper.findAll('button')
    expect(buttons.length).toBeGreaterThanOrEqual(2)
    buttons.forEach((button) => {
      expect(button.attributes('disabled')).toBeDefined()
    })
  })

  it('emits confirm when the confirm button is clicked', async () => {
    const wrapper = mountModal()

    await wrapper.find('button.btn-primary, button.btn-danger').trigger('click')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })
})
