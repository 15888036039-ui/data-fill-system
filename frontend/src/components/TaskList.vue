<template>
  <div class="task-list-container">
    <div v-if="!tasks || tasks.length === 0" class="empty-list">
      <el-empty description="当前暂无相关的报送任务" />
    </div>
    
    <div v-else class="task-grid">
      <div 
        v-for="task in tasks" 
        :key="task.formId" 
        class="task-card-wrapper"
        @click="goToFill(task.formId)"
      >
        <el-card class="premium-task-card" shadow="hover">
          <div class="card-status-bar" :class="status"></div>
          
          <div class="card-body">
            <div class="title-row">
              <h3 class="task-title">{{ task.name }}</h3>
              <el-tag :type="statusTagType" effect="light" round size="small">
                {{ statusLabel }}
              </el-tag>
            </div>
            
            <div class="info-grid">
              <div class="info-item">
                <el-icon><Calendar /></el-icon>
                <span class="label">截止日期:</span>
                <span class="value">{{ formatDate(task.deadline) }}</span>
              </div>
              
              <div v-if="status === 'pending'" class="info-item countdown">
                <el-icon><AlarmClock /></el-icon>
                <span class="label">剩余时间:</span>
                <span class="value accent">{{ formatTimeLeft(task.secondsLeft) }}</span>
              </div>
              
              <div v-if="status === 'completed' && task.nextFillTime" class="info-item next">
                <el-icon><RefreshRight /></el-icon>
                <span class="label">下次填报:</span>
                <span class="value">{{ formatDate(task.nextFillTime) }}</span>
              </div>
            </div>

            <div class="card-action">
              <el-button 
                :type="status === 'pending' ? 'primary' : 'default'" 
                class="action-btn"
                icon="ArrowRight"
                text
                bg
              >
                {{ status === 'pending' ? '立即去填写' : '查看记录 / 修改' }}
              </el-button>
            </div>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Calendar, AlarmClock, RefreshRight, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({
  tasks: {
    type: Array,
    required: true
  },
  status: {
    type: String,
    required: true
  }
})

const router = useRouter()

const statusLabel = computed(() => {
  switch (props.status) {
    case 'pending': return '待填报'
    case 'completed': return '已完成'
    case 'expired': return '已截止'
    default: return ''
  }
})

const statusTagType = computed(() => {
  switch (props.status) {
    case 'pending': return 'warning'
    case 'completed': return 'success'
    case 'expired': return 'danger'
    default: return 'info'
  }
})

const formatDate = (date) => {
  if (!date) return '长期有效'
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const formatTimeLeft = (seconds) => {
  if (seconds <= 0) return '已到期'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  
  if (days > 0) return `${days}天 ${hours}小时`
  if (hours > 0) return `${hours}小时 ${minutes}分`
  return `${minutes}分钟`
}

const goToFill = (formId) => {
  router.push(`/fill/${formId}`)
}
</script>

<style scoped>
.task-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 24px;
  padding: 16px 0;
}

.task-card-wrapper {
  cursor: pointer;
}

.premium-task-card {
  height: 100%;
  border: none !important;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  overflow: hidden;
}

.premium-task-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 12px 24px -8px rgba(0, 0, 0, 0.15) !important;
}

.card-status-bar {
  height: 4px;
  width: 100%;
  position: absolute;
  top: 0;
  left: 0;
}

.card-status-bar.pending { background: #f59e0b; }
.card-status-bar.completed { background: #10b981; }
.card-status-bar.expired { background: #ef4444; }

.card-body {
  padding: 8px 4px;
}

.title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 12px;
}

.task-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1e293b;
  line-height: 1.4;
  flex: 1;
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #64748b;
}

.info-item .el-icon {
  font-size: 16px;
  color: #94a3b8;
}

.info-item .label {
  color: #94a3b8;
}

.info-item .value {
  color: #334155;
  font-weight: 500;
}

.info-item .value.accent {
  color: #d97706;
  font-weight: 600;
}

.card-action {
  border-top: 1px solid #f1f5f9;
  padding-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.action-btn {
  font-weight: 600;
  letter-spacing: 0.5px;
}

.empty-list {
  padding: 60px 0;
}
</style>
