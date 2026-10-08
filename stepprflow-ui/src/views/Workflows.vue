<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Workflows</h1>

    <!-- Filters -->
    <div class="card-compact flex flex-wrap items-center gap-3">
      <input
        v-model="topicFilter"
        class="input max-w-[200px]"
        placeholder="Filter by topic..."
      />
      <input
        v-model="serviceFilter"
        class="input max-w-[200px]"
        placeholder="Filter by service..."
      />
      <select v-model="statusFilter" class="select max-w-[160px]">
        <option value="">All statuses</option>
        <option value="ACTIVE">Active</option>
        <option value="INACTIVE">Inactive</option>
      </select>
      <button class="btn-secondary btn-sm ml-auto" @click="clearFilters">Clear</button>
    </div>

    <!-- Grid -->
    <div v-if="filteredWorkflows.length === 0" class="card text-center text-sm text-gray-400 py-12">
      No workflows found
    </div>

    <div class="grid gap-4 lg:grid-cols-2">
      <div
        v-for="wf in filteredWorkflows"
        :key="wf.topic + wf.serviceName"
        class="card cursor-pointer transition-shadow hover:shadow-md"
        @click="openDetail(wf)"
      >
        <div class="flex items-start justify-between">
          <div class="min-w-0">
            <h3 class="truncate text-sm font-semibold text-gray-900">{{ wf.topic }}</h3>
            <p class="text-xs text-gray-500">{{ wf.serviceName }}</p>
          </div>
          <span
            v-if="wf.status"
            class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
            :class="wf.status === 'ACTIVE' ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-600'"
          >
            {{ wf.status }}
          </span>
        </div>

        <p v-if="wf.description" class="mt-1 text-xs text-gray-500">{{ wf.description }}</p>

        <div class="mt-3 flex items-center gap-4 text-xs text-gray-500">
          <span>{{ wf.steps?.length ?? '?' }} steps</span>
        </div>

        <!-- The API field is `registeredBy` (list of {serviceName, instanceId,
             host, port} per DashboardController#getCombinedWorkflowDefinitions),
             not `registeredServices`. -->
        <div v-if="wf.registeredBy?.length" class="mt-2 flex flex-wrap gap-1">
          <span
            v-for="svc in wf.registeredBy"
            :key="svc.serviceName + (svc.instanceId ?? '')"
            class="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600"
          >
            {{ svc.serviceName }}
          </span>
        </div>
      </div>
    </div>

    <!-- Steps modal -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="selectedWorkflow" class="fixed inset-0 z-50 flex items-center justify-center">
          <div class="fixed inset-0 bg-black/40" @click="selectedWorkflow = null" />
          <div class="relative z-10 w-full max-w-lg max-h-[80vh] overflow-auto rounded-xl bg-white p-6 shadow-2xl">
            <div class="flex items-center justify-between mb-4">
              <div>
                <h2 class="text-lg font-semibold text-gray-900">{{ selectedWorkflow.topic }}</h2>
                <p class="text-sm text-gray-500">{{ selectedWorkflow.serviceName }}</p>
              </div>
              <button
                class="flex h-8 w-8 items-center justify-center rounded-lg text-gray-400 hover:bg-gray-100 hover:text-gray-600"
                @click="selectedWorkflow = null"
              >
                <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div v-if="selectedWorkflow.steps?.length" class="space-y-2">
              <div
                v-for="(step, i) in selectedWorkflow.steps"
                :key="i"
                class="flex items-center gap-3 rounded-lg border border-gray-100 p-3"
              >
                <div class="flex h-7 w-7 items-center justify-center rounded-full bg-primary-100 text-xs font-semibold text-primary-700">
                  {{ i + 1 }}
                </div>
                <div class="min-w-0">
                  <p class="text-sm font-medium text-gray-900">{{ step.name || step.label || `Step ${i + 1}` }}</p>
                  <p v-if="step.description" class="text-xs text-gray-500">{{ step.description }}</p>
                </div>
              </div>
            </div>
            <p v-else class="text-sm text-gray-400">No step details available</p>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow.js'

const store = useWorkflowStore()

const topicFilter = ref('')
const serviceFilter = ref('')
const statusFilter = ref('')
const selectedWorkflow = ref(null)

const filteredWorkflows = computed(() => {
  return store.workflows.filter(wf => {
    if (topicFilter.value && !wf.topic?.toLowerCase().includes(topicFilter.value.toLowerCase())) return false
    if (serviceFilter.value && !wf.serviceName?.toLowerCase().includes(serviceFilter.value.toLowerCase())) return false
    if (statusFilter.value && wf.status !== statusFilter.value) return false
    return true
  })
})

function clearFilters() {
  topicFilter.value = ''
  serviceFilter.value = ''
  statusFilter.value = ''
}

function openDetail(wf) {
  selectedWorkflow.value = wf
}

onMounted(() => {
  store.fetchWorkflows()
})
</script>

<style>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
