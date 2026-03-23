<template>
  <div class="user-tasks-page">
    <div v-if="!userEmail" class="empty-state">
      <el-empty description="请在上方的身份确认框中输入您的邮箱以同步填报任务" />
    </div>

    <div v-else class="task-content">
      <div class="filter-bar">
        <el-input
          v-model="searchQuery"
          placeholder="搜索模板名称..."
          prefix-icon="Search"
          clearable
          class="search-input"
          @clear="handleFilter"
          @keyup.enter="handleFilter"
        />
        <el-select v-model="statusFilter" placeholder="模板状态" clearable class="status-select">
          <el-option label="所有状态" value="" />
          <el-option label="待填报" value="pending" />
          <el-option label="已填报" value="completed" />
          <el-option label="已截止" value="expired" />
        </el-select>
        <el-button type="primary" icon="Search" @click="handleFilter">查询</el-button>
        <el-button icon="Refresh" @click="resetFilter">重置</el-button>
      </div>

      <el-card class="table-card" shadow="never">
        <el-table :data="paginatedTasks" style="width: 100%" v-loading="loading" stripe>
          <el-table-column prop="name" label="模板名称" min-width="300">
            <template #default="scope">
              <div class="form-name-cell">
                <el-icon class="form-icon" style="color: #64748b;"><Document /></el-icon>
                <span class="name-text" style="font-size: 15px;">{{ scope.row.name }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="状态" min-width="140" align="left">
            <template #default="scope">
              <el-tag
                :type="scope.row.taskStatus === 'pending' ? 'warning' : (scope.row.taskStatus === 'completed' ? 'success' : 'danger')"
                effect="light"
                round
              >
                {{ scope.row.taskStatus === 'pending' ? '待填报' : (scope.row.taskStatus === 'completed' ? '已填报' : '已截止') }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="deadline" label="截止时间" min-width="200">
            <template #default="scope">
              <div class="time-cell">
                <el-icon v-if="scope.row.deadline"><Timer /></el-icon>
                <span>{{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleString() : '长期有效' }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="填报倒计时 / 下次填报" min-width="240">
            <template #default="scope">
              <div class="time-cell">
                <el-icon v-if="scope.row.taskStatus === 'pending'"><AlarmClock /></el-icon>
                <el-icon v-else-if="scope.row.taskStatus === 'completed' && scope.row.nextFillTime"><Calendar /></el-icon>
                
                <span v-if="scope.row.taskStatus === 'pending'" style="color: #d97706; font-weight: 600;">
                  {{ formatTimeLeft(scope.row.secondsLeft) }}
                </span>
                <span v-else-if="scope.row.taskStatus === 'completed'" style="color: #64748b;">
                  {{ scope.row.nextFillTime ? new Date(scope.row.nextFillTime).toLocaleString() : '-' }}
                </span>
                <span v-else style="color: #ef4444;">已过期</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="160" align="right" fixed="right">
            <template #default="scope">
              <el-button 
                :type="scope.row.taskStatus === 'pending' ? 'primary' : 'default'" 
                size="small"
                icon="Edit"
                plain
                @click="$router.push(`/fill/${scope.row.formId}`)"
              >
                {{ scope.row.taskStatus === 'pending' ? '立即填报' : '查看/修改' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-container">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="filteredTasks.length"
            @size-change="handlePaginationChange"
            @current-change="handlePaginationChange"
          />
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, inject, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { Document, Timer, Edit, Calendar, Search, Refresh, AlarmClock } from '@element-plus/icons-vue'

const router = useRouter()
const currentUser = inject('currentUser', ref(''))
const userEmail = computed(() => currentUser.value)

const loading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('')
const filteredTasks = ref([])

const currentPage = ref(1)
const pageSize = ref(10)

const allTasks = ref([])

const loadTasks = async () => {
  if (!userEmail.value) {
    return
  }
  
  loading.value = true
  try {
    const res = await axios.get(`/api/fill/user/tasks?userEmail=${encodeURIComponent(userEmail.value)}`)
    const pending = res.data.pending || []
    const completed = res.data.completed || []
    const expired = res.data.expired || []
    
    allTasks.value = [
      ...pending.map(t => ({...t, taskStatus: 'pending'})),
      ...completed.map(t => ({...t, taskStatus: 'completed'})),
      ...expired.map(t => ({...t, taskStatus: 'expired'}))
    ]
    
    handleFilter()
  } catch (e) {
    ElMessage.error('无法同步任务列表，请检查网络')
  } finally {
    loading.value = false
  }
}

const handleFilter = () => {
  currentPage.value = 1
  filteredTasks.value = allTasks.value.filter(task => {
    const matchesSearch = !searchQuery.value || 
      task.name.toLowerCase().includes(searchQuery.value.toLowerCase())
      
    const matchesStatus = !statusFilter.value || task.taskStatus === statusFilter.value
    
    return matchesSearch && matchesStatus
  })
}

const resetFilter = () => {
  searchQuery.value = ''
  statusFilter.value = ''
  handleFilter()
}

const paginatedTasks = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredTasks.value.slice(start, end)
})

const handlePaginationChange = () => {
  // purely distinct frontend pagination, computed handles it automatically
}

onMounted(() => {
  if (userEmail.value) {
    loadTasks()
  }
})

watch(userEmail, (newEmail) => {
  if (newEmail) {
    loadTasks()
  }
})

const formatTimeLeft = (seconds) => {
  if (seconds <= 0) return '已到期'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  
  if (days > 0) return `${days}天 ${hours}小时`
  if (hours > 0) return `${hours}小时 ${minutes}分钟`
  return `${minutes}分钟`
}
</script>

<style scoped>
.user-tasks-page {
  animation: fadeIn 0.4s ease-out;
}

.empty-state {
  padding: 80px 0;
  background: white;
  border-radius: 16px;
  border: 2px dashed #e2e8f0;
}

.filter-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
  background: white;
  padding: 16px;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
  border: 1px solid #f1f5f9;
}

.search-input {
  width: 320px;
}

.status-select {
  width: 160px;
}

.table-card {
  padding: 8px;
  border-radius: 12px;
}

.form-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.form-icon {
  font-size: 20px;
  color: #94a3b8;
}

.name-text {
  font-weight: 600;
  color: #334155;
}

.table-code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: #f1f5f9;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
  color: #475569;
}

.time-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #64748b;
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
  padding: 0 8px;
}

:deep(.el-table__header) {
  background-color: #f8fafc;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
