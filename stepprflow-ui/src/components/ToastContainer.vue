<template>
  <Teleport to="body">
    <div class="pointer-events-none fixed right-4 top-4 z-[100] flex w-full max-w-sm flex-col gap-2">
      <TransitionGroup name="toast">
        <div
          v-for="t in toastStore.toasts"
          :key="t.id"
          role="alert"
          class="pointer-events-auto flex items-start gap-3 rounded-lg border p-3 shadow-lg"
          :class="toastClass(t.type)"
        >
          <span class="mt-1.5 h-2 w-2 shrink-0 rounded-full" :class="dotClass(t.type)" />
          <p class="flex-1 text-sm">{{ t.message }}</p>
          <button
            type="button"
            class="shrink-0 text-xs font-medium opacity-60 hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-gray-400 rounded"
            :aria-label="'Dismiss notification: ' + t.message"
            @click="toastStore.dismiss(t.id)"
          >
            &#10005;
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup>
import { watch } from 'vue'
import { useToastStore } from '@/stores/toast.js'
import { useWorkflowStore } from '@/stores/workflow.js'
import { useMetricsStore } from '@/stores/metrics.js'

// Global bridge: surfaces `store.error` from any Pinia store as a toast.
// Several stores (workflow, metrics) already capture API errors in
// `error.value` on every failed call, but nothing in the templates ever
// read it, so failures (network errors, 403/409/500...) were silently
// swallowed. This watches the known stores and turns any new error into
// a visible, dismissable notification, then clears it from the store so
// it isn't shown twice.
const toastStore = useToastStore()
const workflowStore = useWorkflowStore()
const metricsStore = useMetricsStore()

function watchStoreErrors(store) {
  watch(
    () => store.error,
    (message) => {
      if (message) {
        toastStore.push('error', message)
        store.clearError()
      }
    }
  )
}

watchStoreErrors(workflowStore)
watchStoreErrors(metricsStore)

function toastClass(type) {
  if (type === 'success') return 'border-emerald-200 bg-emerald-50 text-emerald-800'
  if (type === 'error') return 'border-red-200 bg-red-50 text-red-800'
  return 'border-gray-200 bg-white text-gray-800'
}

function dotClass(type) {
  if (type === 'success') return 'bg-emerald-500'
  if (type === 'error') return 'bg-red-500'
  return 'bg-gray-400'
}
</script>

<style>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
