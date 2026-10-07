import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import router from '@/router'
import { authApi } from '@/services/api.js'

// Authentication state for the monitoring UI. The backend picks a single
// mode per deployment (`stepprflow.monitor.auth.mode=basic|oidc`); the UI
// asks `/auth/config` at startup to know which login screen to show.
export const useAuthStore = defineStore('auth', () => {
  const mode = ref('')            // 'basic' | 'oidc' | '' (auth disabled)
  const oidcEnabled = ref(false)
  const authenticated = ref(false)
  const username = ref('')
  const authorities = ref([])
  const operator = ref(false)
  const initialized = ref(false)  // config + me have been loaded at least once

  const isBasic = computed(() => mode.value === 'basic')
  const isOidc = computed(() => mode.value === 'oidc')
  // No auth mode configured means the backend is open — treat everyone as
  // an authenticated operator so the UI stays fully usable.
  const authDisabled = computed(() => mode.value === '' || mode.value == null)
  const isOperator = computed(() => authDisabled.value || operator.value)

  function reset() {
    authenticated.value = false
    username.value = ''
    authorities.value = []
    operator.value = false
  }

  async function loadConfig() {
    try {
      const cfg = await authApi.getConfig()
      mode.value = cfg?.mode ?? ''
      oidcEnabled.value = !!cfg?.oidc
    } catch {
      // If the public config call fails, fall back to "no auth" rather than
      // locking the user out of a monitoring tool.
      mode.value = ''
      oidcEnabled.value = false
    }
    return mode.value
  }

  async function loadMe() {
    try {
      const me = await authApi.getMe()
      username.value = me?.username ?? ''
      authorities.value = me?.authorities ?? []
      operator.value = !!me?.operator
      authenticated.value = true
      return true
    } catch (e) {
      if (e.status === 401) {
        reset()
        return false
      }
      throw e
    }
  }

  async function init() {
    await loadConfig()
    if (authDisabled.value) {
      authenticated.value = true
    } else {
      await loadMe()
    }
    initialized.value = true
  }

  async function loginBasic(user, password) {
    try {
      await authApi.loginBasic(user, password)
    } catch (e) {
      // 401 just means the credentials were rejected; any other status is a
      // real failure worth surfacing.
      if (e.status !== 401) throw e
    }
    // Source of truth is the session, not the POST response: confirm with me.
    return loadMe()
  }

  function loginOidc() {
    window.location.href = '/oauth2/authorization/keycloak'
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // Even if the server call fails, drop local state and send the user
      // back to the login screen.
    }
    reset()
    router.push({ name: 'Login' })
  }

  // Called by the API interceptor on an unexpected 401 (expired session).
  function handleUnauthorized() {
    reset()
    const current = router.currentRoute.value
    if (current.name !== 'Login') {
      router.push({ name: 'Login', query: { redirect: current.fullPath } })
    }
  }

  return {
    mode,
    oidcEnabled,
    authenticated,
    username,
    authorities,
    operator,
    initialized,
    isBasic,
    isOidc,
    authDisabled,
    isOperator,
    loadConfig,
    loadMe,
    init,
    loginBasic,
    loginOidc,
    logout,
    handleUnauthorized
  }
})
