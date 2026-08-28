<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'

// 服务器地址：从服务器托管打开时用当前源，本地 dev(5173) 用线上默认
const defaultBase = (typeof location !== 'undefined' && location.port !== '5173' && location.protocol.startsWith('http'))
  ? location.origin : 'http://8.162.5.160:40000'
const base = ref(localStorage.getItem('xs_base') || defaultBase)
const token = ref(localStorage.getItem('xs_token') || '')
const authed = computed(() => !!token.value)

const loginForm = reactive({ user: 'admin', pass: '' })
const loginErr = ref('')

const connected = ref(false)
const stats = reactive({ mode: '-', deviceCount: 0, appCount: 0, activeCalls: 0, uptimeSec: 0, devices: [] })
const logs = ref([])
const sessions = ref([])
const sessionDetail = ref(null)
let ws = null, poll = null

const apiUrl = (p) => base.value.replace(/\/$/, '') + p
const wsUrl = computed(() => base.value.replace(/^http/, 'ws').replace(/\/$/, '') + '/admin?token=' + token.value)
const hdr = () => ({ 'Content-Type': 'application/json', 'x-token': token.value })

const uptimeText = computed(() => {
  const s = stats.uptimeSec, h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  return `${h}h ${m}m ${sec}s`
})

async function api(path, opts = {}) {
  const r = await fetch(apiUrl(path), { ...opts, headers: { ...hdr(), ...(opts.headers || {}) } })
  if (r.status === 401) { logout(); throw new Error('unauthorized') }
  return r
}

async function login() {
  loginErr.value = ''
  try {
    const r = await fetch(apiUrl('/api/login'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user: loginForm.user, pass: loginForm.pass }) })
    const j = await r.json()
    if (j.ok) { token.value = j.token; localStorage.setItem('xs_token', j.token); localStorage.setItem('xs_base', base.value); start() }
    else loginErr.value = j.error || '登录失败'
  } catch (e) { loginErr.value = '无法连接服务器，请检查地址' }
}
function logout() { token.value = ''; localStorage.removeItem('xs_token'); if (ws) ws.close(); connected.value = false }

async function fetchStats() { try { Object.assign(stats, await (await api('/api/stats')).json()) } catch (e) {} }
async function fetchSessions() { try { sessions.value = await (await api('/api/sessions')).json() } catch (e) {} }
async function openSession(id) { try { sessionDetail.value = await (await api('/api/session?id=' + id)).json() } catch (e) {} }
async function setMode(mode) { await api('/api/mode', { method: 'POST', body: JSON.stringify({ mode }) }); fetchStats() }
async function cmd(device_id, payload) { await api('/api/command', { method: 'POST', body: JSON.stringify({ device_id, ...payload }) }) }
async function kick(device_id) { await api('/api/kick', { method: 'POST', body: JSON.stringify({ device_id }) }); setTimeout(fetchStats, 300) }
function askVolume(id) { const v = prompt('设置咪头音量 (40/60/80/100)', '80'); if (v) cmd(id, { type: 'set_volume', volume: Number(v) }) }

function connectAdmin() {
  if (ws) ws.close()
  try {
    ws = new WebSocket(wsUrl.value)
    ws.onopen = () => { connected.value = true }
    ws.onclose = () => { connected.value = false }
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.type === 'hello') { Object.assign(stats, msg.stats); logs.value = msg.logs.slice().reverse() }
      else if (msg.type === 'log') { logs.value.unshift({ time: msg.time, text: msg.text }); if (logs.value.length > 200) logs.value.pop() }
      else if (msg.type === 'event') { fetchStats(); fetchSessions() }
    }
  } catch (e) {}
}

function start() { fetchStats(); fetchSessions(); connectAdmin(); if (!poll) poll = setInterval(() => { fetchStats(); fetchSessions() }, 3000) }

function fmtSince(ts) { if (!ts) return '-'; const s = Math.floor((Date.now() - ts) / 1000), m = Math.floor(s / 60), sec = s % 60; return m > 0 ? `${m}分${sec}秒` : `${sec}秒` }
function fmtDur(s) { if (!s.disconnectedAt) return '在线'; return Math.round((s.disconnectedAt - s.connectedAt) / 1000) + '秒' }
function fmtTime(ts) { return new Date(ts).toLocaleTimeString() }
function roleLabel(r) { return r === 'device' ? '设备' : r === 'app' ? 'App' : '未知' }

onMounted(() => { if (authed.value) start() })
onBeforeUnmount(() => { clearInterval(poll); if (ws) ws.close() })
</script>

<template>
  <!-- 登录 -->
  <div v-if="!authed" class="login-mask">
    <div class="login-card">
      <div class="login-brand"><span class="logo">心声</span> 管理后台</div>
      <label>服务器地址</label>
      <input v-model="base" placeholder="http://ip:port" />
      <label>账号</label>
      <input v-model="loginForm.user" placeholder="账号" />
      <label>密码</label>
      <input v-model="loginForm.pass" type="password" placeholder="密码" @keyup.enter="login" />
      <div v-if="loginErr" class="login-err">{{ loginErr }}</div>
      <button class="login-btn" @click="login">登录</button>
    </div>
  </div>

  <!-- 主界面 -->
  <div v-else class="wrap">
    <header>
      <div class="brand"><span class="logo">心声</span> 管理后台</div>
      <div class="conn">
        <span class="dot" :class="{ on: connected }"></span>
        <span class="dot-label">{{ connected ? '实时已连接' : '未连接' }}</span>
        <span class="server-addr">{{ base }}</span>
        <button class="btn ghost" @click="logout">退出</button>
      </div>
    </header>

    <section class="cards">
      <div class="card"><div class="k">在线设备</div><div class="v">{{ stats.deviceCount }}</div></div>
      <div class="card"><div class="k">App 连接</div><div class="v">{{ stats.appCount }}</div></div>
      <div class="card"><div class="k">进行中通话</div><div class="v">{{ stats.activeCalls }}</div></div>
      <div class="card">
        <div class="k">服务器模式</div>
        <div class="mode">
          <button :class="{ active: stats.mode === 'echo' }" @click="setMode('echo')">echo</button>
          <button :class="{ active: stats.mode === 'relay' }" @click="setMode('relay')">relay</button>
        </div>
      </div>
      <div class="card"><div class="k">运行时长</div><div class="v small">{{ uptimeText }}</div></div>
    </section>

    <section class="panel">
      <h3>在线设备</h3>
      <table>
        <thead><tr><th>设备 ID</th><th>在线时长</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="!stats.devices.length"><td colspan="4" class="empty">暂无在线设备（relay 模式下设备注册后显示）</td></tr>
          <tr v-for="d in stats.devices" :key="d.device_id">
            <td class="mono">{{ d.device_id }}</td>
            <td>{{ fmtSince(d.since) }}</td>
            <td><span class="tag" :class="d.inCall ? 'busy' : 'idle'">{{ d.inCall ? '通话中' : '空闲' }}</span></td>
            <td class="actions">
              <button @click="cmd(d.device_id, { type: 'wifi_scan' })">WiFi扫描</button>
              <button @click="askVolume(d.device_id)">音量</button>
              <button @click="cmd(d.device_id, { type: 'switch_network', mode: 'wifi' })">切WiFi</button>
              <button @click="cmd(d.device_id, { type: 'switch_network', mode: '4g' })">切4G</button>
              <button class="warn" @click="cmd(d.device_id, { type: 'factory_reset' })">复位</button>
              <button class="danger" @click="kick(d.device_id)">踢下线</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="panel">
      <h3>会话记录 <small>（每个连接一条，用于排查硬件问题）</small></h3>
      <table>
        <thead><tr><th>#</th><th>角色</th><th>设备/地址</th><th>连接时间</th><th>时长</th><th>收音/异常</th><th>发音</th><th></th></tr></thead>
        <tbody>
          <tr v-if="!sessions.length"><td colspan="8" class="empty">暂无会话</td></tr>
          <tr v-for="s in sessions" :key="s.id" :class="{ live: s.live }">
            <td class="mono">{{ s.id }}</td>
            <td><span class="tag" :class="s.role === 'device' ? 'busy' : 'idle'">{{ roleLabel(s.role) }}</span></td>
            <td class="mono">{{ s.deviceId || s.addr }}</td>
            <td>{{ fmtTime(s.connectedAt) }}</td>
            <td>{{ s.live ? '在线' : fmtDur(s) }}</td>
            <td :class="{ bad: s.audioBad > 0 }">{{ s.audioIn }} / {{ s.audioBad }}</td>
            <td>{{ s.audioOut }}</td>
            <td><button @click="openSession(s.id)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <div v-if="sessionDetail" class="modal" @click.self="sessionDetail = null">
      <div class="modal-box">
        <div class="modal-head">
          <b>会话 #{{ sessionDetail.id }} · {{ roleLabel(sessionDetail.role) }} · {{ sessionDetail.deviceId || sessionDetail.addr }}</b>
          <button @click="sessionDetail = null">关闭</button>
        </div>
        <div class="evlist">
          <div v-for="(e, i) in sessionDetail.events" :key="i" class="evline" :class="e.dir">
            <span class="lt">{{ fmtTime(e.t) }}</span><span class="dir">{{ e.dir }}</span>
            <span class="kind">{{ e.kind }}</span><span class="detail">{{ e.detail }}</span>
          </div>
        </div>
      </div>
    </div>

    <section class="panel">
      <h3>实时事件日志</h3>
      <div class="logs">
        <div v-if="!logs.length" class="empty">暂无日志</div>
        <div v-for="(l, i) in logs" :key="i" class="logline"><span class="lt">{{ fmtTime(l.time) }}</span><span class="lx">{{ l.text }}</span></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.wrap{max-width:1080px;margin:0 auto;padding:24px 20px 60px;}
header{display:flex;align-items:center;justify-content:space-between;margin-bottom:22px;flex-wrap:wrap;gap:12px;}
.brand{font-size:20px;font-weight:800;}
.logo{color:var(--blue);}
.conn{display:flex;align-items:center;gap:8px;}
.server-addr{font-size:12px;color:var(--sub);}
.btn{background:var(--blue);color:#fff;border:none;border-radius:10px;padding:8px 16px;font-weight:600;}
.btn.ghost{background:#fff;color:var(--sub);border:1px solid var(--line);}
.dot{width:9px;height:9px;border-radius:50%;background:var(--red);}
.dot.on{background:var(--green);}
.dot-label{font-size:12px;color:var(--sub);}

.login-mask{min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(160deg,#e9f0ff,#f4f6fb);}
.login-card{background:#fff;border:1px solid var(--line);border-radius:20px;padding:32px;width:340px;box-shadow:0 20px 50px rgba(30,40,70,.12);}
.login-brand{font-size:22px;font-weight:800;text-align:center;margin-bottom:22px;}
.login-card label{display:block;font-size:12px;color:var(--sub);margin:12px 0 6px;}
.login-card input{width:100%;padding:11px 14px;border:1px solid var(--line);border-radius:10px;font-size:14px;outline:none;}
.login-card input:focus{border-color:var(--blue);}
.login-btn{width:100%;margin-top:22px;background:var(--blue);color:#fff;border:none;border-radius:10px;padding:12px;font-weight:700;font-size:15px;}
.login-err{color:var(--red);font-size:13px;margin-top:12px;text-align:center;}

.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px;margin-bottom:22px;}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px;}
.card .k{font-size:12px;color:var(--sub);}
.card .v{font-size:30px;font-weight:800;margin-top:6px;}
.card .v.small{font-size:18px;}
.mode{display:flex;gap:6px;margin-top:10px;}
.mode button{flex:1;padding:8px 0;border:1px solid var(--line);background:#fff;border-radius:10px;font-weight:600;color:var(--sub);}
.mode button.active{background:var(--blue);color:#fff;border-color:var(--blue);}

.panel{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px;margin-bottom:20px;}
.panel h3{font-size:15px;margin-bottom:14px;}
.panel h3 small{font-size:12px;color:var(--sub);font-weight:400;}
table{width:100%;border-collapse:collapse;font-size:13px;}
th,td{text-align:left;padding:11px 10px;border-bottom:1px solid var(--line);}
th{color:var(--sub);font-weight:600;}
.mono{font-family:"SF Mono",Consolas,monospace;font-weight:700;}
.empty{color:var(--sub);text-align:center;padding:22px;}
.tag{padding:3px 10px;border-radius:20px;font-size:12px;font-weight:600;}
.tag.idle{background:#e8f7ef;color:var(--green);}
.tag.busy{background:#fff2e6;color:var(--amber);}
.actions{display:flex;gap:6px;flex-wrap:wrap;}
.actions button{padding:6px 10px;border:1px solid var(--line);background:#f8fafc;border-radius:8px;font-size:12px;color:var(--ink);}
.actions button.warn{color:var(--amber);border-color:#ffe0b3;}
.actions button.danger{color:var(--red);border-color:#ffd6d3;}
tr.live td{background:#f0fbf5;}
td.bad{color:var(--red);font-weight:700;}
.modal{position:fixed;inset:0;background:rgba(20,28,45,.5);display:flex;align-items:center;justify-content:center;padding:20px;z-index:50;}
.modal-box{background:#fff;border-radius:16px;width:100%;max-width:760px;max-height:80vh;display:flex;flex-direction:column;overflow:hidden;}
.modal-head{display:flex;justify-content:space-between;align-items:center;padding:16px 20px;border-bottom:1px solid var(--line);}
.modal-head button{border:1px solid var(--line);background:#f8fafc;border-radius:8px;padding:6px 14px;}
.evlist{overflow:auto;padding:12px 16px;font-family:"SF Mono",Consolas,monospace;font-size:12px;}
.evline{display:grid;grid-template-columns:80px 40px 130px 1fr;gap:8px;padding:4px 0;border-bottom:1px solid #f0f2f7;align-items:baseline;}
.evline .lt{color:var(--sub);}
.evline .dir{font-weight:700;text-transform:uppercase;font-size:10px;color:var(--sub);}
.evline.in .dir{color:var(--blue);} .evline.out .dir{color:var(--green);} .evline.sys .dir{color:var(--amber);}
.evline .kind{color:var(--ink);font-weight:600;}
.evline .detail{color:var(--sub);word-break:break-all;}
.logs{max-height:340px;overflow:auto;background:#0e1526;border-radius:12px;padding:14px;font-family:"SF Mono",Consolas,monospace;font-size:12px;}
.logline{display:flex;gap:12px;padding:3px 0;color:#c7d2e6;}
.lt{color:#5f6b85;flex:none;}
.lx{color:#e6edff;}
.logs .empty{color:#5f6b85;}
</style>
