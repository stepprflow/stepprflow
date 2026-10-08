<template>
  <div class="card space-y-4">
    <h3 class="text-sm font-semibold text-gray-900">Health Status</h3>

    <div v-if="!health" class="text-sm text-gray-400">Loading...</div>

    <template v-else>
      <!-- Overall status -->
      <div class="flex items-center gap-2">
        <span
          class="inline-flex h-3 w-3 rounded-full"
          :class="health.status === 'UP' ? 'bg-emerald-500' : health.status === 'DEGRADED' ? 'bg-amber-500' : 'bg-red-500'"
        />
        <span class="text-sm font-medium" :class="statusTextClass">{{ health.status }}</span>
      </div>

      <!-- Components -->
      <!-- "circuitBreakers" is intentionally excluded: it is already shown in detail
           (with a Reset action) in the dedicated Circuit Breakers section below,
           so showing it here too would just duplicate the same state. -->
      <div v-if="visibleComponents.length" class="space-y-3">
        <div
          v-for="[name, component] in visibleComponents"
          :key="name"
          class="rounded-lg border border-gray-100 bg-gray-50 p-3"
        >
          <div class="flex items-center justify-between">
            <span class="text-sm font-medium text-gray-700">{{ name }}</span>
            <span
              class="inline-flex items-center gap-1 text-xs font-medium"
              :class="component.status === 'UP' ? 'text-emerald-600' : 'text-red-600'"
            >
              <span class="h-1.5 w-1.5 rounded-full" :class="component.status === 'UP' ? 'bg-emerald-500' : 'bg-red-500'" />
              {{ component.status }}
            </span>
          </div>
          <div v-if="component.details" class="mt-2 space-y-1">
            <div v-for="(val, key) in component.details" :key="key" class="flex items-center justify-between text-xs text-gray-500">
              <span>{{ key }}</span>
              <span class="font-mono text-gray-700">{{ val }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  health: { type: Object, default: null }
})

const statusTextClass = computed(() => {
  if (!props.health) return ''
  if (props.health.status === 'UP') return 'text-emerald-700'
  if (props.health.status === 'DEGRADED') return 'text-amber-700'
  return 'text-red-700'
})

const visibleComponents = computed(() => {
  if (!props.health?.components) return []
  return Object.entries(props.health.components).filter(([name]) => name !== 'circuitBreakers')
})
</script>
