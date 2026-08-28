<template>
  <el-container class="main-layout">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="sidebar">
      <div class="logo">
        <el-icon size="22"><Microphone /></el-icon>
        <span v-show="!isCollapse">心声 · 管理后台</span>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="isCollapse"
        :router="true"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409eff"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="isCollapse = !isCollapse">
            <Fold v-if="!isCollapse" /><Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>心声</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag :type="rt.connected ? 'success' : 'danger'" size="small" effect="light">
            {{ rt.connected ? '实时已连接' : '未连接' }}
          </el-tag>
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="30"><el-icon><User /></el-icon></el-avatar>
              <span class="username">{{ userStore.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in"><component :is="Component" /></transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useRealtimeStore } from '@/stores/realtime'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const rt = useRealtimeStore()
const isCollapse = ref(false)

const menuItems = [
  { path: '/dashboard', title: '仪表盘', icon: 'Odometer' },
  { path: '/devices', title: '在线设备', icon: 'Cpu' },
  { path: '/sessions', title: '会话记录', icon: 'Notebook' },
  { path: '/logs', title: '实时日志', icon: 'List' }
]

function handleCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' }).then(() => {
      rt.disconnect(); userStore.logout(); router.push('/login')
    }).catch(() => {})
  }
}

onMounted(() => rt.connect())
onBeforeUnmount(() => rt.disconnect())
</script>

<style lang="scss" scoped>
.main-layout { height: 100vh; }
.sidebar {
  background: #304156; overflow-x: hidden;
  .logo { height: 60px; display: flex; align-items: center; justify-content: center; gap: 10px; color: #fff; font-size: 16px; font-weight: bold; border-bottom: 1px solid #3d4a5c; }
  .el-menu { border-right: none; }
}
.header {
  background: #fff; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; box-shadow: 0 1px 4px rgba(0,0,0,.08);
  .header-left { display: flex; align-items: center; gap: 15px;
    .collapse-btn { font-size: 20px; cursor: pointer; color: #606266; &:hover { color: #409eff; } } }
  .header-right { display: flex; align-items: center; gap: 16px;
    .user-info { display: flex; align-items: center; gap: 8px; cursor: pointer; .username { color: #606266; } } }
}
.main-content { background: #f5f7fa; padding: 20px; }
.fade-enter-active, .fade-leave-active { transition: opacity .2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
