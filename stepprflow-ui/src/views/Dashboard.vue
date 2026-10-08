<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Dashboard</h1>

    <!-- Stats cards -->
    <div class="grid grid-cols-3 gap-4 xl:grid-cols-7">
      <StatsCard
        label="Total"
        :value="s.total ?? 0"
        icon="M3.75 3v11.25A2.25 2.25 0 006 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0118 16.5h-2.25m-7.5 0h7.5m-7.5 0l-1 3m8.5-3l1 3m0 0l.5 1.5m-.5-1.5h-9.5m0 0l-.5 1.5"
        variant="default"
      />
      <!-- The overview API has no "active" field; "Active" means in-progress
           executions, i.e. stats.inProgress (see WorkflowQueryService#getStatistics). -->
      <StatsCard
        label="Active"
        :value="s.inProgress ?? 0"
        icon="M5.25 5.653c0-.856.917-1.398 1.667-.986l11.54 6.348a1.125 1.125 0 010 1.971l-11.54 6.347a1.125 1.125 0 01-1.667-.985V5.653z"
        variant="primary"
      />
      <StatsCard
        label="Completed"
        :value="s.completed ?? 0"
        icon="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
        variant="success"
      />
      <StatsCard
        label="Failed"
        :value="s.failed ?? 0"
        icon="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z"
        variant="danger"
      />
      <StatsCard
        label="Pending"
        :value="s.pending ?? 0"
        icon="M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z"
        variant="warning"
      />
      <!-- Surfaces stuck/retrying executions (stats.retryPending), previously
           not shown anywhere on the Dashboard. -->
      <StatsCard
        label="Retry pending"
        :value="s.retryPending ?? 0"
        icon="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99"
        variant="warning"
      />
      <StatsCard
        label="Cancelled"
        :value="s.cancelled ?? 0"
        icon="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
        variant="info"
      />
    </div>

    <div class="grid gap-6 lg:grid-cols-2">
      <!-- Recent executions -->
      <div class="card">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">Recent Executions</h2>
        <div v-if="store.recentExecutions.length === 0" class="text-sm text-gray-400">No recent executions</div>
        <div class="space-y-2">
          <router-link
            v-for="exec in store.recentExecutions"
            :key="exec.executionId"
            :to="`/executions/${exec.executionId}`"
            class="flex items-center justify-between rounded-lg border border-gray-100 p-3 transition-colors hover:bg-gray-50"
          >
            <div class="min-w-0">
              <p class="truncate text-sm font-medium text-gray-900">{{ exec.topic }}</p>
              <p class="truncate text-xs text-gray-500 font-mono">{{ exec.executionId }}</p>
            </div>
            <div class="flex items-center gap-3 shrink-0">
              <span v-if="exec.currentStep != null" class="text-xs text-gray-400">
                {{ exec.currentStep }}/{{ exec.totalSteps }}
              </span>
              <StatusBadge :status="exec.status" />
            </div>
          </router-link>
        </div>
      </div>

      <!-- Registered workflows -->
      <div class="card">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">Registered Workflows</h2>
        <div v-if="store.workflows.length === 0" class="text-sm text-gray-400">No workflows registered</div>
        <div class="space-y-2">
          <div
            v-for="wf in store.workflows"
            :key="wf.topic + wf.serviceName"
            class="flex items-center justify-between rounded-lg border border-gray-100 p-3"
          >
            <div class="min-w-0">
              <p class="truncate text-sm font-medium text-gray-900">{{ wf.topic }}</p>
              <p class="text-xs text-gray-500">{{ wf.serviceName }}</p>
            </div>
            <div class="flex items-center gap-2 text-xs text-gray-400">
              <!-- /api/dashboard/overview's workflows[].steps is already the
                   step COUNT (DashboardController#getOverview maps
                   "steps" -> w.get("stepCount")), unlike Workflows.vue's
                   /api/dashboard/workflows where steps is the full array. -->
              <span>{{ wf.steps ?? '?' }} steps</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow.js'
import StatsCard from '@/components/StatsCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'

const store = useWorkflowStore()
const s = computed(() => store.stats || {})

let interval

onMounted(() => {
  store.fetchOverview()
  interval = setInterval(() => store.fetchOverview(), 5000)
})

onUnmounted(() => {
  clearInterval(interval)
})
</script>
