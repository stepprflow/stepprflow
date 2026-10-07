<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="show" class="fixed inset-0 z-50 flex items-center justify-center">
        <div class="fixed inset-0 bg-black/40" @click="!loading && $emit('cancel')" />
        <div
          ref="dialogRef"
          class="relative z-10 w-full max-w-md rounded-xl bg-white p-6 shadow-2xl"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="titleId"
          @keydown.esc="!loading && $emit('cancel')"
          @keydown.tab="onTabKey"
        >
          <!-- Icon -->
          <div class="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full" :class="iconBg">
            <svg class="h-6 w-6" :class="iconColor" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" :d="iconPath" />
            </svg>
          </div>

          <h3 :id="titleId" class="text-center text-lg font-semibold text-gray-900">{{ title }}</h3>
          <p class="mt-2 text-center text-sm text-gray-500">{{ message }}</p>

          <div class="mt-6 flex gap-3">
            <button class="btn-secondary flex-1" :disabled="loading" @click="$emit('cancel')">Cancel</button>
            <button
              ref="confirmBtnRef"
              class="flex-1"
              :class="confirmClass"
              :disabled="loading"
              @click="$emit('confirm')"
            >
              {{ loading ? 'Please wait…' : confirmLabel }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useModalFocusTrap } from '@/composables/useModalFocusTrap.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  title: { type: String, default: 'Confirm' },
  message: { type: String, default: 'Are you sure?' },
  type: { type: String, default: 'warning' },
  confirmLabel: { type: String, default: 'Confirm' },
  // Disables the actions and shows a busy state while the confirmed
  // action is in flight, so the modal can only close once the caller
  // knows whether the action actually succeeded.
  loading: { type: Boolean, default: false }
})

defineEmits(['confirm', 'cancel'])

const dialogRef = ref(null)
const confirmBtnRef = ref(null)
const titleId = `confirm-modal-title-${Math.random().toString(36).slice(2, 9)}`

const { onTabKey } = useModalFocusTrap(() => props.show, dialogRef, confirmBtnRef)

const typeConfig = {
  info: {
    iconBg: 'bg-blue-100',
    iconColor: 'text-blue-600',
    iconPath: 'M11.25 11.25l.041-.02a.75.75 0 011.063.852l-.708 2.836a.75.75 0 001.063.853l.041-.021M21 12a9 9 0 11-18 0 9 9 0 0118 0zm-9-3.75h.008v.008H12V8.25z',
    confirmClass: 'btn-primary'
  },
  warning: {
    iconBg: 'bg-amber-100',
    iconColor: 'text-amber-600',
    iconPath: 'M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z',
    confirmClass: 'btn-primary'
  },
  danger: {
    iconBg: 'bg-red-100',
    iconColor: 'text-red-600',
    iconPath: 'M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z',
    confirmClass: 'btn-danger'
  }
}

const config = computed(() => typeConfig[props.type] || typeConfig.warning)
const iconBg = computed(() => config.value.iconBg)
const iconColor = computed(() => config.value.iconColor)
const iconPath = computed(() => config.value.iconPath)
const confirmClass = computed(() => config.value.confirmClass)
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
