<template>
  <div class="login-container">
    <div class="login-bg">
      <div class="bg-shape bg-shape-1"></div>
      <div class="bg-shape bg-shape-2"></div>
      <div class="bg-shape bg-shape-3"></div>
    </div>
    <div class="login-card">
      <div class="login-header">
        <el-icon size="46" color="#409eff"><Microphone /></el-icon>
        <h1>心声 管理后台</h1>
        <p>管理员登录</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin">
        <el-form-item prop="user">
          <el-input v-model="form.user" placeholder="账号" prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item prop="pass">
          <el-input v-model="form.pass" type="password" placeholder="密码" prefix-icon="Lock" size="large" show-password @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { login } from '@/api/admin'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const formRef = ref()
const form = reactive({ user: 'admin', pass: '' })
const rules = {
  user: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  pass: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await login(form)   // { ok, token }
    if (res && res.ok && res.token) {
      userStore.setLogin(res.token, form.user)
      ElMessage.success('登录成功')
      router.push('/dashboard')
    } else {
      ElMessage.error(res?.error || '账号或密码错误')
    }
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}
</script>

<style lang="scss" scoped>
.login-container { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); position: relative; overflow: hidden; }
.login-bg { position: absolute; inset: 0;
  .bg-shape { position: absolute; border-radius: 50%; background: rgba(64,158,255,.1); animation: float 20s infinite ease-in-out; }
  .bg-shape-1 { width: 400px; height: 400px; top: -100px; left: -100px; }
  .bg-shape-2 { width: 300px; height: 300px; bottom: -50px; right: -50px; animation-delay: -5s; }
  .bg-shape-3 { width: 200px; height: 200px; top: 50%; left: 50%; animation-delay: -10s; } }
@keyframes float { 0%,100% { transform: translate(0,0) scale(1); } 25% { transform: translate(20px,-20px) scale(1.05); } 50% { transform: translate(-10px,20px) scale(.95); } 75% { transform: translate(-20px,-10px) scale(1.02); } }
.login-card { width: 400px; padding: 40px; background: rgba(255,255,255,.96); border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,.3); position: relative; z-index: 1; }
.login-header { text-align: center; margin-bottom: 28px; h1 { font-size: 22px; color: #303133; margin: 14px 0 8px; } p { color: #909399; font-size: 14px; } }
.login-btn { width: 100%; height: 44px; font-size: 16px; }
</style>
