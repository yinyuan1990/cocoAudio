<template>
  <div>
    <div class="page-title">实时日志</div>
    <el-card shadow="never">
      <div class="logs">
        <div v-if="!rt.logs.length" class="empty">暂无日志</div>
        <div v-for="(l, i) in rt.logs" :key="i" class="line">
          <span class="lt">{{ fmtTime(l.time) }}</span><span class="lx">{{ l.text }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { useRealtimeStore } from '@/stores/realtime'
const rt = useRealtimeStore()
function fmtTime(ts) { return new Date(ts).toLocaleTimeString() }
</script>

<style scoped>
.logs { max-height: 70vh; overflow: auto; background: #0e1526; border-radius: 10px; padding: 16px; font-family: 'SF Mono', Consolas, monospace; font-size: 12px; }
.line { display: flex; gap: 12px; padding: 3px 0; }
.lt { color: #5f6b85; flex: none; }
.lx { color: #e6edff; }
.empty { color: #5f6b85; text-align: center; padding: 20px; }
</style>
