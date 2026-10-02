<template>
  <div v-if="store.loading" class="flex items-center justify-center py-20">
    <div class="text-sm text-gray-400">Loading execution...</div>
  </div>

  <div v-else-if="!exec && store.error" class="flex items-center justify-center py-20">
    <div class="max-w-md text-center">
      <p class="text-lg font-medium text-gray-900">Unable to load execution</p>
      <p class="mt-2 text-sm text-gray-500">{{ store.error }}</p>
      <div class="mt-4 flex items-center justify-center gap-4">
        <button type="button" class="btn-secondary btn-sm" @click="retry">Retry</button>
        <router-link to="/executions" class="text-sm text-primary-600 hover:underline">Back to executions</router-link>
      </div>
    </div>
  </div>

  <div v-else-if="!exec" class="flex items-center justify-center py-20">
    <div class="text-center">
      <p class="text-lg font-medium text-gray-900">Execution not found</p>
      <router-link to="/executions" class="mt-2 text-sm text-primary-600 hover:underline">Back to executions</router-link>
    </div>
  </div>

  <div v-else class="space-y-6">
    <!-- Header -->
    <div class="flex items-start justify-between">
      <div>
        <router-link to="/executions" class="text-sm text-gray-500 hover:text-gray-700">&larr; Back</router-link>
        <h1 class="mt-1 text-2xl font-bold text-gray-900">{{ exec.topic }}</h1>
        <p class="mt-0.5 font-mono text-sm text-gray-500">{{ exec.executionId }}</p>
      </div>
      <div class="flex items-center gap-2">
        <StatusBadge :status="exec.status" />
        <button
          v-if="exec.status === 'FAILED' || exec.status === 'RETRY_PENDING'"
          class="btn-success btn-sm"
          @click="confirmAction('resume')"
        >
          Resume
        </button>
        <button
          v-if="exec.status === 'IN_PROGRESS' || exec.status === 'PENDING'"
          class="btn-danger btn-sm"
          @click="confirmAction('cancel')"
        >
          Cancel
        </button>
      </div>
    </div>

    <!-- Progress circle + info grid -->
    <div class="grid gap-6 lg:grid-cols-3">
      <!-- Circular progress -->
      <div class="card flex flex-col items-center justify-center">
        <svg class="h-32 w-32" viewBox="0 0 120 120">
          <circle cx="60" cy="60" r="52" stroke-width="8" fill="none" class="stroke-gray-100" />
          <circle
            cx="60" cy="60" r="52" stroke-width="8" fill="none"
            class="stroke-primary-500 transition-all duration-500"
            stroke-linecap="round"
            :stroke-dasharray="circumference"
            :stroke-dashoffset="circumference - (circumference * progressPct / 100)"
            transform="rotate(-90 60 60)"
          />
          <text x="60" y="55" text-anchor="middle" class="fill-gray-900 text-2xl font-bold" font-size="24">
            {{ exec.currentStep ?? 0 }}
          </text>
          <text x="60" y="75" text-anchor="middle" class="fill-gray-400 text-xs" font-size="12">
            of {{ exec.totalSteps ?? 0 }}
          </text>
        </svg>
        <p class="mt-2 text-sm text-gray-500">Step Progress</p>
      </div>

      <!-- Info -->
      <div class="card lg:col-span-2">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">Details</h2>
        <dl class="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
          <div>
            <dt class="text-gray-500">Correlation ID</dt>
            <dd class="font-mono text-gray-900">{{ exec.correlationId || '-' }}</dd>
          </div>
          <div>
            <dt class="text-gray-500">Current Step</dt>
            <dd class="text-gray-900">{{ exec.currentStep ?? '-' }}</dd>
          </div>
          <div>
            <dt class="text-gray-500">Created</dt>
            <dd class="text-gray-900">{{ formatDate(exec.createdAt) }}</dd>
          </div>
          <div>
            <dt class="text-gray-500">Updated</dt>
            <dd class="text-gray-900">{{ formatDate(exec.updatedAt) }}</dd>
          </div>
          <div>
            <dt class="text-gray-500">Duration</dt>
            <dd class="text-gray-900">{{ exec.durationMs ? (exec.durationMs / 1000).toFixed(1) + 's' : '-' }}</dd>
          </div>
          <div>
            <dt class="text-gray-500">Initiated By</dt>
            <dd class="text-gray-900">{{ exec.initiatedBy || '-' }}</dd>
          </div>
        </dl>
      </div>
    </div>

    <!-- Retry info -->
    <div v-if="exec.retryInfo" class="card">
      <h2 class="mb-3 text-sm font-semibold text-amber-700">Retry Information</h2>
      <dl class="grid grid-cols-3 gap-4 text-sm">
        <div>
          <dt class="text-gray-500">Attempt</dt>
          <dd class="font-medium text-gray-900">{{ exec.retryInfo.currentAttempt ?? '-' }}</dd>
        </div>
        <div>
          <dt class="text-gray-500">Max Retries</dt>
          <dd class="text-gray-900">{{ exec.retryInfo.maxRetries ?? '-' }}</dd>
        </div>
        <div>
          <dt class="text-gray-500">Next Retry</dt>
          <dd class="text-gray-900">{{ exec.retryInfo.nextRetryAt ? formatDate(exec.retryInfo.nextRetryAt) : '-' }}</dd>
        </div>
      </dl>
    </div>

    <!-- Error info -->
    <div v-if="exec.errorInfo" class="card border-red-200 bg-red-50">
      <h2 class="mb-3 text-sm font-semibold text-red-700">Error</h2>
      <p class="text-sm text-red-800">{{ exec.errorInfo.message || exec.errorInfo }}</p>
      <pre v-if="exec.errorInfo.stackTrace" class="mt-3 max-h-40 overflow-auto rounded bg-red-100 p-3 text-xs text-red-900">{{ exec.errorInfo.stackTrace }}</pre>
    </div>

    <!-- Payload editor -->
    <div class="card">
      <PayloadEditor
        :payload="exec.payload"
        :has-changes="hasPayloadChanges"
        @update="onPayloadUpdate"
        @restore="confirmRestore"
      />
    </div>

    <!-- Execution attempts -->
    <div v-if="exec.executionAttempts?.length" class="card">
      <h2 class="mb-4 text-sm font-semibold text-gray-900">Execution Attempts</h2>
      <div class="space-y-3">
        <div
          v-for="attempt in exec.executionAttempts"
          :key="attempt.attemptNumber"
          class="rounded-lg border border-gray-100 p-3"
        >
          <div class="flex items-center justify-between">
            <span class="text-sm font-medium text-gray-900">Attempt #{{ attempt.attemptNumber }}</span>
            <span class="text-xs text-gray-500">{{ formatDate(attempt.startedAt) }}</span>
          </div>
          <div class="mt-1 text-xs text-gray-500">
            Steps {{ attempt.startStep ?? '?' }} → {{ attempt.endStep ?? '?' }}
            <span v-if="attempt.result" class="ml-2 font-medium" :class="attempt.result === 'SUCCESS' ? 'text-emerald-600' : 'text-red-600'">
              {{ attempt.result }}
            </span>
          </div>
          <p v-if="attempt.errorMessage" class="mt-1 text-xs text-red-600">{{ attempt.errorMessage }}</p>
        </div>
      </div>
    </div>

    <!-- Step history timeline -->
    <div v-if="exec.stepHistory?.length" class="card">
      <h2 class="mb-4 text-sm font-semibold text-gray-900">Step History</h2>
      <div class="relative space-y-0">
        <div
          v-for="(step, i) in exec.stepHistory"
          :key="i"
          class="relative flex gap-4 pb-4"
        >
          <!-- Timeline line -->
          <div class="flex flex-col items-center">
            <div
              class="flex h-6 w-6 items-center justify-center rounded-full border-2"
              :class="stepIconClass(step)"
            >
              <span class="text-xs font-bold">{{ step.stepId ?? i + 1 }}</span>
            </div>
            <div v-if="i < exec.stepHistory.length - 1" class="w-px flex-1 bg-gray-200" />
          </div>
          <div class="flex-1 pb-2">
            <div class="flex items-center justify-between">
              <span class="text-sm font-medium text-gray-900">{{ step.stepLabel || `Step ${step.stepId}` }}</span>
              <StatusBadge :status="step.status" :dot="false" />
            </div>
            <div class="mt-0.5 text-xs text-gray-500">
              <span v-if="step.startedAt">{{ formatDate(step.startedAt) }}</span>
              <span v-if="step.durationMs" class="ml-2">{{ step.durationMs }}ms</span>
            </div>
            <p v-if="step.errorMessage" class="mt-1 text-xs text-red-600">{{ step.errorMessage }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Payload history -->
    <div v-if="exec.payloadHistory?.length" class="card">
      <h2 class="mb-4 text-sm font-semibold text-gray-900">Payload Changes</h2>
      <div class="space-y-2">
        <div
          v-for="(change, i) in exec.payloadHistory"
          :key="i"
          class="rounded-lg border border-gray-100 p-3 text-sm"
        >
          <div class="flex items-center justify-between">
            <code class="rounded bg-gray-100 px-1.5 py-0.5 text-xs">{{ change.fieldPath }}</code>
            <span class="text-xs text-gray-400">{{ formatDate(change.changedAt) }}</span>
          </div>
          <div class="mt-1 grid grid-cols-2 gap-2 text-xs">
            <div><span class="text-gray-400">From:</span> <span class="font-mono text-red-600">{{ change.oldValue }}</span></div>
            <div><span class="text-gray-400">To:</span> <span class="font-mono text-emerald-600">{{ change.newValue }}</span></div>
          </div>
          <p v-if="change.reason" class="mt-1 text-xs text-gray-500 italic">{{ change.reason }}</p>
        </div>
      </div>
    </div>

    <!-- Modals -->
    <ConfirmModal
      :show="confirmModal.show"
      :title="confirmModal.title"
      :message="confirmModal.message"
      :type="confirmModal.type"
      :confirm-label="confirmModal.confirmLabel"
      :loading="confirmModal.loading"
      @confirm="confirmModal.onConfirm"
      @cancel="confirmModal.show = false"
    />

    <ChangeReasonModal
      :show="changeModal.show"
      :field-path="changeModal.fieldPath"
      :old-value="changeModal.oldValue"
      :new-value="changeModal.newValue"
      :loading="changeModal.loading"
      @confirm="onChangeConfirmed"
      @cancel="changeModal.show = false"
    />
  </div>
</template>

<script setup>
import { computed, reactive, onMounted, onUnmounted } from 'vue'
import { format } from 'date-fns'
import { useWorkflowStore } from '@/stores/workflow.js'
import { useToastStore } from '@/stores/toast.js'
import StatusBadge from '@/components/StatusBadge.vue'
import PayloadEditor from '@/components/PayloadEditor.vue'
import ConfirmModal from '@/components/ConfirmModal.vue'
import ChangeReasonModal from '@/components/ChangeReasonModal.vue'

const props = defineProps({
  id: { type: String, required: true }
})

const store = useWorkflowStore()
const toast = useToastStore()
const exec = computed(() => store.currentExecution)

const circumference = 2 * Math.PI * 52

const progressPct = computed(() => {
  if (!exec.value?.totalSteps) return 0
  return Math.round((exec.value.currentStep / exec.value.totalSteps) * 100)
})

const hasPayloadChanges = computed(() => {
  return exec.value?.payloadHistory?.length > 0
})

const confirmModal = reactive({
  show: false, title: '', message: '', type: 'warning', confirmLabel: 'Confirm', loading: false, onConfirm: () => {}
})

const changeModal = reactive({
  show: false, fieldPath: '', oldValue: null, newValue: null, loading: false
})

function formatDate(d) {
  if (!d) return '-'
  return format(new Date(d), 'MMM d, HH:mm:ss')
}

function stepIconClass(step) {
  if (step.status === 'COMPLETED' || step.status === 'PASSED') return 'border-emerald-500 bg-emerald-50 text-emerald-700'
  if (step.status === 'FAILED') return 'border-red-500 bg-red-50 text-red-700'
  if (step.status === 'IN_PROGRESS') return 'border-blue-500 bg-blue-50 text-blue-700'
  return 'border-gray-300 bg-white text-gray-500'
}

function confirmAction(action) {
  if (action === 'resume') {
    confirmModal.title = 'Resume Execution'
    confirmModal.message = `Resume this execution?`
    confirmModal.type = 'info'
    confirmModal.confirmLabel = 'Resume'
    confirmModal.onConfirm = async () => {
      confirmModal.loading = true
      const ok = await store.resumeExecution(exec.value.executionId)
      confirmModal.loading = false
      // Only close the modal once we know the action actually succeeded.
      // On failure, keep it open: the global toast (bound to store.error)
      // already surfaces the API error (403/409/500...) to the user.
      if (ok) {
        confirmModal.show = false
        toast.push('success', 'Execution resumed successfully.')
      }
    }
  } else {
    confirmModal.title = 'Cancel Execution'
    confirmModal.message = `Cancel this execution? This cannot be undone.`
    confirmModal.type = 'danger'
    confirmModal.confirmLabel = 'Cancel Execution'
    confirmModal.onConfirm = async () => {
      confirmModal.loading = true
      const ok = await store.cancelExecution(exec.value.executionId)
      confirmModal.loading = false
      if (ok) {
        confirmModal.show = false
        toast.push('success', 'Execution cancelled.')
      }
    }
  }
  confirmModal.show = true
}

function confirmRestore() {
  confirmModal.title = 'Restore Payload'
  confirmModal.message = 'Restore the payload to its original state? All changes will be reverted.'
  confirmModal.type = 'warning'
  confirmModal.confirmLabel = 'Restore'
  confirmModal.onConfirm = async () => {
    confirmModal.loading = true
    const ok = await store.restorePayload(exec.value.executionId)
    confirmModal.loading = false
    if (ok) {
      confirmModal.show = false
      toast.push('success', 'Payload restored.')
    }
  }
  confirmModal.show = true
}

function onPayloadUpdate(path, newValue) {
  const oldValue = getNestedValue(exec.value.payload, path)
  changeModal.fieldPath = path
  changeModal.oldValue = oldValue
  changeModal.newValue = newValue
  changeModal.show = true
}

async function onChangeConfirmed(reason) {
  changeModal.loading = true
  const ok = await store.updatePayloadField(exec.value.executionId, changeModal.fieldPath, changeModal.newValue, reason)
  changeModal.loading = false
  if (ok) {
    changeModal.show = false
    toast.push('success', 'Payload field updated.')
  }
}

function retry() {
  store.fetchExecution(props.id)
}

function getNestedValue(obj, path) {
  return path.split('.').reduce((o, k) => o?.[k], obj)
}

let interval

onMounted(() => {
  store.fetchExecution(props.id)
  interval = setInterval(() => {
    if (exec.value && (exec.value.status === 'IN_PROGRESS' || exec.value.status === 'PENDING')) {
      store.fetchExecution(props.id)
    }
  }, 3000)
})

onUnmounted(() => {
  clearInterval(interval)
  store.currentExecution = null
})
</script>
