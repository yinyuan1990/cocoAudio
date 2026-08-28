<template>
  <div>
    <div class="page-title">会话记录 <span class="sub">每个连接一条，用于排查硬件问题（含 ADPCM 语音帧校验）</span></div>
    <el-card shadow="never">
      <el-table :data="rt.sessions" empty-text="暂无会话" stripe>
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column label="角色" width="90">
          <template #default="{ row }"><el-tag :type="row.role === 'device' ? 'warning' : 'primary'" effect="light">{{ roleLabel(row.role) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="设备/地址" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.deviceId || row.addr }}</span></template>
        </el-table-column>
        <el-table-column label="连接时间" width="110">
          <template #default="{ row }">{{ fmtTime(row.connectedAt) }}</template>
        </el-table-column>
        <el-table-column label="时长" width="90">
          <template #default="{ row }">{{ row.live ? '在线' : dur(row) }}</template>
        </el-table-column>
        <el-table-column label="收音/异常" width="110">
          <template #default="{ row }"><span :class="{ bad: row.audioBad > 0 }">{{ row.audioIn }} / {{ row.audioBad }}</span></template>
        </el-table-column>
        <el-table-column label="发音" width="80">
          <template #default="{ row }">{{ row.audioOut }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="row.live ? 'success' : 'info'" size="small">{{ row.live ? '在线' : '已结束' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button size="small" @click="open(row.id)">详情</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="show" :title="`会话 #${detail?.id || ''} · ${roleLabel(detail?.role)} · ${detail?.deviceId || detail?.addr || ''}`" width="720px">
      <div class="evlist">
        <div v-for="(e, i) in detail?.events || []" :key="i" class="evline" :class="e.dir">
          <span class="lt">{{ fmtTime(e.t) }}</span>
          <el-tag size="small" :type="dirType(e.dir)" effect="plain">{{ e.dir }}</el-tag>
          <span class="kind">{{ e.kind }}</span>
          <span class="detail">{{ e.detail }}</span>
        </div>
        <div v-if="!detail?.events?.length" class="empty">无事件</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRealtimeStore } from '@/stores/realtime'
import { getSession } from '@/api/admin'

const rt = useRealtimeStore()
const show = ref(false)
const detail = ref(null)

function roleLabel(r) { return r === 'device' ? '设备' : r === 'app' ? 'App' : '未知' }
function fmtTime(ts) { return ts ? new Date(ts).toLocaleTimeString() : '-' }
function dur(s) { return s.disconnectedAt ? Math.round((s.disconnectedAt - s.connectedAt) / 1000) + '秒' : '-' }
function dirType(d) { return d === 'in' ? 'primary' : d === 'out' ? 'success' : 'warning' }
async function open(id) { detail.value = await getSession(id); show.value = true }
</script>

<style scoped>
.sub { font-size: 12px; color: #909399; font-weight: 400; }
.mono { font-family: 'SF Mono', Consolas, monospace; font-weight: 700; }
.bad { color: #f56c6c; font-weight: 700; }
.evlist { max-height: 60vh; overflow: auto; font-family: 'SF Mono', Consolas, monospace; font-size: 12px; }
.evline { display: grid; grid-template-columns: 84px 56px 130px 1fr; gap: 8px; align-items: center; padding: 5px 0; border-bottom: 1px solid #f0f2f5; }
.evline .lt { color: #909399; }
.evline .kind { font-weight: 600; color: #303133; }
.evline .detail { color: #606266; word-break: break-all; }
.empty { color: #909399; text-align: center; padding: 20px; }
</style>
