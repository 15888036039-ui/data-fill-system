<template>
  <div class="form-list-page">
    <div class="page-layout">
      <el-card class="folder-card" shadow="never">
        <div class="folder-card-header">
          <div>
            <div class="folder-title">模板目录</div>
            <div class="folder-subtitle">支持多级目录组织模板</div>
          </div>
          <el-button v-if="isAdmin" type="primary" plain size="small" @click="createFolder()">新建目录</el-button>
        </div>

        <div class="folder-tree-panel" v-loading="folderLoading">
          <div
            class="folder-all-item"
            :class="{ active: !selectedFolderId }"
            @click="selectAllFolders"
          >
            <span>全部模板</span>
            <el-tag size="small" round>{{ allTemplateCount }}</el-tag>
          </div>

          <el-tree
            v-if="folderTree.length > 0"
            ref="folderTreeRef"
            :data="folderTree"
            node-key="id"
            default-expand-all
            highlight-current
            :draggable="isAdmin"
            :expand-on-click-node="false"
            :props="{ label: 'name', children: 'children' }"
            :current-node-key="selectedFolderId || undefined"
            :allow-drop="allowFolderDrop"
            @node-click="handleFolderSelect"
            @node-contextmenu="handleFolderContextMenu"
            @node-drop="handleFolderDrop"
          >
            <template #default="{ data }">
              <div class="folder-node">
                <div class="folder-node-main">
                  <el-icon class="folder-node-icon"><Folder /></el-icon>
                  <span class="folder-node-name">{{ data.name }}</span>
                  <el-tag size="small" round>{{ data.templateCount || 0 }}</el-tag>
                </div>
                <div v-if="isAdmin && !data.systemNode" class="folder-node-actions">
                  <el-button link type="primary" @click.stop="createFolder(data)">新增</el-button>
                  <el-button link type="primary" @click.stop="renameFolder(data)">重命名</el-button>
                  <el-button link type="danger" @click.stop="deleteFolder(data)">删除</el-button>
                </div>
              </div>
            </template>
          </el-tree>

          <el-empty v-else-if="!folderLoading" description="暂无目录，模板将归入未分类" :image-size="72" />
        </div>

        <div
          v-if="contextMenu.visible"
          class="folder-context-menu"
          :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        >
          <button class="context-menu-item" @click="handleContextMenuAction('create')">新建子目录</button>
          <button class="context-menu-item" @click="handleContextMenuAction('rename')">重命名目录</button>
          <button class="context-menu-item danger" @click="handleContextMenuAction('delete')">删除目录</button>
        </div>
      </el-card>

      <div class="content-panel">
        <div class="filter-bar">
          <el-input
            v-model="searchQuery"
            placeholder="搜索模板名称或物理表名..."
            prefix-icon="Search"
            clearable
            class="search-input"
          />
          <el-select v-model="statusFilter" placeholder="模板状态" clearable class="status-select">
            <el-option label="所有状态" value="" />
            <el-option label="运行中" value="ACTIVE" />
            <el-option label="已过期" value="EXPIRED" />
            <el-option label="待发布" value="DISABLED" />
          </el-select>
          <el-button type="primary" icon="Search" @click="loadForms">查询</el-button>
          <el-button icon="Refresh" @click="resetFilter">重置</el-button>
        </div>

        <div class="toolbar-row">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item @click="selectAllFolders">
              <span class="crumb-link">全部模板</span>
            </el-breadcrumb-item>
            <el-breadcrumb-item v-for="item in selectedFolderCrumbs" :key="item.id">
              <span class="crumb-link" @click="selectFolderById(item.id)">{{ item.name }}</span>
            </el-breadcrumb-item>
          </el-breadcrumb>

          <div class="toolbar-meta">
            <el-button
              v-if="isAdmin"
              type="warning"
              plain
              :disabled="selectedFormIds.length === 0"
              @click="openBatchMoveDialog"
            >
              批量移动
            </el-button>
            <el-tag v-if="selectedFolderLabel" type="info" effect="plain" round>当前目录：{{ selectedFolderLabel }}</el-tag>
            <el-tag v-if="isAdmin && selectedFormIds.length > 0" type="warning" effect="plain" round>已选 {{ selectedFormIds.length }} 个</el-tag>
            <el-tag type="success" effect="plain" round>模板数：{{ filteredForms.length }}</el-tag>
          </div>
        </div>

        <el-card class="table-card" shadow="never">
          <el-table
            :data="paginatedForms"
            style="width: 100%"
            v-loading="loading"
            @selection-change="handleSelectionChange"
          >
            <el-table-column v-if="isAdmin" type="selection" width="48" align="center" />
            <el-table-column prop="name" label="模板名称" min-width="220">
              <template #default="scope">
                <div class="form-name-cell">
                  <el-icon class="form-icon"><Document /></el-icon>
                  <span class="name-text">{{ scope.row.name }}</span>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column prop="folderId" label="所属目录" min-width="220">
              <template #default="scope">
                <span class="folder-path-text">{{ resolveFolderPath(scope.row.folderId) }}</span>
              </template>
            </el-table-column>
            
            <el-table-column prop="tableName" label="物理表名" min-width="180">
              <template #default="scope">
                <code class="table-code">{{ scope.row.tableName }}</code>
              </template>
            </el-table-column>

            <el-table-column prop="status" label="状态" min-width="120" align="center">
              <template #default="scope">
                <el-tag
                  :type="scope.row.status === 'ACTIVE' ? 'success' : (scope.row.status === 'EXPIRED' ? 'danger' : 'info')"
                  effect="light"
                  round
                >
                  {{ scope.row.status === 'ACTIVE' ? '运行中' : (scope.row.status === 'EXPIRED' ? '已过期' : '待发布') }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="deadline" label="截止时间" min-width="180">
              <template #default="scope">
                <div class="time-cell">
                  <el-icon v-if="scope.row.deadline"><Timer /></el-icon>
                  <span>{{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleString() : '长期有效' }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="creator" label="创建人" min-width="140">
              <template #default="scope">
                <div class="creator-cell">
                  <el-icon><User /></el-icon>
                  <span>{{ scope.row.creator || '管理员' }}</span>
                </div>
              </template>
            </el-table-column>

            <el-table-column prop="createTime" label="创建时间" min-width="180">
              <template #default="scope">
                <span class="time-muted">{{ scope.row.createTime ? scope.row.createTime.replace('T', ' ') : '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="350" align="right" fixed="right">
              <template #default="scope">
                <el-button-group>
                  <el-tooltip content="查看并管理数据" placement="top">
                    <el-button type="info" size="small" plain icon="View" @click="$router.push(`/fill/${scope.row.id}?admin=true`)">数据</el-button>
                  </el-tooltip>
                  <el-tooltip v-if="isAdmin" content="编辑模板配置" placement="top">
                    <el-button type="primary" size="small" plain icon="Edit" @click="$router.push(`/designer/${scope.row.id}`)">设计</el-button>
                  </el-tooltip>
                  <el-tooltip v-if="isAdmin" content="移动到其他目录" placement="top">
                    <el-button type="warning" size="small" plain @click="openMoveDialog(scope.row)">移动</el-button>
                  </el-tooltip>
                  <el-popconfirm
                    v-if="isAdmin"
                    title="警告: 此操作将永久物理删除该表及其所有数据，无法恢复。确定删除吗？"
                    confirm-button-text="确定删除"
                    cancel-button-text="取消"
                    confirm-button-type="danger"
                    @confirm="handleDeleteForm(scope.row.id)"
                  >
                    <template #reference>
                      <el-button type="danger" size="small" plain icon="Delete">删除</el-button>
                    </template>
                  </el-popconfirm>
                </el-button-group>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              layout="total, sizes, prev, pager, next, jumper"
              :total="filteredForms.length"
              @size-change="handlePaginationChange"
              @current-change="handlePaginationChange"
            />
          </div>
        </el-card>
      </div>
    </div>

    <el-dialog v-model="moveDialogVisible" title="移动模板目录" width="460px">
      <div class="move-dialog-body">
        <div class="move-form-name">{{ movingForm?.name }}</div>
        <el-select
          v-model="moveTargetFolderId"
          clearable
          filterable
          placeholder="选择目标目录，不选则归入未分类"
          style="width: 100%"
        >
          <el-option label="未分类" value="" />
          <el-option
            v-for="item in folderOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="moveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmMoveForm">确认移动</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchMoveDialogVisible" title="批量移动模板目录" width="460px">
      <div class="move-dialog-body">
        <div class="move-form-name">已选择 {{ selectedFormIds.length }} 个模板</div>
        <el-select
          v-model="batchMoveTargetFolderId"
          clearable
          filterable
          placeholder="选择目标目录，不选则归入未分类"
          style="width: 100%"
        >
          <el-option label="未分类" value="" />
          <el-option
            v-for="item in folderOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      <template #footer>
        <el-button @click="batchMoveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmBatchMove">确认移动</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, inject, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder, Timer, User } from '@element-plus/icons-vue'
import axios from 'axios'

const route = useRoute()
const router = useRouter()
const currentUser = inject('currentUser', ref(''))
const isAdmin = inject('isAdmin', ref(false))

const forms = ref([])
const loading = ref(false)
const folderTreeRef = ref(null)
const folderTree = ref([])
const folderLoading = ref(false)
const selectedFolderId = ref(route.query.folderId || '')
const searchQuery = ref('')
const statusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const moveDialogVisible = ref(false)
const batchMoveDialogVisible = ref(false)
const movingForm = ref(null)
const moveTargetFolderId = ref('')
const batchMoveTargetFolderId = ref('')
const selectedFormIds = ref([])
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  folder: null
})

const filteredForms = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  return forms.value.filter(form => {
    const matchesSearch = !keyword ||
      (form.name || '').toLowerCase().includes(keyword) ||
      (form.tableName || '').toLowerCase().includes(keyword)
    const matchesStatus = !statusFilter.value || form.status === statusFilter.value
    return matchesSearch && matchesStatus
  })
})

const paginatedForms = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredForms.value.slice(start, end)
})

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

const folderOptions = computed(() => {
  const options = []
  Object.entries(folderPathMap.value).forEach(([id, segments]) => {
    if (id === '__uncategorized__') return
    options.push({ value: id, label: segments.join(' / ') })
  })
  return options
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

const allTemplateCount = computed(() => {
  return folderTree.value.reduce((sum, node) => sum + (node.templateCount || 0), 0)
})

const updateRouteFolder = async (folderId) => {
  const query = { ...route.query }
  if (folderId) {
    query.folderId = folderId
  } else {
    delete query.folderId
  }
  await router.replace({ query })
}

const findFolderIdByPath = (segments) => {
  const pathText = segments.join(' / ')
  const target = folderOptions.value.find(item => item.label === pathText)
  return target?.value || ''
}

const resolveFolderPath = (folderId) => {
  if (!folderId) return '未分类'
  const segments = folderPathMap.value[folderId]
  return segments && segments.length > 0 ? segments.join(' / ') : '未分类'
}

const handlePaginationChange = () => {}

const handleSelectionChange = (rows) => {
  selectedFormIds.value = (rows || []).map(row => row.id)
}

const hideContextMenu = () => {
  contextMenu.value = {
    visible: false,
    x: 0,
    y: 0,
    folder: null
  }
}

const resetFilter = async () => {
  searchQuery.value = ''
  statusFilter.value = ''
  currentPage.value = 1
  if (selectedFolderId.value) {
    selectedFolderId.value = ''
    await updateRouteFolder('')
  }
  await loadForms()
}

const loadFolders = async () => {
  folderLoading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    const res = await axios.get('/api/fill/folders/tree', { params })
    folderTree.value = res.data || []
  } catch (e) {
    folderTree.value = []
    ElMessage.error(e.response?.data?.message || '获取目录失败')
  } finally {
    folderLoading.value = false
  }
}

const loadForms = async () => {
  loading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    if (selectedFolderId.value) params.folderId = selectedFolderId.value
    const res = await axios.get('/api/fill/forms', { params })
    forms.value = res.data || []
    currentPage.value = 1
    selectedFormIds.value = []
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '获取表单模板失败')
  } finally {
    loading.value = false
  }
}

const selectAllFolders = async () => {
  selectedFolderId.value = ''
  await updateRouteFolder('')
  await loadForms()
}

const selectFolderById = async (folderId) => {
  selectedFolderId.value = folderId
  await updateRouteFolder(folderId)
  await loadForms()
}

const handleFolderSelect = async (data) => {
  hideContextMenu()
  await selectFolderById(data.id)
}

const handleFolderContextMenu = (event, data) => {
  if (!isAdmin.value || !data || data.systemNode) {
    hideContextMenu()
    return
  }
  event.preventDefault()
  contextMenu.value = {
    visible: true,
    x: Math.min(event.clientX, window.innerWidth - 180),
    y: Math.min(event.clientY, window.innerHeight - 140),
    folder: data
  }
}

const handleContextMenuAction = async (action) => {
  const folder = contextMenu.value.folder
  hideContextMenu()
  if (!folder) return
  if (action === 'create') {
    await createFolder(folder)
  } else if (action === 'rename') {
    await renameFolder(folder)
  } else if (action === 'delete') {
    await deleteFolder(folder)
  }
}

const createFolder = async (parentNode = null) => {
  try {
    const result = await ElMessageBox.prompt('请输入目录名称', parentNode ? `在“${parentNode.name}”下新建目录` : '新建根目录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空'
    })
    await axios.post('/api/fill/folders', {
      name: result.value,
      parentId: parentNode?.systemNode ? null : (parentNode?.id || null)
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('目录创建成功')
    await loadFolders()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '目录创建失败')
    }
  }
}

const renameFolder = async (folder) => {
  try {
    const result = await ElMessageBox.prompt('修改目录名称', '重命名目录', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValue: folder.name,
      inputPattern: /\S+/,
      inputErrorMessage: '目录名称不能为空'
    })
    await axios.put(`/api/fill/folders/${folder.id}`, {
      name: result.value,
      parentId: folder.parentId || null,
      sortOrder: folder.sortOrder || 0
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('目录已重命名')
    await loadFolders()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '目录修改失败')
    }
  }
}

const deleteFolder = async (folder) => {
  try {
    await ElMessageBox.confirm(`确定删除目录“${folder.name}”吗？`, '删除目录', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await axios.delete(`/api/fill/folders/${folder.id}`, {
      params: { userEmail: currentUser.value }
    })
    if (selectedFolderId.value === folder.id) {
      selectedFolderId.value = ''
      await updateRouteFolder('')
    }
    ElMessage.success('目录已删除')
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e.response?.data?.message || '删除目录失败')
    }
  }
}

const openMoveDialog = (form) => {
  movingForm.value = form
  moveTargetFolderId.value = form.folderId || ''
  moveDialogVisible.value = true
}

const openBatchMoveDialog = () => {
  batchMoveTargetFolderId.value = ''
  batchMoveDialogVisible.value = true
}

const confirmMoveForm = async () => {
  if (!movingForm.value) return
  try {
    await axios.put(`/api/fill/forms/${movingForm.value.id}/folder`, {
      folderId: moveTargetFolderId.value || null
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('模板目录已更新')
    moveDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '移动模板失败')
  }
}

const confirmBatchMove = async () => {
  if (selectedFormIds.value.length === 0) {
    ElMessage.warning('请先选择要移动的模板')
    return
  }
  try {
    await axios.put('/api/fill/forms/folder/batch', {
      formIds: selectedFormIds.value,
      folderId: batchMoveTargetFolderId.value || null
    }, {
      params: { userEmail: currentUser.value }
    })
    ElMessage.success('模板已批量移动')
    batchMoveDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '批量移动失败')
  }
}

const allowFolderDrop = (draggingNode, dropNode, type) => {
  const draggingData = draggingNode?.data
  const dropData = dropNode?.data
  if (!draggingData || !dropData) return false
  if (draggingData.systemNode || dropData.systemNode) return false
  if (type === 'inner' && draggingData.id === dropData.id) return false
  return true
}

const buildFolderReorderPayload = (nodes, parentId = null, result = []) => {
  ;(nodes || []).forEach((node, index) => {
    if (!node.systemNode) {
      result.push({
        id: node.id,
        parentId,
        sortOrder: (index + 1) * 10
      })
      buildFolderReorderPayload(node.children || [], node.id, result)
    }
  })
  return result
}

const handleFolderDrop = async (draggingNode, dropNode, dropType) => {
  const draggingFolder = draggingNode?.data
  const dropFolder = dropNode?.data
  try {
    const payload = buildFolderReorderPayload(folderTree.value.filter(node => !node.systemNode))
    await axios.put('/api/fill/folders/reorder', payload, {
      params: { userEmail: currentUser.value }
    })
    let successText = '目录顺序已更新'
    if (draggingFolder?.name && dropFolder?.name) {
      if (dropType === 'inner') {
        successText = `已将“${draggingFolder.name}”移动到“${dropFolder.name}”下`
      } else if (dropType === 'before') {
        successText = `已将“${draggingFolder.name}”调整到“${dropFolder.name}”前面`
      } else if (dropType === 'after') {
        successText = `已将“${draggingFolder.name}”调整到“${dropFolder.name}”后面`
      }
    }
    ElMessage.success(successText)
    await loadFolders()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '目录拖拽保存失败')
    await loadFolders()
  }
}

const handleDeleteForm = async (id) => {
  try {
    await axios.delete(`/api/fill/forms/${id}`)
    ElMessage.success('模板及物理表已永久删除')
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除操作失败')
  }
}

onMounted(async () => {
  document.addEventListener('click', hideContextMenu)
  selectedFolderId.value = route.query.folderId || ''
  await Promise.all([loadFolders(), loadForms()])
})

onBeforeUnmount(() => {
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.form-list-page {
  animation: fadeIn 0.4s ease-out;
}

.page-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.folder-card {
  position: relative;
  width: 260px;
  flex-shrink: 0;
  border-radius: 14px;
  overflow: hidden;
}

.content-panel {
  flex: 1;
  min-width: 0;
}

.folder-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
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
  min-height: 360px;
  overflow: hidden;
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

.folder-node-actions {
  display: none;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.folder-node:hover .folder-node-actions {
  display: flex;
}

.folder-context-menu {
  position: fixed;
  z-index: 3000;
  min-width: 160px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.16);
  padding: 6px;
}

.context-menu-item {
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  padding: 9px 12px;
  border-radius: 8px;
  color: #334155;
  cursor: pointer;
  font-size: 13px;
}

.context-menu-item:hover {
  background: #f8fafc;
}

.context-menu-item.danger {
  color: #dc2626;
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

.time-cell,
.creator-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #64748b;
}

.time-muted {
  color: #64748b;
  font-size: 13px;
}

.move-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.move-form-name {
  font-size: 14px;
  color: #334155;
  font-weight: 600;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
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
  .folder-card {
    width: 236px;
  }
}
</style>
