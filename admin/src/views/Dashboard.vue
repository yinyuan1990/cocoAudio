<template>
  <div>
    <div class="page-title">仪表盘</div>
    <el-row :gutter="16">
      <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="stat"><div class="k">在线设备</div><div class="v">{{ rt.stats.deviceCount }}</div></div></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="stat"><div class="k">App 连接</div><div class="v">{{ rt.stats.appCount }}</div></div></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="stat"><div class="k">进行中通话</div><div class="v">{{ rt.stats.activeCalls }}</div></div></el-card></el-col>
      <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="stat"><div class="k">运行时长</div><div class="v small">{{ uptime }}</div></div></el-card></el-col>
    </el-row>

    <el-card shadow="never" class="mt">
      <template #header><div class="card-head"><span>服务器模式</span></div></template>
      <div class="mode-row">
        <el-radio-group :model-value="rt.stats.mode" @change="onMode">
          <el-radio-button value="echo">echo 回环自测</el-radio-button>
          <el-radio-button value="relay">relay 中转对讲</el-radio-button>
        </el-radio-group>
        <span class="hint">echo：语音原样回传单端自测；relay：App 与设备之间转发（接硬件用）</span>
      </div>
    </el-card>

    <el-card shadow="never" class="mt">
      <template #header><span>服务器信息</span></template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="WebSocket 地址">ws://8.162.5.160:40000</el-descriptions-item>
        <el-descriptions-item label="当前模式">{{ rt.stats.mode }}</el-descriptions-item>
        <el-descriptions-item label="端口">{{ rt.stats.port }}</el-descriptions-item>
        <el-descriptions-item label="实时连接">{{ rt.connected ? '已连接' : '未连接' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useRealtimeStore } from '@/stores/realtime'
import { setMode } from '@/api/admin'

const rt = useRealtimeStore()
const uptime = computed(() => {
  const s = rt.stats.uptimeSec || 0, h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  return `${h}h ${m}m ${sec}s`
})
async function onMode(mode) {
  await setMode(mode); rt.refresh(); ElMessage.success(`已切换为 ${mode}`)
}
</script>

<style lang="scss" scoped>
.stat { .k { font-size: 13px; color: #909399; } .v { font-size: 30px; font-weight: 800; margin-top: 8px; color: #303133; } .v.small { font-size: 20px; } }
.mt { margin-top: 16px; }
.mode-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; .hint { color: #909399; font-size: 12px; } }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
