<template>
  <div class="flex min-h-screen items-center justify-center bg-gray-50 p-4">
    <div class="card w-full max-w-sm space-y-6">
      <!-- Brand -->
      <div class="flex flex-col items-center gap-3 text-center">
        <img src="/stepprflow-logo.png" alt="StepprFlow" class="h-10 w-10 rounded-lg" />
        <div>
          <h1 class="text-lg font-semibold text-gray-900">StepprFlow Monitoring</h1>
          <p class="mt-0.5 text-sm text-gray-500">Sign in to continue</p>
        </div>
      </div>

      <!-- Basic / form login -->
      <form v-if="auth.isBasic" class="space-y-4" @submit.prevent="submitBasic">
        <div class="space-y-1">
          <label for="username" class="block text-sm font-medium text-gray-700">Username</label>
          <input
            id="username"
            v-model="username"
            type="text"
            name="username"
            autocomplete="username"
            required
            :disabled="loading"
            class="input"
          />
        </div>

        <div class="space-y-1">
          <label for="password" class="block text-sm font-medium text-gray-700">Password</label>
          <input
            id="password"
            v-model="password"
            type="password"
            name="password"
            autocomplete="current-password"
            required
            :disabled="loading"
            class="input"
          />
        </div>

        <p v-if="errorMessage" role="alert" class="text-sm text-red-600">
          {{ errorMessage }}
        </p>

        <button type="submit" class="btn-primary w-full" :disabled="loading">
          {{ loading ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>

      <!-- OIDC login -->
      <div v-else-if="auth.isOidc" class="space-y-4">
        <p v-if="errorMessage" role="alert" class="text-sm text-red-600">
          {{ errorMessage }}
        </p>
        <button type="button" class="btn-primary w-full" @click="auth.loginOidc()">
          Se connecter avec Keycloak
        </button>
      </div>

      <!-- No auth configured -->
      <p v-else class="text-center text-sm text-gray-500">
        Authentication is disabled on this instance.
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

async function submitBasic() {
  errorMessage.value = ''
  loading.value = true
  try {
    const ok = await auth.loginBasic(username.value, password.value)
    if (ok) {
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
      router.push(redirect)
    } else {
      errorMessage.value = 'Invalid username or password.'
    }
  } catch {
    errorMessage.value = 'Unable to sign in. Please try again.'
  } finally {
    loading.value = false
  }
}
</script>
