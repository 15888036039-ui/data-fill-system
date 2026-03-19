<template>
  <div class="user-tasks-page">
    <div class="page-header">
      <div class="title-section">
        <h1 class="page-title">数据填报工作台</h1>
        <p class="page-subtitle">查看并完成您的数据采集与周期性报送任务</p>
      </div>
    </div>

    <div v-if="!userEmail" class="empty-state">
      <el-empty description="请在上方的身份确认框中输入您的邮箱以同步填报任务" />
    </div>

    <div v-else class="task-content">
      <el-tabs v-model="activeTab" class="custom-tabs">
        <el-tab-pane name="pending">
          <template #label>
            <div class="tab-label">
              <span>待填报</span>
              <el-badge :value="taskData.pending.length" :hidden="taskData.pending.length === 0" />
            </div>
          </template>
          <transition name="fade-slide" mode="out-in">
            <task-list :key="activeTab" :tasks="taskData.pending" status="pending" @refresh="loadTasks" />
          </transition>
        </el-tab-pane>
        
        <el-tab-pane name="completed">
          <template #label>
            <div class="tab-label">
              <span>已填报</span>
              <el-badge :value="taskData.completed.length" type="info" :hidden="taskData.completed.length === 0" />
            </div>
          </template>
          <transition name="fade-slide" mode="out-in">
            <task-list :key="activeTab" :tasks="taskData.completed" status="completed" @refresh="loadTasks" />
          </transition>
        </el-tab-pane>
        
        <el-tab-pane name="expired">
          <template #label>
            <div class="tab-label">
              <span>已截止</span>
              <el-badge :value="taskData.expired.length" type="danger" :hidden="taskData.expired.length === 0" />
            </div>
          </template>
          <transition name="fade-slide" mode="out-in">
            <task-list :key="activeTab" :tasks="taskData.expired" status="expired" @refresh="loadTasks" />
          </transition>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch, inject, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import TaskList from '../components/TaskList.vue'

const route = useRoute()
const currentUser = inject('currentUser', ref(''))
const userEmail = computed(() => currentUser.value)
const activeTab = ref('pending')

const taskData = reactive({
  pending: [],
  completed: [],
  expired: []
})

const loadTasks = async () => {
  if (!userEmail.value) {
    return
  }
  
  try {
    const res = await axios.get(`/api/fill/user/tasks?userEmail=${encodeURIComponent(userEmail.value)}`)
    taskData.pending = res.data.pending || []
    taskData.completed = res.data.completed || []
    taskData.expired = res.data.expired || []
  } catch (e) {
    ElMessage.error('无法同步任务列表，请检查网络')
  }
}

onMounted(() => {
  if (userEmail.value) {
    loadTasks()
  }
})

// Watch for user changes (e.g. from App.vue login)
watch(userEmail, (newEmail) => {
  if (newEmail) {
    loadTasks()
  }
})
</script>

<style scoped>
.user-tasks-page {
  animation: fadeIn 0.4s ease-out;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32px;
}

.page-title {
  font-size: 26px;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 6px;
  letter-spacing: -0.025em;
}

.page-subtitle {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

.email-input {
  width: 320px;
}

:deep(.el-input-group__append) {
  background-color: var(--primary-color);
  color: white;
  box-shadow: none;
  border-color: var(--primary-color);
}

:deep(.el-input-group__append .el-button) {
  color: white;
  font-weight: 600;
}

.card-style {
  background: white;
  padding: 8px;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
  border: 1px solid #e2e8f0;
}

.empty-state-onboarding {
  padding: 80px 0;
  background: white;
  border-radius: 16px;
  border: 2px dashed #e2e8f0;
}

.custom-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: #e2e8f0;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  padding: 0 4px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Tab transition */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateX(10px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}
</style>
