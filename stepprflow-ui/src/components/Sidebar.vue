<template>
  <aside class="fixed inset-y-0 left-0 z-30 flex w-64 flex-col border-r border-gray-200 bg-white">
    <!-- Logo -->
    <div class="flex h-16 items-center gap-3 border-b border-gray-200 px-6">
      <img src="/stepprflow-logo.png" alt="StepprFlow" class="h-8 w-8 rounded-lg" />
      <span class="text-lg font-semibold text-gray-900">StepprFlow</span>
    </div>

    <!-- Navigation -->
    <nav class="flex-1 space-y-1 px-3 py-4">
      <router-link
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900"
        active-class="!bg-primary-50 !text-primary-700"
      >
        <svg class="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
          <path stroke-linecap="round" stroke-linejoin="round" :d="item.icon" />
        </svg>
        {{ item.label }}
      </router-link>
    </nav>

    <!-- Stats footer -->
    <div v-if="stats" class="border-t border-gray-200 px-4 py-4 space-y-2">
      <div class="flex items-center justify-between text-xs text-gray-500">
        <span>Active</span>
        <span class="font-medium text-gray-900">{{ stats.active ?? 0 }}</span>
      </div>
      <div class="flex items-center justify-between text-xs text-gray-500">
        <span>Failed</span>
        <span class="font-medium text-red-600">{{ stats.failed ?? 0 }}</span>
      </div>
      <div class="flex items-center justify-between text-xs text-gray-500">
        <span>Completed</span>
        <span class="font-medium text-emerald-600">{{ stats.completed ?? 0 }}</span>
      </div>
    </div>

    <!-- Current user + logout -->
    <div v-if="auth.authenticated && !auth.authDisabled" class="border-t border-gray-200 px-4 py-4">
      <div class="flex items-center justify-between gap-2">
        <div class="min-w-0">
          <p class="truncate text-sm font-medium text-gray-900" :title="auth.username">
            {{ auth.username || 'Signed in' }}
          </p>
          <p class="text-xs" :class="auth.isOperator ? 'text-emerald-600' : 'text-gray-400'">
            {{ auth.isOperator ? 'Operator' : 'Viewer' }}
          </p>
        </div>
        <button type="button" class="btn-secondary btn-sm shrink-0" @click="auth.logout()">
          Logout
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useWorkflowStore } from '@/stores/workflow.js'
import { useAuthStore } from '@/stores/auth.js'

const store = useWorkflowStore()
const auth = useAuthStore()
const stats = computed(() => store.stats)

const navItems = [
  {
    to: '/',
    label: 'Dashboard',
    icon: 'M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z'
  },
  {
    to: '/executions',
    label: 'Executions',
    icon: 'M3.75 12h16.5m-16.5 3.75h16.5M3.75 19.5h16.5M5.625 4.5h12.75a1.875 1.875 0 010 3.75H5.625a1.875 1.875 0 010-3.75z'
  },
  {
    to: '/workflows',
    label: 'Workflows',
    icon: 'M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 010 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z'
  },
  {
    to: '/metrics',
    label: 'Metrics',
    icon: 'M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z'
  }
]
</script>
