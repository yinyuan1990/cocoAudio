<template>
  <div>
    <div class="page-title">在线设备</div>
    <el-card shadow="never">
      <el-table :data="rt.stats.devices" empty-text="暂无在线设备（relay 模式下设备注册后显示）" stripe>
        <el-table-column prop="device_id" label="设备 ID" width="160">
          <template #default="{ row }"><span class="mono">{{ row.device_id }}</span></template>
        </el-table-column>
        <el-table-column label="在线时长" width="140">
          <template #default="{ row }">{{ since(row.since) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.inCall ? 'warning' : 'success'" effect="light">{{ row.inCall ? '通话中' : '空闲' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作">
          <template #default="{ row }">
            <el-button size="small" @click="cmd(row.device_id, { type: 'wifi_scan' })">WiFi扫描</el-button>
            <el-button size="small" @click="askVolume(row.device_id)">音量</el-button>
            <el-button size="small" @click="cmd(row.device_id, { type: 'switch_network', mode: 'wifi' })">切WiFi</el-button>
            <el-button size="small" @click="cmd(row.device_id, { type: 'switch_network', mode: '4g' })">切4G</el-button>
            <el-button size="small" type="warning" @click="cmd(row.device_id, { type: 'factory_reset' })">复位</el-button>
            <el-button size="small" type="danger" @click="kick(row.device_id)">踢下线</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRealtimeStore } from '@/stores/realtime'
import { sendCommand, kickDevice } from '@/api/admin'

const rt = useRealtimeStore()
function since(ts) { if (!ts) return '-'; const s = Math.floor((Date.now() - ts) / 1000), m = Math.floor(s / 60), sec = s % 60; return m > 0 ? `${m}分${sec}秒` : `${sec}秒` }
async function cmd(device_id, payload) { await sendCommand({ device_id, ...payload }); ElMessage.success('指令已下发') }
async function askVolume(id) {
  const { value } = await ElMessageBox.prompt('设置咪头音量 (40/60/80/100)', '音量', { inputValue: '80' }).catch(() => ({}))
  if (value) cmd(id, { type: 'set_volume', volume: Number(value) })
}
async function kick(id) {
  await ElMessageBox.confirm(`确定踢下线设备 ${id}？`, '提示', { type: 'warning' }).catch(() => { throw 0 }).then(async () => {
    await kickDevice(id); ElMessage.success('已断开'); setTimeout(rt.refresh, 300)
  }).catch(() => {})
}
</script>

<style scoped>
.mono { font-family: 'SF Mono', Consolas, monospace; font-weight: 700; }
</style>
