<template>
  <el-config-provider :locale="zhCn">
    <div class="app-wrapper">
      <header v-if="!isEmbedMode && currentUser && isRegistered !== false" class="app-header">
        <div class="header-content">
          <el-menu 
            mode="horizontal" 
            router 
            :default-active="$route.path"
            class="nav-menu"
            :ellipsis="false"
          >
            <el-menu-item v-if="!isAdmin" index="/tasks">填报工作台</el-menu-item>
            <el-menu-item v-if="isAdmin" index="/forms">模板管理</el-menu-item>
            <el-menu-item v-if="isAdmin" index="/designer">新建模板</el-menu-item>
            <el-menu-item v-if="isAdmin" index="/settings">系统配置</el-menu-item>
          </el-menu>
          <div class="header-right">
            <el-tag v-if="currentUser" type="info" effect="plain" class="user-badge" round>
              {{ maskedUser }}
            </el-tag>
          </div>
        </div>
      </header>
      
      <main class="main-body" :class="{ 'is-embedded': isEmbedMode }">
        <div class="container">
          <template v-if="currentUser && (isRegistered === true || loading || isRegistered === null)">
            <div v-if="loading || isRegistered === null" class="loading-state">
               <el-skeleton :rows="10" animated />
            </div>
            <router-view v-else v-slot="{ Component }">
              <transition name="fade-transform" mode="out-in">
                <component :is="Component" />
              </transition>
            </router-view>
          </template>
          <template v-else-if="currentUser && isRegistered === false">
            <div class="unauthorized-state">
              <el-result icon="error" title="系统未注册用户" sub-title="您的账号尚未在组织架构中注册，无法访问填报系统。">
                <template #extra>
                  <p class="error-tip">当前用户：{{ currentUser }}</p>
                  <p style="margin-top: 10px; color: #64748b;">如需申请权限，请联系管理员核对注册信息。</p>
                </template>
              </el-result>
            </div>
          </template>
          <template v-else>
            <div class="login-state">
              <div class="login-card">
                <div class="login-icon">📋</div>
                <h2 class="login-title">数据填报系统</h2>
                <p class="login-subtitle">请选择您的身份以继续</p>
                <el-select
                  v-model="selectedLogin"
                  filterable
                  placeholder="搜索并选择用户"
                  size="large"
                  style="width: 100%; margin: 20px 0;"
                  :loading="loginUsersLoading"
                >
                  <el-option
                    v-for="u in loginUsers"
                    :key="u"
                    :label="u"
                    :value="u"
                  />
                </el-select>
                <el-button 
                  type="primary" 
                  size="large" 
                  style="width: 100%;" 
                  :disabled="!selectedLogin"
                  @click="handleManualLogin"
                >
                  确认登录
                </el-button>
              </div>
            </div>
          </template>
        </div>
      </main>
    </div>
  </el-config-provider>
</template>

<script setup>
import { provide, ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import axios from 'axios'
import { decryptUser, encryptUser, isEncrypted } from './utils/crypto.js'

// Detection from URL (query or hash)
const getParam = (name) => {
  const searchParams = new URLSearchParams(window.location.search)
  if (searchParams.has(name)) return searchParams.get(name)
  
  const hashPart = window.location.hash.split('?')[1]
  if (hashPart) {
    const hashParams = new URLSearchParams(hashPart)
    if (hashParams.has(name)) return hashParams.get(name)
  }
  return null
}

const rawUserParam = ref(getParam('user'))
const isRegistered = ref(null) // null: initial, true: yes, false: no
const loading = ref(false)

const route = useRoute()
const router = useRouter()

// Watch for landing with plain text user and hide it immediately in both search AND hash
const checkAndObfuscateUser = () => {
  let changed = false
  const searchParams = new URLSearchParams(window.location.search)
  let hash = window.location.hash
  
  // 1. Check main search params
  const searchUser = searchParams.get('user')
  if (searchUser && !isEncrypted(searchUser)) {
    searchParams.set('user', encryptUser(searchUser))
    changed = true
  }
  
  // 2. Check hash params (#/...?user=...)
  if (hash.includes('?')) {
    const parts = hash.split('?')
    const prefix = parts[0]
    const hashQuery = parts.slice(1).join('?')
    const hashParams = new URLSearchParams(hashQuery)
    const hashUser = hashParams.get('user')
    if (hashUser && !isEncrypted(hashUser)) {
      hashParams.set('user', encryptUser(hashUser))
      hash = `${prefix}?${hashParams.toString()}`
      changed = true
    }
  }
  
  if (changed) {
    const newSearch = searchParams.toString() ? '?' + searchParams.toString() : ''
    // Use window.location.pathname to correctly preserve base path
    const newUrl = window.location.pathname + newSearch + hash
    window.history.replaceState({}, '', newUrl)
    // Re-sync
    const currentParam = getParam('user')
    if (currentParam !== rawUserParam.value) {
      rawUserParam.value = currentParam
    }
  }
}

// Additional watch on route to catch inner navigations or late loads
watch(() => route.query.user, (newVal) => {
  if (newVal && !isEncrypted(newVal)) {
    const encrypted = encryptUser(newVal)
    router.replace({
      query: { ...route.query, user: encrypted }
    }).then(() => {
      rawUserParam.value = encrypted
    })
  }
}, { immediate: true })

const verifyUser = async (email) => {
  if (!email) return
  
  loading.value = true
  try {
    const res = await axios.get(`/api/user/verify?userEmail=${encodeURIComponent(email)}`)
    isRegistered.value = res.data.registered
  } catch (e) {
    console.error('User verification failed:', e)
    isRegistered.value = false
  } finally {
    loading.value = false
  }
}

// Run immediately to hide plain text on landing
checkAndObfuscateUser()

const currentUser = computed(() => decryptUser(rawUserParam.value))
provide('currentUser', currentUser)

// Masked user for display
const maskedUser = computed(() => {
  if (!currentUser.value) return ''
  const email = currentUser.value
  const [name, domain] = email.split('@')
  if (!domain) return name.slice(0, 1) + '***'
  return (name.length > 2 ? name.slice(0, 2) : name.slice(0, 1)) + '***@' + domain
})

const isAdmin = computed(() => currentUser.value === 'finereport_manage')
provide('isAdmin', isAdmin)

// Embed mode detection
const isEmbedMode = ref(window.location.search.includes('embed=true') || window.location.hash.includes('embed=true'))

// 手动登录（备用方案）
const selectedLogin = ref('')
const loginUsers = ref([])
const loginUsersLoading = ref(false)

const loadLoginUsers = async () => {
  loginUsersLoading.value = true
  try {
    const res = await axios.get('/api/user/list')
    loginUsers.value = res.data || []
  } catch (e) {
    loginUsers.value = []
  } finally {
    loginUsersLoading.value = false
  }
}

const handleManualLogin = () => {
  if (!selectedLogin.value) return
  const encrypted = encryptUser(selectedLogin.value)
  // 使用 router.push 而不是 window.location.href，确保路由触发更新
  router.push({
    path: route.path,
    query: { ...route.query, user: encrypted }
  }).then(() => {
    rawUserParam.value = encrypted
    // 强制执行校验
    verifyUser(selectedLogin.value)
  })
}

// Utility to preserve user in navigation if needed
const appendUser = (path) => {
  if (!rawUserParam.value) return path
  const sep = path.includes('?') ? '&' : '?'
  return `${path}${sep}user=${rawUserParam.value}`
}
provide('appendUser', appendUser)

onMounted(() => {
  // User is identified via URL parameter or manual login
  if (currentUser.value) {
    verifyUser(currentUser.value)
  } else {
    // 没有检测到用户，加载列表供手动选择
    loadLoginUsers()
  }
})
</script>

<style>
:root {
  --primary-color: #2563eb;
  --bg-color: #f8fafc;
  --header-height: 64px;
}

body {
  margin: 0;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  background-color: var(--bg-color);
  color: #1e293b;
  -webkit-font-smoothing: antialiased;
}

.loading-state {
  padding: 80px 0;
}

.unauthorized-state {
  padding: 100px 0;
  display: flex;
  justify-content: center;
}

.error-tip {
  font-family: monospace;
  background: #f1f5f9;
  padding: 8px 16px;
  border-radius: 6px;
  color: #ef4444;
  font-size: 13px;
}

.app-wrapper {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  height: var(--header-height);
  background: white;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  position: sticky;
  top: 0;
  z-index: 1000;
}

.header-content {
  width: 100%;
  margin: 0;
  height: 100%;
  display: flex;
  align-items: center;
  padding: 0 24px;
}

.nav-menu {
  flex: 1;
  border-bottom: none !important;
  height: 100%;
}

.nav-menu .el-menu-item {
  font-size: 15px;
  font-weight: 500;
  color: #64748b;
  border-bottom: 2px solid transparent !important;
}

.nav-menu .el-menu-item.is-active {
  color: var(--primary-color) !important;
  border-bottom-color: var(--primary-color) !important;
  background-color: transparent !important;
}

.main-body {
  flex: 1;
  padding: 32px 0;
}

.main-body.is-embedded {
  padding: 0;
}

.container {
  width: 100%;
  margin: 0;
  padding: 0 24px;
  box-sizing: border-box;
}

/* Transitions */
.fade-transform-enter-active,
.fade-transform-leave-active {
  transition: all 0.3s ease;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateY(10px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* Global Element Plus Overrides */
.el-card {
  border-radius: 12px !important;
  border: 1px solid #e2e8f0 !important;
  box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px -1px rgba(0, 0, 0, 0.1) !important;
}

.el-button--primary {
  --el-button-bg-color: var(--primary-color) !important;
  --el-button-border-color: var(--primary-color) !important;
  font-weight: 600 !important;
  border-radius: 8px !important;
}

.login-state {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 60vh;
}

.login-card {
  background: white;
  border-radius: 16px;
  padding: 48px 40px;
  width: 400px;
  max-width: 90vw;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.08);
  text-align: center;
  border: 1px solid #e2e8f0;
}

.login-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.login-title {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px 0;
}

.login-subtitle {
  color: #64748b;
  font-size: 14px;
  margin: 0;
}
</style>
