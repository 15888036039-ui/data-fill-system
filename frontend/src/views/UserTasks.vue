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
              <el-option label="未开始" value="upcoming" />
              <el-option label="已填报" value="completed" />
              <el-option label="已截止" value="expired" />
            </el-select>
            <el-button type="primary" icon="Search" @click="handleFilter">查询</el-button>
            <el-button icon="Refresh" @click="resetFilter">重置</el-button>
          </div>

          <div class="toolbar-row">
            <el-breadcrumb separator="/">
              <el-breadcrumb-item @click="selectAllFolders">
                <span class="crumb-link">全部任务</span>
              </el-breadcrumb-item>
              <el-breadcrumb-item v-for="item in selectedFolderCrumbs" :key="item.id">
                <span class="crumb-link" @click="selectFolderById(item.id)">{{ item.name }}</span>
              </el-breadcrumb-item>
            </el-breadcrumb>

            <div class="toolbar-meta">
              <el-tag v-if="selectedFolderLabel" type="info" effect="plain" round>当前目录：{{ selectedFolderLabel }}</el-tag>
              <el-tag type="success" effect="plain" round>任务数：{{ filteredTasks.length }}</el-tag>
            </div>
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

          <el-table-column prop="folderPath" label="所属目录" min-width="220">
            <template #default="scope">
              <span class="folder-path-text">{{ scope.row.folderPath || '未分类' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" min-width="140" align="left">
            <template #default="scope">
              <el-tag
                :type="scope.row.taskStatus === 'pending' ? 'warning' : (scope.row.taskStatus === 'upcoming' ? 'info' : (scope.row.taskStatus === 'completed' ? 'success' : 'danger'))"
                effect="light"
                round
              >
                {{ scope.row.taskStatus === 'pending' ? '待填报' : (scope.row.taskStatus === 'upcoming' ? '未开始' : (scope.row.taskStatus === 'completed' ? '已填报' : '已截止')) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="填报倒计时 / 填报记录" min-width="280">
            <template #default="scope">
              <div class="time-cell">
                <template v-if="scope.row.taskStatus === 'pending'">
                  <el-icon><AlarmClock /></el-icon>
                  <span style="color: #d97706; font-weight: 600;">
                    {{ formatTimeLeft(scope.row.secondsLeft) }}
                  </span>
                  <span class="sub-text">(截止: {{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleString() : '长期有效' }})</span>
                </template>

                <template v-else-if="scope.row.taskStatus === 'upcoming'">
                  <el-icon><Calendar /></el-icon>
                  <span style="color: #64748b;">
                    预计开始: {{ formatTimeLeft(scope.row.secondsUntilStart) }} 后
                  </span>
                  <span class="sub-text">(开启: {{ scope.row.startTimeOfCycle ? new Date(scope.row.startTimeOfCycle).toLocaleString() : '' }})</span>
                </template>

                <template v-else-if="scope.row.taskStatus === 'completed'">
                  <el-icon v-if="scope.row.nextFillTime"><Calendar /></el-icon>
                  <el-icon v-else><Check /></el-icon>
                  <div class="completed-time-info">
                    <span v-if="scope.row.nextFillTime" style="color: #059669;">
                      下次填报: {{ new Date(scope.row.nextFillTime).toLocaleString() }}
                    </span>
                    <span v-else style="color: #64748b;">已填报</span>
                    <span v-if="scope.row.lastSubmitTime" class="sub-text">
                      (已于 {{ new Date(scope.row.lastSubmitTime).toLocaleString() }} 完成)
                    </span>
                  </div>
                </template>
                
                <template v-else>
                  <el-icon><CircleClose /></el-icon>
                  <span style="color: #ef4444;">已逾期</span>
                  <span class="sub-text">(截止于: {{ new Date(scope.row.deadline).toLocaleString() }})</span>
                </template>
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
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch, inject, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { Document, Folder, Edit, Calendar, Search, Refresh, AlarmClock, Check, CircleClose } from '@element-plus/icons-vue'

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
  const segments = folderPathMap.value[selectedFolderId.value] || ['未分类']
  return segments.map((name, index) => ({
    id: index === segments.length - 1 ? selectedFolderId.value : findFolderIdByPath(segments.slice(0, index + 1)),
    name
  })).filter(item => item.id)
})
const selectedFolderLabel = computed(() => {
  if (!selectedFolderId.value) return ''
  if (selectedFolderId.value === '__uncategorized__') return '未分类'
  return (folderPathMap.value[selectedFolderId.value] || []).join(' / ')
})

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
    const res = await axios.get(`/api/fill/folders/tree?userEmail=${encodeURIComponent(userEmail.value)}`)
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
      (task.folderPath || '未分类').toLowerCase().includes(searchQuery.value.toLowerCase())
      
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

onMounted(() => {
  if (userEmail.value) {
    loadFolders()
    loadTasks()
  }
})

watch(userEmail, (newEmail) => {
  if (newEmail) {
    loadFolders()
    loadTasks()
  }
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

.page-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.folder-card {
  width: 260px;
  flex-shrink: 0;
  border-radius: 14px;
  overflow: hidden;
}

.folder-card-header {
  margin-bottom: 12px;
}

.folder-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.folder-subtitle {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.folder-tree-panel {
  min-height: 480px;
  max-height: 480px;
  overflow-y: auto;
}

.folder-all-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: #334155;
  background: #f8fafc;
  margin-bottom: 8px;
  transition: all 0.2s ease;
  font-size: 13px;
}

.folder-all-item.active {
  color: var(--primary-color);
  background: rgba(64, 158, 255, 0.08);
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
  gap: 6px;
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
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.content-panel {
  flex: 1;
  min-width: 0;
}

.filter-bar {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
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

.toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.toolbar-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.crumb-link {
  cursor: pointer;
  color: #475569;
}

.crumb-link:hover {
  color: var(--primary-color);
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

.folder-path-text {
  color: #475569;
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
  gap: 8px;
  font-size: 13px;
  color: #64748b;
}

.sub-text {
  font-size: 12px;
  color: #94a3b8;
  margin-left: 4px;
}

.completed-time-info {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
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

:deep(.el-tree-node__content) {
  min-height: 34px;
  border-radius: 6px;
  padding-right: 6px;
}

:deep(.el-tree) {
  overflow-x: hidden;
}

:deep(.el-tree-node) {
  overflow: hidden;
}

:deep(.el-tree-node > .el-tree-node__content) {
  overflow: hidden;
}

:deep(.el-tree .el-scrollbar__wrap) {
  overflow-x: hidden !important;
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
    min-height: 200px;
    max-height: 200px;
  }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
