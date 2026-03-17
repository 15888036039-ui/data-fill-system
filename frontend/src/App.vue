<template>
  <el-config-provider :locale="zhCn">
    <div class="app-wrapper">
      <header v-if="!isEmbedMode" class="app-header">
        <div class="header-content">
          <div class="logo-area" @click="$router.push('/')">
            <el-icon class="logo-icon" :size="28"><DataAnalysis /></el-icon>
            <span class="logo-text">数据填报系统</span>
          </div>
          <el-menu 
            mode="horizontal" 
            router 
            :default-active="$route.path"
            class="nav-menu"
            :ellipsis="false"
          >
            <el-menu-item index="/tasks">填报工作台</el-menu-item>
            <el-menu-item index="/forms">模板管理</el-menu-item>
            <el-menu-item index="/designer">新建模板</el-menu-item>
            <el-menu-item index="/settings">系统配置</el-menu-item>
          </el-menu>
          <div class="header-right">
            <!-- User display removed as per request -->
          </div>
        </div>
      </header>
      
      <main class="main-body" :class="{ 'is-embedded': isEmbedMode }">
        <div class="container">
          <router-view v-slot="{ Component }">
            <transition name="fade-transform" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </main>

      <footer v-if="!isEmbedMode" class="app-footer">
        <div class="footer-content">
          <p>© 2026 企业级数据采集平台 | 全流程自动化填报方案</p>
          <div class="footer-links">
            <span>帮助中心</span>
            <span>隐私政策</span>
            <span>联系支持</span>
          </div>
        </div>
      </footer>
    </div>
  </el-config-provider>
</template>

<script setup>
import { provide, ref } from 'vue'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

// Simple fixed state as requested - everyone is "Admin" for this project
const isAdmin = ref(true)
provide('isAdmin', isAdmin)

// Embed mode detection
const isEmbedMode = ref(window.location.search.includes('embed=true') || window.location.hash.includes('embed=true'))
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

.logo-area {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  margin-right: 48px;
}

.logo-icon {
  color: var(--primary-color);
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: #0f172a;
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

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-size: 14px;
  color: #475569;
  font-weight: 500;
  padding: 8px 12px;
  border-radius: 6px;
  transition: background 0.2s;
}

.user-dropdown:hover {
  background: #f1f5f9;
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

.app-footer {
  background: white;
  border-top: 1px solid #e2e8f0;
  padding: 32px 0;
  margin-top: auto;
}

.footer-content {
  width: 100%;
  margin: 0;
  padding: 0 24px;
  text-align: center;
}

.footer-content p {
  font-size: 14px;
  color: #94a3b8;
  margin: 0 0 12px;
}

.footer-links {
  display: flex;
  justify-content: center;
  gap: 24px;
  font-size: 13px;
  color: #64748b;
}

.footer-links span {
  cursor: pointer;
  transition: color 0.2s;
}

.footer-links span:hover {
  color: var(--primary-color);
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
</style>
