<template>
  <div class="user-tasks-page">
    <div v-if="!userEmail" class="empty-state">
      <el-empty description="请在上方的身份确认框中输入您的邮箱以同步填报任务" />
    </div>

    <div v-else class="task-content">
      <div class="page-layout">
        <el-card class="folder-card" shadow="never">
          <div class="folder-card-header">
            <div class="folder-title">任务目录</div>
            <div class="folder-subtitle">仅展示你有权限查看的目录</div>
          </div>

          <div class="folder-tree-panel" v-loading="folderLoading">
            <div
              class="folder-all-item"
              :class="{ active: !selectedFolderId }"
              @click="selectAllFolders"
            >
              <span>全部任务</span>
              <el-tag size="small" round>{{ allTasks.length }}</el-tag>
            </div>

            <el-tree
              v-if="folderTree.length > 0"
              :data="folderTree"
              node-key="id"
              default-expand-all
              highlight-current
              :expand-on-click-node="false"
              :props="{ label: 'name', children: 'children' }"
              :current-node-key="selectedFolderId || undefined"
              @node-click="handleFolderSelect"
            >
              <template #default="{ data }">
                <div class="folder-node">
                  <div class="folder-node-main">
                    <el-icon class="folder-node-icon"><Folder /></el-icon>
                    <span class="folder-node-name">{{ data.name }}</span>
                  </div>
                  <el-tag size="small" round>{{ data.templateCount || 0 }}</el-tag>
                </div>
              </template>
            </el-tree>

            <el-empty v-else-if="!folderLoading" description="暂无目录" :image-size="72" />
          </div>
        </el-card>

        <div class="content-panel">
          <div class="view-header">
            <div class="view-header-left">
              <div class="view-title-row">
                <span class="view-title">{{ selectedFolderId ? selectedFolderLabel : '全部任务' }}</span>
                <el-tag type="success" effect="plain" round size="small" class="count-tag">
                  共 {{ filteredTasks.length }} 个任务
                </el-tag>
              </div>
            </div>
            
            <div class="view-header-right">
              <div class="search-bar-integrated">
                <el-input
                  v-model="searchQuery"
                  placeholder="搜索模板名称..."
                  prefix-icon="Search"
                  clearable
                  class="search-input-compact"
                  @keyup.enter="handleFilter"
                  @clear="handleFilter"
                />
                <el-select v-model="statusFilter" placeholder="状态" clearable class="status-select-compact">
                  <el-option label="所有状态" value="" />
                  <el-option label="待填报" value="pending" />
                  <el-option label="未开始" value="upcoming" />
                  <el-option label="已填报" value="completed" />
                  <el-option label="已截止" value="expired" />
                </el-select>
                <el-button type="primary" @click="handleFilter">查询</el-button>
                <el-button @click="resetFilter">重置</el-button>
                <div class="divider"></div>
                <el-button link type="primary" icon="Refresh" @click="loadTasks">刷新</el-button>
              </div>
            </div>
          </div>

          <el-card class="table-card" shadow="never">
            <el-table 
              :data="paginatedTasks" 
              style="width: 100%" 
              v-loading="loading"
              row-class-name="modern-table-row"
            >
              <el-table-column prop="name" label="模板名称" min-width="220">
                <template #default="scope">
                  <div class="form-name-cell">
                    <div class="icon-avatar">
                      <el-icon><Document /></el-icon>
                    </div>
                    <span class="name-text">{{ scope.row.name }}</span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column prop="folderPath" label="所属目录" min-width="120">
                <template #default="scope">
                  <div class="folder-path-cell">
                    <el-icon size="12"><Folder /></el-icon>
                    <span class="folder-path-text">{{ scope.row.folderPath || '默认' }}</span>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="任务详情" min-width="320">
                <template #default="scope">
                  <div class="task-info-cell">
                    <div class="status-tag-wrapper">
                      <el-tag
                        :type="scope.row.taskStatus === 'pending' ? 'warning' : (scope.row.taskStatus === 'upcoming' ? 'info' : (scope.row.taskStatus === 'completed' ? 'success' : 'danger'))"
                        effect="light"
                        round
                        size="small"
                        class="info-status-tag"
                      >
                        {{ scope.row.taskStatus === 'pending' ? '待填报' : (scope.row.taskStatus === 'upcoming' ? '未开始' : (scope.row.taskStatus === 'completed' ? '已填报' : '已截止')) }}
                      </el-tag>
                    </div>
                    
                    <div class="detail-info">
                      <template v-if="scope.row.taskStatus === 'pending'">
                        <span v-if="scope.row.secondsLeft !== null && scope.row.secondsLeft !== undefined" class="main-info countdown">
                          <el-icon><AlarmClock /></el-icon>
                          剩余: {{ formatTimeLeft(scope.row.secondsLeft) }}
                        </span>
                        <span class="sub-text">截止日期: {{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleString() : '长期有效' }}</span>
                      </template>

                      <template v-else-if="scope.row.taskStatus === 'upcoming'">
                        <span class="main-info">
                          <el-icon><Calendar /></el-icon>
                          预计状态: {{ formatTimeLeft(scope.row.secondsUntilStart) }} 后开启
                        </span>
                        <span class="sub-text">开启时间: {{ scope.row.startTimeOfCycle ? new Date(scope.row.startTimeOfCycle).toLocaleString() : '' }}</span>
                      </template>

                      <template v-else-if="scope.row.taskStatus === 'completed'">
                        <template v-if="scope.row.nextFillTime">
                          <span class="main-info success">
                            <el-icon><Calendar /></el-icon>
                            下次周期: {{ new Date(scope.row.nextFillTime).toLocaleString() }}
                          </span>
                          <span v-if="scope.row.lastSubmitTime" class="sub-text">
                            最近提交: {{ new Date(scope.row.lastSubmitTime).toLocaleString() }}
                          </span>
                        </template>
                        <template v-else>
                          <span class="main-info">
                            <el-icon><Check /></el-icon>
                            完成于: {{ scope.row.lastSubmitTime ? new Date(scope.row.lastSubmitTime).toLocaleString() : '---' }}
                          </span>
                        </template>
                      </template>
                      
                      <template v-else>
                        <span class="main-info expired">
                          <el-icon><CircleClose /></el-icon>
                          截止于: {{ new Date(scope.row.deadline).toLocaleString() }}
                        </span>
                      </template>
                    </div>
                  </div>
                </template>
              </el-table-column>

              <el-table-column label="操作" width="140" align="right" fixed="right">
                <template #default="scope">
                  <el-button 
                    :type="scope.row.taskStatus === 'pending' ? 'primary' : 'info'" 
                    size="small"
                    :icon="scope.row.taskStatus === 'pending' ? 'Edit' : 'View'"
                    round
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
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { Document, Folder, Edit, Calendar, Search, Refresh, AlarmClock, Check, CircleClose, View } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const currentUser = inject('currentUser', ref(''))
const userEmail = computed(() => currentUser.value)

const loading = ref(false)
const folderLoading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('')
const selectedFolderId = ref('')
const filteredTasks = ref([])

const currentPage = ref(1)
const pageSize = ref(10)

const allTasks = ref([])
const folderTree = ref([])
const folderPathMap = computed(() => {
  const map = {}
  const walk = (nodes, parents = []) => {
    ;(nodes || []).forEach(node => {
      const nextParents = [...parents, node.name]
      map[node.id] = nextParents
      walk(node.children, nextParents)
    })
  }
  walk(folderTree.value)
  return map
})
const selectedFolderCrumbs = computed(() => {
  if (!selectedFolderId.value) return []
  const segments = folderPathMap.value[selectedFolderId.value] || ['默认']
  return segments.map((name, index) => ({
    id: index === segments.length - 1 ? selectedFolderId.value : findFolderIdByPath(segments.slice(0, index + 1)),
    name
  })).filter(item => item.id)
})
const selectedFolderLabel = computed(() => {
  if (!selectedFolderId.value) return ''
  if (selectedFolderId.value === '__uncategorized__') return '默认'
  return (folderPathMap.value[selectedFolderId.value] || []).join(' / ')
})

const loadTasks = async () => {
  if (!userEmail.value) {
    return
  }
  
  loading.value = true
  try {
    const params = {
      userEmail: userEmail.value
    }
    if (route.query.groupTag) {
      params.groupTag = route.query.groupTag
    }
    
    const res = await axios.get('/api/fill/user/tasks', { params })
    const pending = res.data.pending || []
    const completed = res.data.completed || []
    const expired = res.data.expired || []
    
    allTasks.value = [
      ...pending,
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

const loadFolders = async () => {
  if (!userEmail.value) return
  folderLoading.value = true
  try {
    const params = {
      userEmail: userEmail.value
    }
    if (route.query.groupTag) {
      params.groupTag = route.query.groupTag
    }
    const res = await axios.get('/api/fill/folders/tree', { params })
    folderTree.value = res.data || []
  } catch (e) {
    folderTree.value = []
    ElMessage.error('无法加载目录信息')
  } finally {
    folderLoading.value = false
  }
}

const handleFilter = () => {
  currentPage.value = 1
  filteredTasks.value = allTasks.value.filter(task => {
    const matchesSearch = !searchQuery.value || 
      task.name.toLowerCase().includes(searchQuery.value.toLowerCase()) ||
      (task.folderPath || '默认').toLowerCase().includes(searchQuery.value.toLowerCase())
      
    const matchesStatus = !statusFilter.value || task.taskStatus === statusFilter.value
    const matchesFolder = !selectedFolderId.value ||
      (selectedFolderId.value === '__uncategorized__'
        ? (!task.folderId || task.folderId === '')
        : task.folderId === selectedFolderId.value)
    
    return matchesSearch && matchesStatus && matchesFolder
  })
}

const resetFilter = () => {
  searchQuery.value = ''
  statusFilter.value = ''
  selectedFolderId.value = ''
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

watch(userEmail, (newEmail) => {
  if (newEmail) {
    loadFolders()
    loadTasks()
  }
})

let timer = null
onMounted(() => {
  if (userEmail.value) {
    loadFolders()
    loadTasks()
  }
  timer = setInterval(() => {
    allTasks.value.forEach(task => {
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

const selectAllFolders = () => {
  selectedFolderId.value = ''
  handleFilter()
}

const selectFolderById = (folderId) => {
  selectedFolderId.value = folderId
  handleFilter()
}

const handleFolderSelect = (data) => {
  selectFolderById(data.id)
}

const findFolderIdByPath = (segments) => {
  const pathText = segments.join(' / ')
  const target = Object.entries(folderPathMap.value).find(([, value]) => value.join(' / ') === pathText)
  return target?.[0] || ''
}

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

.page-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.folder-card {
  width: 280px;
  flex-shrink: 0;
  border-radius: 16px;
  overflow: hidden;
  border: 1px solid #f1f5f9;
  background: white;
  transition: all 0.3s;
}

.folder-card:hover {
  box-shadow: 0 12px 24px -10px rgba(15, 23, 42, 0.1);
}

.folder-card-header {
  padding: 16px 12px 12px;
  border-bottom: 1px solid #f1f5f9;
  margin-bottom: 8px;
}

.folder-title {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: 0.5px;
}

.folder-subtitle {
  display: none; /* 精简 UI */
}

.folder-tree-panel {
  padding: 0 12px 20px;
  max-height: calc(100vh - 240px);
  overflow-y: auto;
}

.folder-all-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  cursor: pointer;
  color: #64748b;
  margin-bottom: 4px;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  font-size: 13.5px;
  border-radius: 8px;
}

.folder-all-item:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.folder-all-item.active {
  color: white;
  font-weight: 600;
  background: var(--primary-color);
  box-shadow: 0 4px 6px -1px rgba(37, 99, 235, 0.2);
}

.folder-node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-right: 4px;
  min-width: 0;
  overflow: hidden;
}

.folder-node-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.folder-node-icon {
  color: #94a3b8;
}

.folder-node-name {
  display: block;
  flex: 1;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #334155;
}

.content-panel {
  flex: 1;
  min-width: 0;
  width: 0;
  padding-bottom: 24px;
}

.view-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  margin-bottom: 16px;
  padding: 0 4px;
}

.view-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.view-title {
  font-size: 18px;
  font-weight: 800;
  color: #1e293b;
  letter-spacing: -0.5px;
}

.count-tag {
  font-weight: 600;
  border: none;
  background: #f0fdf4;
}

.search-bar-integrated {
  display: flex;
  align-items: center;
  gap: 8px;
  background: white;
  padding: 4px;
  border-radius: 12px;
}

.search-input-compact {
  width: 240px;
}

.search-input-compact :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: #f1f5f9;
  border-radius: 8px;
}

.status-select-compact {
  width: 110px;
}

.status-select-compact :deep(.el-input__wrapper) {
  box-shadow: none !important;
  background: #f1f5f9;
  border-radius: 8px;
}

.divider {
  width: 1px;
  height: 20px;
  background: #e2e8f0;
  margin: 0 4px;
}

.table-card {
  border-radius: 16px;
  border: 1px solid #f1f5f9;
  box-shadow: 0 4px 20px -5px rgba(15, 23, 42, 0.04);
  padding: 4px;
}

.modern-table-row {
  transition: background-color 0.2s;
}

.modern-table-row:hover {
  background-color: #f8fafc !important;
}

.icon-avatar {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  flex-shrink: 0;
  transition: all 0.2s;
}

.modern-table-row:hover .icon-avatar {
  background: #e2e8f0;
  color: var(--primary-color);
}

.form-name-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name-text {
  font-weight: 600;
  color: #1e293b;
  font-size: 15px;
}

.folder-path-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 13px;
}

.task-info-cell {
  display: flex;
  align-items: center;
  gap: 16px;
}

.status-tag-wrapper {
  flex-shrink: 0;
  width: 80px;
}

.info-status-tag {
  width: 100%;
  justify-content: center;
  border: none;
}

.detail-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.main-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

.main-info.countdown { color: #d97706; }
.main-info.expired { color: #ef4444; }
.main-info.success { color: #10b981; }

.sub-text {
  font-size: 12px;
  color: #94a3b8;
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
  padding: 0 8px;
}

:deep(.el-table__header-wrapper th) {
  background-color: #f8fafc;
  color: #475569;
  font-weight: 600;
  font-size: 13px;
  height: 48px;
}

@media (max-width: 1280px) {
  .page-layout {
    flex-direction: column;
    align-items: stretch;
  }
  .folder-card {
    width: 100%;
    margin-bottom: 16px;
  }
  .folder-tree-panel {
    max-height: 240px;
  }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
