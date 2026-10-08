<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Metrics</h1>

    <!-- Health banner -->
    <div
      v-if="metricsStore.health"
      class="flex items-center gap-3 rounded-lg border p-4"
      :class="healthBannerClass"
    >
      <span class="h-3 w-3 rounded-full" :class="healthDotClass" />
      <span class="text-sm font-medium">System health: {{ metricsStore.health.status }}</span>
    </div>

    <!-- Global stats -->
    <div v-if="d" class="grid grid-cols-2 gap-4 lg:grid-cols-4">
      <StatsCard
        label="Started"
        :value="d.totalStarted ?? 0"
        icon="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z"
        variant="primary"
      />
      <StatsCard
        label="Completed"
        :value="d.totalCompleted ?? 0"
        icon="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
        variant="success"
      />
      <StatsCard
        label="Failed"
        :value="d.totalFailed ?? 0"
        icon="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z"
        variant="danger"
      />
      <StatsCard
        label="Active"
        :value="d.totalActive ?? 0"
        icon="M3.75 13.5l10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75z"
        variant="info"
      />
    </div>

    <!-- Rate bars -->
    <div v-if="d" class="grid gap-4 lg:grid-cols-2">
      <div class="card-compact">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm text-gray-600">Success Rate</span>
          <span class="text-sm font-semibold text-emerald-600">{{ formatRate(d.globalSuccessRate) }}%</span>
        </div>
        <div class="h-2.5 rounded-full bg-gray-100 overflow-hidden">
          <div class="h-full rounded-full bg-emerald-500 transition-all" :style="{ width: formatRate(d.globalSuccessRate) + '%' }" />
        </div>
      </div>
      <div class="card-compact">
        <div class="flex items-center justify-between mb-2">
          <span class="text-sm text-gray-600">Failure Rate</span>
          <span class="text-sm font-semibold text-red-600">{{ formatRate(d.globalFailureRate) }}%</span>
        </div>
        <div class="h-2.5 rounded-full bg-gray-100 overflow-hidden">
          <div class="h-full rounded-full bg-red-500 transition-all" :style="{ width: formatRate(d.globalFailureRate) + '%' }" />
        </div>
      </div>
    </div>

    <!-- Health + Outbox panels -->
    <div class="grid gap-6 lg:grid-cols-2">
      <HealthPanel :health="metricsStore.health" />
      <OutboxPanel :outbox="metricsStore.outbox" />
    </div>

    <!-- Circuit breakers: state + reset action only. These breakers
         (broker-kafka, broker-rabbitmq, workflow-execution) protect the
         monitor's own connection to the broker — the monitor observes
         messages, it doesn't route business calls for the actual services.
         So their call counters are structurally 0 and would misrepresent
         real per-service circuit-breaker metrics (a separate, unimplemented
         feature). Their STATE is still meaningful (it reflects whether the
         monitor itself can currently reach the broker), so we keep it,
         deduplicated against the Health Status panel above (see
         HealthPanel.vue, which hides the redundant "circuitBreakers" entry). -->
    <div v-if="metricsStore.circuitBreakers.length" class="card">
      <h2 class="mb-4 text-sm font-semibold text-gray-900">Circuit Breakers</h2>
      <div class="space-y-3">
        <div
          v-for="cb in metricsStore.circuitBreakers"
          :key="cb.name"
          class="flex items-center justify-between rounded-lg border border-gray-100 p-3"
        >
          <p class="text-sm font-medium text-gray-900">{{ cb.name }}</p>
          <div class="flex items-center gap-2">
            <span
              class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium"
              :class="cbStateClass(cb.state)"
            >
              {{ cb.state }}
            </span>
            <button
              v-if="cb.state !== 'CLOSED'"
              class="btn-secondary btn-sm"
              :disabled="!auth.isOperator"
              :title="auth.isOperator ? '' : operatorOnlyTitle"
              @click="confirmResetCb(cb.name)"
            >
              Reset
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Per-workflow table -->
    <div v-if="d?.workflowMetrics?.length" class="card overflow-hidden !p-0">
      <div class="border-b border-gray-100 bg-gray-50 px-4 py-3">
        <h2 class="text-sm font-semibold text-gray-900">Per-Workflow Metrics</h2>
      </div>
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-gray-100 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
            <th class="px-4 py-3">Topic</th>
            <th class="px-4 py-3">Service</th>
            <th class="px-4 py-3 text-right">Started</th>
            <th class="px-4 py-3 text-right">Completed</th>
            <th class="px-4 py-3 text-right">Failed</th>
            <th class="px-4 py-3 text-right">Active</th>
            <th class="px-4 py-3 text-right">Avg Duration</th>
            <th class="px-4 py-3 text-right">Success Rate</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-for="wm in d.workflowMetrics" :key="wm.topic + wm.serviceName" class="hover:bg-gray-50">
            <td class="px-4 py-3 font-medium text-gray-900">{{ wm.topic }}</td>
            <td class="px-4 py-3 text-gray-500">{{ wm.serviceName || '-' }}</td>
            <td class="px-4 py-3 text-right text-gray-700">{{ wm.started }}</td>
            <td class="px-4 py-3 text-right text-emerald-600">{{ wm.completed }}</td>
            <td class="px-4 py-3 text-right text-red-600">{{ wm.failed }}</td>
            <td class="px-4 py-3 text-right text-blue-600">{{ wm.active }}</td>
            <td class="px-4 py-3 text-right text-gray-500">{{ wm.avgDurationMs ? (wm.avgDurationMs / 1000).toFixed(1) + 's' : '-' }}</td>
            <td class="px-4 py-3 text-right">
              <span class="font-medium" :class="wm.successRate >= 90 ? 'text-emerald-600' : wm.successRate >= 50 ? 'text-amber-600' : 'text-red-600'">
                {{ formatRate(wm.successRate) }}%
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Reset circuit breaker confirmation -->
    <ConfirmModal
      :show="confirmModal.show"
      :title="confirmModal.title"
      :message="confirmModal.message"
      type="warning"
      confirm-label="Reset"
      :loading="confirmModal.loading"
      @confirm="confirmModal.onConfirm"
      @cancel="confirmModal.show = false"
    />
  </div>
</template>

<script setup>
import { computed, reactive, onMounted, onUnmounted } from 'vue'
import { useMetricsStore } from '@/stores/metrics.js'
import { useToastStore } from '@/stores/toast.js'
import { useAuthStore } from '@/stores/auth.js'
import StatsCard from '@/components/StatsCard.vue'
import HealthPanel from '@/components/HealthPanel.vue'
import OutboxPanel from '@/components/OutboxPanel.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'

const metricsStore = useMetricsStore()
const toast = useToastStore()
const auth = useAuthStore()
const d = computed(() => metricsStore.dashboard)
const operatorOnlyTitle = 'Action réservée aux opérateurs'

const confirmModal = reactive({
  show: false, title: '', message: '', loading: false, onConfirm: () => {}
})

function formatRate(val) {
  if (val == null) return '0.0'
  return Number(val).toFixed(1)
}

function cbStateClass(state) {
  if (state === 'CLOSED') return 'bg-emerald-100 text-emerald-700'
  if (state === 'HALF_OPEN') return 'bg-amber-100 text-amber-700'
  return 'bg-red-100 text-red-700'
}

function confirmResetCb(name) {
  confirmModal.title = 'Reset Circuit Breaker'
  confirmModal.message = `Reset the "${name}" circuit breaker to CLOSED and clear its counters?`
  confirmModal.onConfirm = async () => {
    confirmModal.loading = true
    const ok = await metricsStore.resetCircuitBreaker(name)
    confirmModal.loading = false
    // Only close on confirmed success — on failure the global toast
    // (bound to metricsStore.error) already surfaces the API error.
    if (ok) {
      confirmModal.show = false
      toast.push('success', `Circuit breaker "${name}" reset.`)
    }
  }
  confirmModal.show = true
}

const healthBannerClass = computed(() => {
  const s = metricsStore.health?.status
  if (s === 'UP') return 'border-emerald-200 bg-emerald-50'
  if (s === 'DEGRADED') return 'border-amber-200 bg-amber-50'
  return 'border-red-200 bg-red-50'
})

const healthDotClass = computed(() => {
  const s = metricsStore.health?.status
  if (s === 'UP') return 'bg-emerald-500'
  if (s === 'DEGRADED') return 'bg-amber-500'
  return 'bg-red-500'
})

let interval

onMounted(() => {
  metricsStore.fetchAll()
  interval = setInterval(() => metricsStore.fetchAll(), 10000)
})

onUnmounted(() => {
  clearInterval(interval)
})
</script>
