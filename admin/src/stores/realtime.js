import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { getStats, getSessions } from '@/api/admin'
import { useUserStore } from './user'

export const useRealtimeStore = defineStore('realtime', () => {
  const connected = ref(false)
  const stats = reactive({ mode: '-', deviceCount: 0, appCount: 0, activeCalls: 0, uptimeSec: 0, devices: [] })
  const logs = ref([])
  const sessions = ref([])
  let ws = null
  let poll = null

  async function refresh() {
    try { Object.assign(stats, await getStats()) } catch (e) {}
    try { sessions.value = await getSessions() } catch (e) {}
  }

  function connect() {
    const userStore = useUserStore()
    const wsBase = location.origin.replace(/^http/, 'ws')
    if (ws) ws.close()
    try {
      ws = new WebSocket(`${wsBase}/admin?token=${userStore.token}`)
      ws.onopen = () => { connected.value = true }
      ws.onclose = () => { connected.value = false }
      ws.onmessage = (ev) => {
        const msg = JSON.parse(ev.data)
        if (msg.type === 'hello') { Object.assign(stats, msg.stats); logs.value = msg.logs.slice().reverse() }
        else if (msg.type === 'log') { logs.value.unshift({ time: msg.time, text: msg.text }); if (logs.value.length > 300) logs.value.pop() }
        else if (msg.type === 'event') { refresh() }
      }
    } catch (e) {}
    refresh()
    if (!poll) poll = setInterval(refresh, 3000)
  }

  function disconnect() { if (ws) ws.close(); ws = null; if (poll) { clearInterval(poll); poll = null } connected.value = false }

  return { connected, stats, logs, sessions, connect, disconnect, refresh }
})
