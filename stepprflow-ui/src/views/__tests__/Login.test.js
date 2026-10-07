import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import Login from '@/views/Login.vue'
import { useAuthStore } from '@/stores/auth.js'

// Login pulls useRouter/useRoute from vue-router; stub them so the view can
// mount without a real router instance.
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

// auth.js imports the app router; stub it to keep the render test isolated.
vi.mock('@/router', () => ({ default: { push: vi.fn(), currentRoute: { value: {} } } }))

function mountLogin(mode) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.mode = mode
  return mount(Login)
}

describe('Login.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the username/password form in basic mode', () => {
    const wrapper = mountLogin('basic')

    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.find('input#username').exists()).toBe(true)
    expect(wrapper.find('input#password').exists()).toBe(true)
    // Accessible labels wired to the inputs.
    expect(wrapper.find('label[for="username"]').exists()).toBe(true)
    expect(wrapper.find('label[for="password"]').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').exists()).toBe(true)
  })

  it('renders the Keycloak button (and no form) in oidc mode', () => {
    const wrapper = mountLogin('oidc')

    expect(wrapper.find('form').exists()).toBe(false)
    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('Keycloak')
  })

  it('calls loginOidc when the Keycloak button is clicked', async () => {
    const wrapper = mountLogin('oidc')
    const auth = useAuthStore()
    const spy = vi.spyOn(auth, 'loginOidc').mockImplementation(() => {})

    await wrapper.find('button').trigger('click')

    expect(spy).toHaveBeenCalled()
  })
})
