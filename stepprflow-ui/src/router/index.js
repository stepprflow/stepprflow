import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue')
  },
  {
    path: '/executions',
    name: 'Executions',
    component: () => import('@/views/Executions.vue')
  },
  {
    path: '/executions/:id',
    name: 'ExecutionDetail',
    component: () => import('@/views/ExecutionDetail.vue'),
    props: true
  },
  {
    path: '/workflows',
    name: 'Workflows',
    component: () => import('@/views/Workflows.vue')
  },
  {
    path: '/metrics',
    name: 'Metrics',
    component: () => import('@/views/Metrics.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// Global auth guard. On first navigation it loads the auth config + current
// user once; thereafter it gates protected routes. The store is imported
// lazily to avoid a load-time cycle (auth store -> router -> this module).
router.beforeEach(async (to) => {
  const { useAuthStore } = await import('@/stores/auth.js')
  const auth = useAuthStore()

  if (!auth.initialized) {
    await auth.init()
  }

  // Already signed in users have no business on the login screen.
  if (to.name === 'Login') {
    return auth.authenticated ? { name: 'Dashboard' } : true
  }

  // Protected route without a session -> login, remembering where we headed.
  if (!auth.authenticated) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }

  return true
})

export default router
