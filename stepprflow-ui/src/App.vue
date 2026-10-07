<template>
  <!-- Login (and any public route) renders standalone, without the app shell. -->
  <template v-if="isBareRoute">
    <router-view />
  </template>

  <div v-else class="flex min-h-screen">
    <Sidebar />
    <main class="flex-1 ml-64 p-8">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>

  <ToastContainer />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import ToastContainer from '@/components/ToastContainer.vue'

const route = useRoute()
const isBareRoute = computed(() => route.meta.public === true)
</script>

<style>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
