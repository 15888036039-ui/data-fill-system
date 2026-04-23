<template>
  <div class="task-list-container">
    <div v-if="!tasks || tasks.length === 0" class="empty-list">
      <el-empty description="当前暂无相关的报送任务" />
    </div>
    
    <div v-else class="table-wrapper">
      <el-table :data="tasks" style="width: 100%" class="task-table" border stripe>
        <el-table-column prop="name" label="模板名称" min-width="200">
          <template #default="scope">
            <div class="form-name-cell">
              <el-icon class="form-icon" style="margin-right: 8px; color: #1e293b;"><Document /></el-icon>
              <span class="name-text" style="font-weight: 500; color: #1e293b;">{{ scope.row.name }}</span>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column label="状态" width="120" align="center">
          <template #default="scope">
            <el-tag :type="statusTagType" effect="light" round>
              {{ statusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        
        <el-table-column prop="deadline" label="截止日期" width="180">
          <template #default="scope">
            <div style="display: flex; align-items: center; gap: 6px; color: #64748b;">
              <el-icon v-if="scope.row.deadline"><Calendar /></el-icon>
              <span>{{ formatDate(scope.row.deadline) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="status === 'pending'" label="剩余时间" width="160">
          <template #default="scope">
            <span v-if="scope.row.secondsLeft !== null && scope.row.secondsLeft !== undefined" style="color: #d97706; font-weight: 600;">{{ formatTimeLeft(scope.row.secondsLeft) }}</span>
            <span v-else style="color: #94a3b8; font-size: 13px;">-</span>
          </template>
        </el-table-column>

        <el-table-column v-else-if="status === 'completed'" label="下次填报" width="180">
          <template #default="scope">
            <div style="display: flex; align-items: center; gap: 6px; color: #64748b;">
              <el-icon v-if="scope.row.nextFillTime"><RefreshRight /></el-icon>
              <span>{{ scope.row.nextFillTime ? formatDate(scope.row.nextFillTime) : '-' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="160" align="right" fixed="right">
          <template #default="scope">
            <el-button 
              :type="status === 'pending' ? 'primary' : 'default'" 
              size="small"
              icon="Edit"
              @click="goToFill(scope.row.formId)"
              plain
            >
              {{ status === 'pending' ? '立即填报' : '查看/修改' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
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

let timer = null
onMounted(() => {
  timer = setInterval(() => {
    props.tasks.forEach(task => {
      if (task.secondsLeft !== undefined && task.secondsLeft > 0) {
        task.secondsLeft--
      }
      if (task.secondsUntilStart !== undefined && task.secondsUntilStart > 0) {
        task.secondsUntilStart--
      }
    })
  }, 1000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const formatTimeLeft = (seconds) => {
  if (seconds === null || seconds === undefined) return ''
  if (seconds <= 0) return '已到期'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = Math.floor(seconds % 60)
  
  if (days > 0) return `${days}天 ${hours}小时`
  if (hours > 0) return `${hours}小时 ${minutes}分 ${secs}秒`
  if (minutes > 0) return `${minutes}分钟 ${secs}秒`
  return `${secs}秒`
}

const goToFill = (formId) => {
  router.push(`/fill/${formId}`)
}
</script>

<style scoped>
.table-wrapper {
  padding: 16px 0;
}

.form-name-cell {
  display: flex;
  align-items: center;
}

.empty-list {
  padding: 60px 0;
}
</style>
