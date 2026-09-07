/**
 * 心声 语音对讲 · 信令/语音转发服务器 + 管理后端 + 会话日志
 * ---------------------------------------------------------------
 * 同一端口同时提供：
 *   1) WebSocket：App / 设备(ESP32) 的信令与语音转发
 *   2) WebSocket /admin：管理后台实时事件推送
 *   3) HTTP /api/*：管理后台 REST（含会话日志查询）
 *
 * 会话日志：每个连接一条会话，记录连接/断开、每条信令、语音帧计数，
 * 并校验设备语音是否为合法 251 字节 ADPCM 包（便于排查硬件编码问题）。
 * 会话结束落盘到 LOG_DIR/sessions-YYYYMMDD.jsonl。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

let mode = process.env.MODE || 'echo';
const PORT = Number(process.env.PORT) || 8080;
const LOG_DIR = process.env.LOG_DIR || path.join(__dirname, 'logs');
const PUBLIC_DIR = process.env.PUBLIC_DIR || path.join(__dirname, 'public');
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.ADMIN_PASS || 'xinsheng2026';
const START = Date.now();
try { fs.mkdirSync(LOG_DIR, { recursive: true }); } catch {}

const tokens = new Set();
function makeToken() { const t = Math.random().toString(36).slice(2) + Date.now().toString(36); tokens.add(t); return t; }
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.json': 'application/json' };

const devices = new Map();   // device_id -> 设备ws
const apps = new Set();
const admins = new Set();
const logs = [];             // 全局事件（最近 200）
const sessions = new Map();  // 活跃会话 id -> session
const history = [];          // 已结束会话（最近 200）
let seq = 0;

const nowStr = () => new Date().toLocaleTimeString();
const send = (ws, obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };

function glog(text) {
  const item = { time: Date.now(), text };
  logs.push(item); if (logs.length > 200) logs.shift();
  console.log(`[${nowStr()}]`, text);
  admins.forEach(a => send(a, { type: 'log', ...item }));
}
function pushEvent(kind, data = {}) { admins.forEach(a => send(a, { type: 'event', kind, ...data })); }

// ---------------- 会话日志 ----------------
function newSession(ws, req) {
  const s = {
    id: ++seq,
    role: 'unknown',
    deviceId: null,
    addr: req.socket.remoteAddress,
    connectedAt: Date.now(),
    disconnectedAt: null,
    events: [],
    audioIn: { frames: 0, bytes: 0, bad: 0, firstChecked: false },
    audioOut: { frames: 0, bytes: 0 },
  };
  ws.session = s;
  sessions.set(s.id, s);
  ev(ws, 'sys', 'connect', s.addr);
  pushEvent('session', { id: s.id, state: 'open' });
  return s;
}
function ev(ws, dir, kind, detail) {
  const s = ws.session; if (!s) return;
  s.events.push({ t: Date.now(), dir, kind, detail: detail === undefined ? '' : String(detail) });
  if (s.events.length > 800) s.events.shift();
}
function checkAudio(ws, buf, incoming) {
  const s = ws.session; if (!s) return;
  const c = incoming ? s.audioIn : s.audioOut;
  c.frames++; c.bytes += buf.length;
  if (incoming) {
    const okLen = buf.length === 251;
    const okMagic = buf.length >= 4 && buf[0] === 0x41 && buf[1] === 0x44 && buf[2] === 0x50 && buf[3] === 0x43;
    if (!okLen || !okMagic) {
      c.bad++;
      if (c.bad <= 5) ev(ws, 'in', 'audio_bad', `帧#${c.frames} 长度=${buf.length} 魔数ok=${okMagic}（应为251字节+ADPC）`);
    } else if (!c.firstChecked) {
      c.firstChecked = true;
      ev(ws, 'in', 'audio_ok', `首个语音帧格式正确（251B, ADPC）`);
    }
    if (c.frames % 200 === 0) ev(ws, 'in', 'audio_stat', `已收 ${c.frames} 帧, 异常 ${c.bad}`);
  }
}
function closeSession(ws) {
  const s = ws.session; if (!s) return;
  s.disconnectedAt = Date.now();
  ev(ws, 'sys', 'disconnect', `时长${Math.round((s.disconnectedAt - s.connectedAt) / 1000)}s 收音${s.audioIn.frames}帧(异常${s.audioIn.bad}) 发音${s.audioOut.frames}帧`);
  sessions.delete(s.id);
  history.push(summary(s, false)); if (history.length > 200) history.shift();
  try {
    const file = path.join(LOG_DIR, `sessions-${new Date().toISOString().slice(0, 10)}.jsonl`);
    fs.appendFile(file, JSON.stringify(s) + '\n', () => {});
  } catch {}
  pushEvent('session', { id: s.id, state: 'close' });
}
function summary(s, live) {
  return {
    id: s.id, live, role: s.role, deviceId: s.deviceId, addr: s.addr,
    platform: s.platform || null, appVersion: s.appVersion || null, os: s.os || null, fw: s.fw || null,
    connectedAt: s.connectedAt, disconnectedAt: s.disconnectedAt,
    audioIn: s.audioIn.frames, audioBad: s.audioIn.bad, audioOut: s.audioOut.frames,
    events: s.events.length,
  };
}
function stats() {
  let calls = 0; devices.forEach(d => { if (d.peer) calls++; });
  return {
    mode, port: PORT, uptimeSec: Math.floor((Date.now() - START) / 1000),
    deviceCount: devices.size, appCount: apps.size, activeCalls: calls,
    devices: [...devices.values()].map(d => ({ device_id: d.deviceId, since: d.connectedAt, inCall: !!d.peer || !!d.busy, fw: d.fw || null, net: d.net || null, state: d.state || null })),
  };
}

// ---------------- 语音抓包（排查音质问题）----------------
// POST /api/record {device_id, seconds} 开始录制该设备通话中两个方向的原始 ADPCM 帧，
// 到时后落盘 LOG_DIR/rec-<dev>-<ts>-dev2app.adpcm / -app2dev.adpcm 和帧到达时间 .json
const recorders = new Map();   // device_id -> {dev2app:[], app2dev:[], t0, until, tsIn:[], tsOut:[]}
function recCapture(deviceId, buf, fromDevice) {
  const r = recorders.get(deviceId); if (!r) return;
  const now = Date.now();
  if (!r.started) {
    r.started = true; r.t0 = now; r.until = now + r.seconds * 1000;
    r.timer = setTimeout(() => recFinish(deviceId), r.seconds * 1000 + 500);   // 录满自动落盘，不依赖后续帧
    glog(`录音开始采集 ${deviceId}（${r.seconds}s）`);
  }
  if (now > r.until) { recFinish(deviceId); return; }
  if (fromDevice) { r.dev2app.push(Buffer.from(buf)); r.tsIn.push(now - r.t0); }
  else            { r.app2dev.push(Buffer.from(buf)); r.tsOut.push(now - r.t0); }
}
function recFinish(deviceId) {
  const r = recorders.get(deviceId); if (!r) return;
  recorders.delete(deviceId);
  if (r.timer) clearTimeout(r.timer);
  if (!r.started) return;
  const ts = new Date(r.t0).toISOString().replace(/[:.]/g, '-');
  const base = path.join(LOG_DIR, `rec-${deviceId}-${ts}`);
  try {
    fs.writeFileSync(base + '-dev2app.adpcm', Buffer.concat(r.dev2app));
    fs.writeFileSync(base + '-app2dev.adpcm', Buffer.concat(r.app2dev));
    fs.writeFileSync(base + '-timing.json', JSON.stringify({ tsIn: r.tsIn, tsOut: r.tsOut }));
    glog(`录音完成 ${deviceId}: 设备->App ${r.dev2app.length} 帧, App->设备 ${r.app2dev.length} 帧 -> ${path.basename(base)}`);
  } catch (e) { glog(`录音落盘失败: ${e.message}`); }
}

// ---------------- HTTP 管理 API ----------------
function readBody(req) {
  return new Promise(resolve => { let b = ''; req.on('data', c => b += c); req.on('end', () => { try { resolve(JSON.parse(b || '{}')); } catch { resolve({}); } }); });
}
const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-token');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const u = new URL(req.url, 'http://x'); const p = u.pathname;
  const json = (obj, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };

  // 登录
  if (p === '/api/login' && req.method === 'POST') {
    const b = await readBody(req);
    if (b.user === ADMIN_USER && b.pass === ADMIN_PASS) return json({ ok: true, token: makeToken() });
    return json({ ok: false, error: '账号或密码错误' }, 401);
  }
  // 除登录外的 /api 需要 token
  if (p.startsWith('/api/')) {
    const token = req.headers['x-token'];
    if (!token || !tokens.has(token)) return json({ ok: false, error: 'unauthorized' }, 401);
  }

  // 静态托管管理后台（非 /api 的 GET）
  if (!p.startsWith('/api/') && req.method === 'GET') {
    let rel = p === '/' ? '/index.html' : p;
    let file = path.join(PUBLIC_DIR, rel);
    if (!file.startsWith(PUBLIC_DIR)) file = path.join(PUBLIC_DIR, 'index.html');
    fs.readFile(file, (err, data) => {
      if (err) {
        fs.readFile(path.join(PUBLIC_DIR, 'index.html'), (e2, idx) => {
          if (e2) { res.writeHead(404); return res.end('心声服务器运行中（管理后台未部署静态文件）'); }
          res.writeHead(200, { 'Content-Type': MIME['.html'] }); res.end(idx);
        });
        return;
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      res.end(data);
    });
    return;
  }

  if (p === '/api/stats' && req.method === 'GET') return json(stats());
  if (p === '/api/logs' && req.method === 'GET') return json(logs);
  if (p === '/api/devices' && req.method === 'GET') return json(stats().devices);
  if (p === '/api/sessions' && req.method === 'GET') {
    const active = [...sessions.values()].map(s => summary(s, true));
    return json([...active, ...history.slice().reverse()]);
  }
  if (p === '/api/session' && req.method === 'GET') {
    const id = Number(u.searchParams.get('id'));
    const s = sessions.get(id);
    if (s) return json(s);
    // 已结束会话详情从当天日志文件读取
    try {
      const file = path.join(LOG_DIR, `sessions-${new Date().toISOString().slice(0, 10)}.jsonl`);
      const lines = fs.readFileSync(file, 'utf8').trim().split('\n');
      for (const ln of lines) { const o = JSON.parse(ln); if (o.id === id) return json(o); }
    } catch {}
    return json({ error: 'not found' }, 404);
  }
  if (p === '/api/mode' && req.method === 'POST') {
    const b = await readBody(req);
    if (b.mode === 'echo' || b.mode === 'relay') { mode = b.mode; glog(`切换模式 => ${mode}`); pushEvent('mode', { mode }); return json({ ok: true, mode }); }
    return json({ ok: false, error: 'mode must be echo|relay' }, 400);
  }
  if (p === '/api/command' && req.method === 'POST') {
    const b = await readBody(req);
    if (!b.device_id || !b.type) return json({ ok: false, error: 'need device_id & type' }, 400);
    const dev = devices.get(b.device_id);
    if (!dev) return json({ ok: false, error: 'device offline' }, 404);
    send(dev, b); ev(dev, 'out', 'admin_cmd', b.type); glog(`后台下发 ${b.type} -> ${b.device_id}`);
    return json({ ok: true });
  }
  if (p === '/api/record' && req.method === 'POST') {
    const b = await readBody(req);
    if (!b.device_id) return json({ ok: false, error: 'need device_id' }, 400);
    const seconds = Math.min(Number(b.seconds) || 30, 300);
    // 待命：收到该设备第一帧语音才开始计时；15 分钟内没通话则作废
    const rec = { dev2app: [], app2dev: [], tsIn: [], tsOut: [], seconds, started: false, t0: Date.now(), until: 0 };
    recorders.set(b.device_id, rec);
    // 只作废"自己这一个"任务，不能误伤后来重新布下的
    setTimeout(() => { if (recorders.get(b.device_id) === rec && !rec.started) { recorders.delete(b.device_id); glog(`录音待命超时作废 ${b.device_id}`); } }, 15 * 60 * 1000);
    glog(`录音待命 ${b.device_id}：下一通电话开始后录 ${seconds}s`);
    return json({ ok: true, seconds, armed: true });
  }
  if (p === '/api/record/list' && req.method === 'GET') {
    try { return json(fs.readdirSync(LOG_DIR).filter(f => f.startsWith('rec-')).sort()); } catch { return json([]); }
  }
  if (p === '/api/record/file' && req.method === 'GET') {
    const name = path.basename(u.searchParams.get('name') || '');
    const file = path.join(LOG_DIR, name);
    if (!name.startsWith('rec-') || !fs.existsSync(file)) return json({ error: 'not found' }, 404);
    res.writeHead(200, { 'Content-Type': 'application/octet-stream' }); return res.end(fs.readFileSync(file));
  }
  if (p === '/api/kick' && req.method === 'POST') {
    const b = await readBody(req); const dev = devices.get(b.device_id);
    if (dev) { dev.close(4000, 'kicked'); return json({ ok: true }); }
    return json({ ok: false, error: 'device offline' }, 404);
  }
  json({ ok: false, error: 'not found' }, 404);
});

// ---------------- WebSocket ----------------
const wss = new WebSocketServer({ server });
wss.on('connection', (ws, req) => {
  if ((req.url || '').startsWith('/admin')) {
    const token = new URL(req.url, 'http://x').searchParams.get('token');
    if (!token || !tokens.has(token)) { ws.close(4401, 'unauthorized'); return; }
    admins.add(ws); send(ws, { type: 'hello', stats: stats(), logs }); ws.on('close', () => admins.delete(ws)); return;
  }
  ws.role = 'unknown'; ws.deviceId = null; ws.peer = null;
  newSession(ws, req);

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      checkAudio(ws, data, true);
      if (ws.role === 'device' && ws.deviceId) recCapture(ws.deviceId, data, true);
      else if (ws.peer && ws.peer.deviceId) recCapture(ws.peer.deviceId, data, false);
      if (mode === 'echo' && ws.role === 'app') { ws.send(data, { binary: true }); checkAudio(ws, data, false); }
      else if (mode === 'relay' && ws.peer && ws.peer.readyState === 1) { ws.peer.send(data, { binary: true }); checkAudio(ws.peer, data, false); }
      return;
    }
    let m; try { m = JSON.parse(data.toString()); } catch { return; }
    if (m.type !== 'ping') ev(ws, 'in', m.type, JSON.stringify(m).slice(0, 160));
    switch (m.type) {
      case 'connect_app': {
        // App 上报 platform(android/ios)、version、os，便于区分两端日志
        const plat = (m.platform || 'unknown').toLowerCase();
        ws.role = 'app'; ws.session.role = 'app';
        ws.platform = plat; ws.session.platform = plat;
        ws.session.appVersion = m.version || null; ws.session.os = m.os || null;
        apps.add(ws);
        glog(`App 已连接 [${plat}${m.version ? ' ' + m.version : ''}${m.os ? ' / ' + m.os : ''}]`);
        pushEvent('app', { count: apps.size, platform: plat }); break;
      }
      case 'ping':
        send(ws, { type: 'pong' });
        // 设备心跳带的 WiFi 信号强度 / 联网方式 / 通话状态转发给所有 App 显示
        if (ws.role === 'device' && ws.deviceId != null && m.rssi != null) {
          if (m.net) ws.net = m.net;
          if (m.state) ws.state = m.state;
          apps.forEach(a => send(a, { type: 'device_signal', device_id: ws.deviceId, rssi: m.rssi, net: ws.net || null, state: ws.state || null }));
        }
        break;
      case 'device_state': {
        // 设备占线状态（蓝牙通话 / 电话卡通话等非网络通话）：占线期间 App 呼叫直接回忙
        if (ws.role !== 'device') break;
        ws.busy = !!m.busy; ws.state = m.state || (m.busy ? 'busy' : 'idle');
        glog(`设备 ${ws.deviceId} 状态 ${ws.state}${ws.busy ? '（占线）' : ''}`);
        pushEvent('device', { device_id: ws.deviceId, online: true, fw: ws.fw, state: ws.state, busy: ws.busy });
        apps.forEach(a => send(a, { type: 'device_state', device_id: ws.deviceId, state: ws.state, busy: ws.busy }));
        break;
      }
      case 'call_reject': {
        // 设备拒接（收到 incoming_call 时已被别的通话占线）：告诉呼叫方忙，清掉配对
        if (ws.role !== 'device') break;
        glog(`设备 ${ws.deviceId} 拒接（${m.reason || 'busy'}）`);
        if (ws.peer) {
          send(ws.peer, { type: 'call_result', success: false, error: `busy 设备正在通话中(${m.reason || 'busy'})，请稍后再拨` });
          ev(ws.peer, 'out', 'call_result', 'busy(device_reject)');
          ws.peer.peer = null; ws.peer = null;
        }
        break;
      }
      case 'check_device_status':
        send(ws, { type: 'device_status', device_id: m.device_id, online: mode === 'echo' ? true : devices.has(m.device_id) }); break;
      case 'call_request': {
        ws.callId = m.device_id; glog(`App[${ws.platform || '?'}] 呼叫 ${m.device_id}`); pushEvent('call', { device_id: m.device_id, state: 'calling' });
        if (mode === 'echo') { send(ws, { type: 'call_connected' }); ev(ws, 'out', 'call_connected', 'echo'); }
        else {
          const dev = devices.get(m.device_id);
          if (!dev) { send(ws, { type: 'call_result', success: false, error: 'device offline 设备不在线' }); ev(ws, 'out', 'call_result', 'offline'); break; }
          // 设备正在蓝牙通话 / 电话卡通话（设备自己上报的占线）：直接回忙
          if (dev.busy) {
            send(ws, { type: 'call_result', success: false, error: `busy 设备正在通话中(${dev.state || 'busy'})，请稍后再拨` });
            ev(ws, 'out', 'call_result', `busy(${dev.state})`); glog(`设备 ${m.device_id} 占线(${dev.state})，拒绝 App[${ws.platform || '?'}] 呼叫`);
            break;
          }
          // 设备正在和别的 App 通话：拒绝，不能抢线（对端已断开的残留状态则清掉）
          if (dev.peer && dev.peer !== ws) {
            if (dev.peer.readyState === 1) {
              send(ws, { type: 'call_result', success: false, error: 'busy 设备正在通话中，请稍后再拨' });
              ev(ws, 'out', 'call_result', 'busy'); glog(`设备 ${m.device_id} 占线，拒绝 App[${ws.platform || '?'}] 呼叫`);
              break;
            }
            dev.peer.peer = null; dev.peer = null;
          }
          // 本 App 若还挂着上一通的对端，先清理
          if (ws.peer && ws.peer !== dev) { send(ws.peer, { type: 'call_ended' }); ws.peer.peer = null; }
          ws.peer = dev; dev.peer = ws; send(dev, { type: 'incoming_call' }); ev(dev, 'out', 'incoming_call', ''); }
        break;
      }
      case 'call_end':
        send(ws, { type: 'call_ended' });
        if (ws.peer) {
          // 通话结束时若正在录音，立即落盘（不足设定秒数也保存）
          const devId = ws.peer.deviceId || ws.deviceId; if (devId && recorders.get(devId)?.started) recFinish(devId);
          send(ws.peer, { type: 'call_ended' }); ev(ws.peer, 'out', 'call_ended', ''); ws.peer.peer = null; ws.peer = null; }
        pushEvent('call', { state: 'ended' }); break;
      case 'wifi_scan':
        send(ws, { type: 'wifi_list', device_id: m.device_id, data: [ { ssid: 'Home-WiFi-5G', rssi: -42 }, { ssid: 'Office_2.4G', rssi: -58 }, { ssid: 'TP-LINK_8823', rssi: -71 } ] });
        if (mode === 'relay') { const d = devices.get(m.device_id); if (d) { send(d, m); ev(d, 'out', 'wifi_scan', ''); } } break;
      case 'wifi_config':
        send(ws, { type: 'wifi_test_result', success: true });
        if (mode === 'relay') { const d = devices.get(m.device_id); if (d) { send(d, m); ev(d, 'out', 'wifi_config', ''); } } break;
      case 'set_volume': case 'set_speaker_volume': case 'factory_reset': case 'switch_network': case 'pairing_gpio':
        if (mode === 'relay') { const d = devices.get(m.device_id); if (d) { send(d, m); ev(d, 'out', m.type, JSON.stringify(m).slice(0, 120)); } } break;
      case 'connect_device': {
        // 同一设备号重连：先把旧连接踢掉，避免僵尸会话；旧连接的 close 里会发现自己已被替代而不动在线表
        const old = devices.get(m.device_id);
        if (old && old !== ws) { glog(`设备 ${m.device_id} 重连，关闭旧连接`); try { old.terminate(); } catch {} }
        ws.role = 'device'; ws.session.role = 'device'; ws.deviceId = m.device_id; ws.session.deviceId = m.device_id;
        ws.fw = m.fw || null; ws.session.fw = ws.fw;
        ws.net = m.net || null; ws.state = m.state || 'idle'; ws.busy = !!(m.state && m.state !== 'idle');
        ws.connectedAt = Date.now(); devices.set(m.device_id, ws);
        glog(`设备上线 ${m.device_id}${ws.fw ? ' 固件 ' + ws.fw : ''}${ws.net ? ' 网络 ' + ws.net : ''}${ws.busy ? ' 状态 ' + ws.state : ''}`);
        pushEvent('device', { device_id: m.device_id, online: true, fw: ws.fw, net: ws.net, state: ws.state, busy: ws.busy });
        apps.forEach(a => send(a, { type: 'device_online', device_id: m.device_id })); break;
      }
      case 'call_accept':
        if (ws.peer) { send(ws.peer, { type: 'call_connected' }); ev(ws.peer, 'out', 'call_connected', ''); glog(`设备接听 ${ws.deviceId}`); pushEvent('call', { device_id: ws.deviceId, state: 'connected' }); } break;
      default: glog(`未处理消息 ${JSON.stringify(m)}`);
    }
  });

  ws.on('close', () => {
    if (ws.role === 'app') { apps.delete(ws); pushEvent('app', { count: apps.size }); }
    if (ws.role === 'device' && ws.deviceId) {
      // 只有在线表里登记的还是"自己"时才算下线；若已被同号新连接替代，不能把新连接删掉
      if (devices.get(ws.deviceId) === ws) {
        devices.delete(ws.deviceId);
        apps.forEach(a => send(a, { type: 'device_offline', device_id: ws.deviceId }));
        glog(`设备下线 ${ws.deviceId}`); pushEvent('device', { device_id: ws.deviceId, online: false });
      } else {
        glog(`设备 ${ws.deviceId} 旧连接关闭（已被新连接替代，仍在线）`);
      }
    }
    if (ws.peer) {
      // 一方断开，通知另一方挂断，避免设备麦克风一直开着
      if (ws.peer.readyState === 1) { send(ws.peer, { type: 'call_ended' }); ev(ws.peer, 'out', 'call_ended', 'peer_closed'); }
      ws.peer.peer = null;
    }
    closeSession(ws);
  });
});

server.listen(PORT, () => glog(`心声服务器启动 模式:${mode} 端口:${PORT} 日志目录:${LOG_DIR}`));
