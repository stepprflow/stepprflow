import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { dashboardApi, executionApi } from '@/services/api.js'

export const useWorkflowStore = defineStore('workflow', () => {
  const stats = ref(null)
  const executions = ref([])
  const recentExecutions = ref([])
  const workflows = ref([])
  const currentExecution = ref(null)
  const loading = ref(false)
  const error = ref(null)

  const pagination = ref({
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0
  })

  const filters = ref({
    search: '',
    topic: '',
    status: [],
    sortBy: 'createdAt',
    sortDir: 'desc'
  })

  const hasNextPage = computed(() => pagination.value.page < pagination.value.totalPages - 1)
  const hasPrevPage = computed(() => pagination.value.page > 0)

  function clearError() {
    error.value = null
  }

  async function fetchOverview() {
    try {
      const data = await dashboardApi.getOverview()
      stats.value = data.stats
      recentExecutions.value = data.recentExecutions || []
      workflows.value = data.workflows || []
    } catch (e) {
      error.value = e.message
    }
  }

  async function fetchStats() {
    try {
      stats.value = await executionApi.stats()
    } catch (e) {
      error.value = e.message
    }
  }

  async function fetchExecutions() {
    loading.value = true
    error.value = null
    try {
      const params = {
        page: pagination.value.page,
        size: pagination.value.size,
        sort: filters.value.sortBy,
        direction: filters.value.sortDir
      }
      if (filters.value.search) params.search = filters.value.search
      if (filters.value.topic) params.topic = filters.value.topic
      if (filters.value.status.length) params.status = filters.value.status.join(',')

      const data = await executionApi.list(params)
      executions.value = data.content || []
      pagination.value.totalElements = data.totalElements ?? 0
      pagination.value.totalPages = data.totalPages ?? 0
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function fetchRecentExecutions() {
    try {
      recentExecutions.value = await executionApi.recent()
    } catch (e) {
      error.value = e.message
    }
  }

  async function fetchExecution(id) {
    // Only show loading spinner on initial load, not on polling refreshes
    if (!currentExecution.value) {
      loading.value = true
    }
    error.value = null
    try {
      currentExecution.value = await executionApi.get(id)
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function fetchWorkflows(params) {
    try {
      workflows.value = await dashboardApi.getWorkflows(params)
    } catch (e) {
      error.value = e.message
    }
  }

  async function resumeExecution(id) {
    try {
      await executionApi.resume(id)
      if (currentExecution.value?.executionId === id) {
        await fetchExecution(id)
      }
      return true
    } catch (e) {
      error.value = e.message
      return false
    }
  }

  async function cancelExecution(id) {
    try {
      await executionApi.cancel(id)
      if (currentExecution.value?.executionId === id) {
        await fetchExecution(id)
      }
      return true
    } catch (e) {
      error.value = e.message
      return false
    }
  }

  async function updatePayloadField(id, fieldPath, newValue, reason) {
    try {
      currentExecution.value = await executionApi.updatePayloadField(id, fieldPath, newValue, reason)
      return true
    } catch (e) {
      error.value = e.message
      return false
    }
  }

  async function restorePayload(id) {
    try {
      currentExecution.value = await executionApi.restorePayload(id)
      return true
    } catch (e) {
      error.value = e.message
      return false
    }
  }

  function setPage(page) {
    pagination.value.page = page
  }

  function setFilters(newFilters) {
    Object.assign(filters.value, newFilters)
    pagination.value.page = 0
  }

  return {
    stats,
    executions,
    recentExecutions,
    workflows,
    currentExecution,
    loading,
    error,
    pagination,
    filters,
    hasNextPage,
    hasPrevPage,
    clearError,
    fetchOverview,
    fetchStats,
    fetchExecutions,
    fetchRecentExecutions,
    fetchExecution,
    fetchWorkflows,
    resumeExecution,
    cancelExecution,
    updatePayloadField,
    restorePayload,
    setPage,
    setFilters
  }
})
