<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center">
        <div class="fixed inset-0 bg-black/40" @click="!loading && $emit('cancel')" />
        <div
          ref="dialogRef"
          class="relative z-10 w-full max-w-lg rounded-xl bg-white p-6 shadow-2xl"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="titleId"
          @keydown.esc="!loading && $emit('cancel')"
          @keydown.tab="onTabKey"
        >
          <h3 :id="titleId" class="text-lg font-semibold text-gray-900">Change Reason</h3>
          <p class="mt-1 text-sm text-gray-500">Provide a reason for this payload change.</p>

          <!-- Change summary -->
          <div class="mt-4 rounded-lg bg-gray-50 border border-gray-200 p-3 text-sm">
            <div class="flex items-center gap-2 text-gray-600">
              <span class="font-medium">Field:</span>
              <code class="rounded bg-gray-200 px-1.5 py-0.5 text-xs font-mono">{{ fieldPath }}</code>
            </div>
            <div class="mt-1.5 grid grid-cols-2 gap-2 text-xs">
              <div>
                <span class="text-gray-400">From:</span>
                <span class="ml-1 font-mono text-red-600">{{ formatValue(oldValue) }}</span>
              </div>
              <div>
                <span class="text-gray-400">To:</span>
                <span class="ml-1 font-mono text-emerald-600">{{ formatValue(newValue) }}</span>
              </div>
            </div>
          </div>

          <!-- Reason input -->
          <textarea
            ref="reasonInput"
            v-model="reason"
            class="input mt-4 min-h-[80px] resize-y"
            placeholder="Why are you making this change?"
            :disabled="loading"
            @keydown.meta.enter="submit"
            @keydown.ctrl.enter="submit"
          />

          <div class="mt-4 flex gap-3">
            <button class="btn-secondary flex-1" :disabled="loading" @click="$emit('cancel')">Cancel</button>
            <button
              class="btn-primary flex-1"
              :disabled="!reason.trim() || loading"
              @click="submit"
            >
              {{ loading ? 'Applying…' : 'Apply Change' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  fieldPath: { type: String, default: '' },
  oldValue: { default: null },
  newValue: { default: null },
  // Disables inputs/actions while the confirmed change is being submitted.
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['confirm', 'cancel'])

const reason = ref('')
const reasonInput = ref(null)
const dialogRef = ref(null)
const titleId = `change-reason-modal-title-${Math.random().toString(36).slice(2, 9)}`

const { onTabKey } = useModalFocusTrap(() => props.show, dialogRef, reasonInput)

watch(() => props.show, (val) => {
  if (val) {
    reason.value = ''
  }
})

function formatValue(val) {
  if (val === null || val === undefined) return 'null'
  if (typeof val === 'string') return `"${val}"`
  return String(val)
}

function submit() {
  if (reason.value.trim() && !props.loading) {
    emit('confirm', reason.value.trim())
  }
}
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
