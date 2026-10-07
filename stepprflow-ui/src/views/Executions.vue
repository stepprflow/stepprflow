<template>
  <div class="space-y-6">
    <h1 class="text-2xl font-bold text-gray-900">Executions</h1>

    <!-- Filters -->
    <div class="card-compact flex flex-wrap items-center gap-3">
      <input
        v-model="searchQuery"
        class="input max-w-xs"
        placeholder="Search by ID or topic..."
      />
      <input
        v-model="topicFilter"
        class="input max-w-[180px]"
        placeholder="Filter by topic..."
      />
      <div class="flex flex-wrap gap-1.5">
        <button
          v-for="s in allStatuses"
          :key="s"
          class="rounded-full border px-2.5 py-1 text-xs font-medium transition-colors"
          :class="selectedStatuses.includes(s)
            ? 'border-primary-300 bg-primary-50 text-primary-700'
            : 'border-gray-200 bg-white text-gray-500 hover:border-gray-300'"
          @click="toggleStatus(s)"
        >
          {{ s }}
        </button>
      </div>
      <button class="btn-secondary btn-sm ml-auto" @click="clearFilters">Clear</button>
    </div>

    <!-- Table -->
    <div class="card overflow-hidden !p-0">
      <div v-if="store.loading" class="p-8 text-center text-sm text-gray-400">Loading...</div>
      <table v-else class="w-full text-sm">
        <thead>
          <tr class="border-b border-gray-100 bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
            <th class="px-4 py-3">Execution ID</th>
            <th class="px-4 py-3">Topic</th>
            <th class="px-4 py-3">Status</th>
            <th class="px-4 py-3">Progress</th>
            <th class="px-4 py-3">Created</th>
            <th class="px-4 py-3 text-right">Actions</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-50">
          <tr v-if="store.executions.length === 0">
            <td colspan="6" class="px-4 py-8 text-center text-gray-400">No executions found</td>
          </tr>
          <tr
            v-for="exec in store.executions"
            :key="exec.executionId"
            class="hover:bg-gray-50 transition-colors cursor-pointer"
            @click="$router.push(`/executions/${exec.executionId}`)"
          >
            <td class="px-4 py-3 font-mono text-xs text-gray-600 max-w-[200px] truncate">
              {{ exec.executionId }}
            </td>
            <td class="px-4 py-3 font-medium text-gray-900">{{ exec.topic }}</td>
            <td class="px-4 py-3">
              <StatusBadge :status="exec.status" />
            </td>
            <td class="px-4 py-3 text-gray-500">
              <div class="flex items-center gap-2">
                <div class="h-1.5 w-16 rounded-full bg-gray-100 overflow-hidden">
                  <div
                    class="h-full rounded-full bg-primary-500 transition-all"
                    :style="{ width: progressPct(exec) + '%' }"
                  />
                </div>
                <span class="text-xs">{{ exec.currentStep ?? 0 }}/{{ exec.totalSteps ?? 0 }}</span>
              </div>
            </td>
            <td class="px-4 py-3 text-xs text-gray-500">{{ formatDate(exec.createdAt) }}</td>
            <td class="px-4 py-3 text-right" @click.stop>
              <button
                v-if="exec.status === 'FAILED' || exec.status === 'RETRY_PENDING'"
                class="btn-success btn-sm mr-1"
                :disabled="!auth.isOperator"
                :title="auth.isOperator ? '' : operatorOnlyTitle"
                @click="confirmResume(exec)"
              >
                Resume
              </button>
              <button
                v-if="exec.status === 'IN_PROGRESS' || exec.status === 'PENDING'"
                class="btn-danger btn-sm"
                :disabled="!auth.isOperator"
                :title="auth.isOperator ? '' : operatorOnlyTitle"
                @click="confirmCancel(exec)"
              >
                Cancel
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div v-if="store.pagination.totalPages > 1" class="flex items-center justify-between">
      <span class="text-sm text-gray-500">
        {{ store.pagination.totalElements }} results — page {{ store.pagination.page + 1 }} of {{ store.pagination.totalPages }}
      </span>
      <div class="flex gap-2">
        <button class="btn-secondary btn-sm" :disabled="!store.hasPrevPage" @click="changePage(-1)">Previous</button>
        <button class="btn-secondary btn-sm" :disabled="!store.hasNextPage" @click="changePage(1)">Next</button>
      </div>
    </div>

    <ConfirmModal
      :show="modal.show"
      :title="modal.title"
      :message="modal.message"
      :type="modal.type"
      :confirm-label="modal.confirmLabel"
      @confirm="modal.onConfirm"
      @cancel="modal.show = false"
    />
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { format } from 'date-fns'
import { useWorkflowStore } from '@/stores/workflow.js'
import { useAuthStore } from '@/stores/auth.js'
import StatusBadge from '@/components/StatusBadge.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'

const store = useWorkflowStore()
const auth = useAuthStore()
const operatorOnlyTitle = 'Action réservée aux opérateurs'

const allStatuses = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'RETRY_PENDING', 'CANCELLED']
const searchQuery = ref(store.filters.search)
const topicFilter = ref(store.filters.topic)
const selectedStatuses = ref([...store.filters.status])

const modal = reactive({
  show: false,
  title: '',
  message: '',
  type: 'warning',
  confirmLabel: 'Confirm',
  onConfirm: () => {}
})

let searchTimeout
watch(searchQuery, (val) => {
  clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    store.setFilters({ search: val })
    store.fetchExecutions()
  }, 300)
})

watch(topicFilter, (val) => {
  store.setFilters({ topic: val })
  store.fetchExecutions()
})

watch(selectedStatuses, (val) => {
  store.setFilters({ status: [...val] })
  store.fetchExecutions()
}, { deep: true })

function toggleStatus(s) {
  const idx = selectedStatuses.value.indexOf(s)
  if (idx >= 0) selectedStatuses.value.splice(idx, 1)
  else selectedStatuses.value.push(s)
}

function clearFilters() {
  searchQuery.value = ''
  topicFilter.value = ''
  selectedStatuses.value = []
}

function progressPct(exec) {
  if (!exec.totalSteps) return 0
  return Math.round((exec.currentStep / exec.totalSteps) * 100)
}

function formatDate(d) {
  if (!d) return '-'
  return format(new Date(d), 'MMM d, HH:mm:ss')
}

function changePage(delta) {
  store.setPage(store.pagination.page + delta)
  store.fetchExecutions()
}

function confirmResume(exec) {
  modal.title = 'Resume Execution'
  modal.message = `Resume execution ${exec.executionId} for topic "${exec.topic}"?`
  modal.type = 'info'
  modal.confirmLabel = 'Resume'
  modal.onConfirm = async () => {
    modal.show = false
    await store.resumeExecution(exec.executionId)
    store.fetchExecutions()
  }
  modal.show = true
}

function confirmCancel(exec) {
  modal.title = 'Cancel Execution'
  modal.message = `Cancel execution ${exec.executionId}? This cannot be undone.`
  modal.type = 'danger'
  modal.confirmLabel = 'Cancel Execution'
  modal.onConfirm = async () => {
    modal.show = false
    await store.cancelExecution(exec.executionId)
    store.fetchExecutions()
  }
  modal.show = true
}

onMounted(() => {
  store.fetchExecutions()
})
</script>
