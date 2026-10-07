<template>
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-medium text-gray-700">Payload</h3>
      <div v-if="canEdit" class="flex items-center gap-2">
        <button
          v-if="hasChanges"
          class="btn-secondary btn-sm"
          @click="$emit('restore')"
        >
          Restore original
        </button>
        <button
          class="btn-secondary btn-sm"
          :class="{ '!bg-primary-50 !text-primary-700 !border-primary-300': editMode }"
          @click="editMode = !editMode"
        >
          {{ editMode ? 'Done editing' : 'Edit' }}
        </button>
      </div>
      <span v-else class="text-xs text-gray-400" :title="operatorOnlyTitle">Read-only</span>
    </div>

    <div class="rounded-lg border border-gray-200 bg-gray-50 p-4 overflow-auto max-h-[600px]">
      <PayloadNode
        v-if="payload !== null && payload !== undefined"
        :value="payload"
        :path="''"
        :edit-mode="editMode"
        @update="onFieldUpdate"
      />
      <p v-else class="text-sm text-gray-400 italic">No payload</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import PayloadNode from './PayloadNode.vue'

defineProps({
  payload: { type: [Object, Array, String, Number, Boolean], default: null },
  hasChanges: { type: Boolean, default: false },
  // Editing payloads is a mutation — OPERATOR only. A VIEWER still sees the
  // payload, just without the Edit / Restore controls.
  canEdit: { type: Boolean, default: true }
})

const emit = defineEmits(['update', 'restore'])
const editMode = ref(false)
const operatorOnlyTitle = 'Action réservée aux opérateurs'

function onFieldUpdate(path, value) {
  emit('update', path, value)
}
</script>
