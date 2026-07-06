<template>
  <div class="form-list-page">
    <div class="page-layout">
      <el-card class="folder-card" shadow="never">
        <div class="folder-header">
          <span class="folder-header-title">模板目录</span>
          <el-button v-if="isAdmin" type="primary" link icon="Plus" @click="createFolder()">新建</el-button>
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
                  <!-- 增加 Tooltip：当目录名过长显示省略号时，悬停可查看完整名称 -->
                  <el-tooltip :content="data.name" placement="top" :show-after="200" :enterable="false" :disabled="!data.name">
                    <span class="folder-node-name">{{ data.name }}</span>
                  </el-tooltip>
                  <el-tag size="small" round class="folder-count-tag">{{ data.templateCount || 0 }}</el-tag>
                </div>
                <div v-if="isAdmin && !data.systemNode" class="folder-node-actions">
                  <el-tooltip content="新增子目录" placement="top"><el-button link type="primary" icon="Plus" @click.stop="createFolder(data)" /></el-tooltip>
                  <el-tooltip content="重命名" placement="top"><el-button link type="primary" icon="Edit" @click.stop="renameFolder(data)" /></el-tooltip>
                  <el-tooltip content="删除目录" placement="top"><el-button link type="danger" icon="Delete" @click.stop="deleteFolder(data)" /></el-tooltip>
                </div>
              </div>
            </template>
          </el-tree>

          <el-empty v-else-if="!folderLoading" description="暂无目录，模板将归入默认" :image-size="72" />
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

        <!-- 侧边栏宽度调节手柄：位于卡片最右侧的透明条，允许鼠标拖拽缩放 -->
        <div class="sidebar-resizer" @mousedown="startSidebarResize"></div>
      </el-card>

      <div class="content-panel">
        <div class="view-header">
          <div class="view-header-left">
            <div class="view-title-row">
              <span class="view-title">全部模板</span>
              <el-tag type="success" effect="plain" round size="small" class="count-tag">
                共 {{ filteredForms.length }} 个模板
              </el-tag>
            </div>
          </div>
          
          <div class="view-header-right">
            <div class="search-bar-integrated">
              <el-input
                v-model="searchQuery"
                placeholder="搜索模板名称或物理表名..."
                prefix-icon="Search"
                clearable
                class="search-input-compact"
                @keyup.enter="handleFilter"
                @clear="handleFilter"
              />
              <el-select v-model="statusFilter" placeholder="状态" clearable class="status-select-compact">
                <el-option label="所有状态" value="" />
                <el-option label="运行中" value="ACTIVE" />
                <el-option label="已过期" value="EXPIRED" />
                <el-option label="待发布" value="DISABLED" />
              </el-select>
              <el-button type="primary" @click="handleFilter">查询</el-button>
              <el-button @click="resetFilter">重置</el-button>
              <div class="divider"></div>
              <el-button
                v-if="isAdmin"
                type="warning"
                plain
                icon="Rank"
                :disabled="selectedFormIds.length === 0"
                @click="openBatchMoveDialog"
              >
                批量移动
              </el-button>
            </div>
          </div>
        </div>

        <el-card class="table-card" shadow="never">
          <el-table
            :data="paginatedForms"
            style="width: 100%"
            v-loading="loading"
            @selection-change="handleSelectionChange"
            row-class-name="modern-table-row"
          >
            <el-table-column v-if="isAdmin" type="selection" width="48" align="center" />
            <el-table-column prop="name" label="模板名称" min-width="220" show-overflow-tooltip>
              <template #default="scope">
                <div class="form-name-cell">
                  <div class="icon-avatar">
                    <el-icon><Document /></el-icon>
                  </div>
                  <span class="name-text">{{ scope.row.name }}</span>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column prop="folderId" label="所属目录" min-width="130" show-overflow-tooltip>
              <template #default="scope">
                <div class="folder-path-cell">
                  <el-icon size="12"><Folder /></el-icon>
                  <span class="folder-path-text">{{ resolveFolderPath(scope.row.folderId) }}</span>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column prop="tableName" label="物理表名" min-width="160" show-overflow-tooltip>
              <template #default="scope">
                <code class="table-code">{{ scope.row.tableName }}</code>
              </template>
            </el-table-column>
  
            <el-table-column prop="status" label="状态" width="100" align="center">
              <template #default="scope">
                <el-tag
                  :type="scope.row.status === 'ACTIVE' ? 'success' : (scope.row.status === 'EXPIRED' ? 'danger' : 'info')"
                  effect="light"
                  round
                  size="small"
                  class="status-tag"
                >
                  {{ scope.row.status === 'ACTIVE' ? '运行中' : (scope.row.status === 'EXPIRED' ? '已过期' : '待发布') }}
                </el-tag>
              </template>
            </el-table-column>
  
            <el-table-column prop="deadline" label="截止时间" min-width="140">
              <template #default="scope">
                <div class="time-cell">
                  <el-icon v-if="scope.row.deadline" size="14"><Timer /></el-icon>
                  <span>{{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleDateString() : '长期有效' }}</span>
                </div>
              </template>
            </el-table-column>
  
            <el-table-column prop="creator" label="创建人" width="120" show-overflow-tooltip>
              <template #default="scope">
                <span class="creator-text">{{ scope.row.creator || 'admin' }}</span>
              </template>
            </el-table-column>
  
            <el-table-column label="操作" width="160" align="right" fixed="right">
              <template #default="scope">
                <div class="action-cell">
                  <el-tooltip content="查看/填写数据" placement="top">
                    <el-button circle size="small" type="primary" plain icon="List" @click="$router.push(`/fill/${scope.row.id}?admin=true` + (selectedFolderId ? `&folderId=${selectedFolderId}` : ''))" />
                  </el-tooltip>
                  <el-tooltip v-if="isAdmin" content="设计模板" placement="top">
                    <el-button circle size="small" type="success" plain icon="Edit" @click="$router.push(`/designer/${scope.row.id}`)" />
                  </el-tooltip>
                  
                  <el-dropdown v-if="isAdmin" trigger="click">
                    <el-button circle size="small" type="info" plain icon="MoreFilled" />
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item icon="Rank" @click="openMoveDialog(scope.row)">移动目录</el-dropdown-item>
                        <el-dropdown-item divided icon="Delete" @click="handleDeleteForm(scope.row)" style="color: #f56c6c">删除模板</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
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
          placeholder="选择目标目录，不选则归入默认"
          style="width: 100%"
        >
          <el-option label="默认" value="" />
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
          placeholder="选择目标目录，不选则归入默认"
          style="width: 100%"
        >
          <el-option label="默认" value="" />
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
 
    <!-- 彻底销毁确认弹窗 -->
    <el-dialog v-model="deleteDialogVisible" title="确认删除模板" width="420px" class="delete-dialog">
      <div class="delete-dialog-body">
        <div class="delete-warning">
          <el-icon class="warning-icon"><WarningFilled /></el-icon>
          <div class="warning-text">确定要删除模板“{{ deletingForm?.name }}”吗？</div>
        </div>
        <div class="delete-hint">删除后，该模板将从列表中移除，用户无法再填报。</div>
        
        <div v-if="!deletingForm?.isExternal" class="delete-option-area">
          <el-checkbox v-model="shouldDropTable">
            <span class="option-label">同时彻底销毁物理表及所有数据</span>
          </el-checkbox>
          <div class="option-desc">勾选后执行 DROP TABLE，数据将不可恢复。不勾选则仅执行重命名备份。</div>
        </div>
        <div v-else class="delete-info-area">
          <el-icon><InfoFilled /></el-icon>
          <span>当前为外部绑定表，删除模板不会影响原始表。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="deleteLoading" @click="confirmDeleteForm">确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, inject, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder, Timer, User, WarningFilled, InfoFilled, Menu, Box, Plus, Edit, Delete, Rank, List, MoreFilled } from '@element-plus/icons-vue'
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
const appliedSearchQuery = ref('')
const appliedStatusFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const moveDialogVisible = ref(false)
const batchMoveDialogVisible = ref(false)
const movingForm = ref(null)
const moveTargetFolderId = ref('')
const batchMoveTargetFolderId = ref('')
const selectedFormIds = ref([])
const deleteDialogVisible = ref(false)
const deleteLoading = ref(false)
const deletingForm = ref(null)
const shouldDropTable = ref(false)
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  folder: null
})

// 侧边栏宽度动态调节逻辑
const sidebarWidth = ref(280) // 初始宽度
const isResizing = ref(false) // 是否正在调整大小

// 鼠标按下缩放条时触发
const startSidebarResize = (e) => {
  isResizing.value = true
  // 监听全局鼠标移动和松开事件
  document.addEventListener('mousemove', handleSidebarResize)
  document.addEventListener('mouseup', stopSidebarResize)
  // 锁定光标样式并禁用文字选择
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
}

// 鼠标移动时计算新宽度
const handleSidebarResize = (e) => {
  if (!isResizing.value) return
  // 计算鼠标当前位置相对于页面的宽度（减去可能的左侧偏移量）
  const newWidth = e.clientX - 24 
  // 限制宽度范围：最小 180px，最大 600px
  if (newWidth > 180 && newWidth < 600) {
    sidebarWidth.value = newWidth
  }
}

// 鼠标松开时移除监听，恢复页面状态
const stopSidebarResize = () => {
  isResizing.value = false
  document.removeEventListener('mousemove', handleSidebarResize)
  document.removeEventListener('mouseup', stopSidebarResize)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

const filteredForms = computed(() => {
  const keyword = appliedSearchQuery.value.trim().toLowerCase()
  return forms.value.filter(form => {
    const matchesSearch = !keyword ||
      (form.name || '').toLowerCase().includes(keyword) ||
      (form.tableName || '').toLowerCase().includes(keyword)
    const matchesStatus = !appliedStatusFilter.value || form.status === appliedStatusFilter.value
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
  if (!folderId) return '默认'
  const segments = folderPathMap.value[folderId]
  return segments && segments.length > 0 ? segments.join(' / ') : '默认'
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
  appliedSearchQuery.value = ''
  appliedStatusFilter.value = ''
  currentPage.value = 1
  if (selectedFolderId.value) {
    selectedFolderId.value = ''
    await updateRouteFolder('')
  }
  await loadForms()
}

const handleFilter = async () => {
  appliedSearchQuery.value = searchQuery.value
  appliedStatusFilter.value = statusFilter.value
  currentPage.value = 1
  await loadForms()
}

const loadFolders = async () => {
  folderLoading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    if (route.query.groupTag) params.groupTag = route.query.groupTag
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
    if (route.query.groupTag) params.groupTag = route.query.groupTag
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

const handleDeleteForm = (form) => {
  deletingForm.value = form
  shouldDropTable.value = false
  deleteDialogVisible.value = true
}

const confirmDeleteForm = async () => {
  if (!deletingForm.value) return
  deleteLoading.value = true
  try {
    await axios.delete(`/api/fill/forms/${deletingForm.value.id}`, { 
      params: { 
        userEmail: currentUser.value,
        dropTable: shouldDropTable.value
      } 
    })
    ElMessage.success('模板已成功删除')
    deleteDialogVisible.value = false
    await Promise.all([loadFolders(), loadForms()])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除操作失败')
  } finally {
    deleteLoading.value = false
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
  width: v-bind('sidebarWidth + "px"');
  min-width: 180px;
  max-width: 600px;
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

.folder-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 12px 12px;
  border-bottom: 1px solid #f1f5f9;
  margin-bottom: 8px;
}

.folder-header-title {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
  letter-spacing: 0.5px;
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
  font-size: 16px;
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

.folder-node-actions {
  display: none; /* 默认隐藏操作按钮，腾出空间给名称 */
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  padding-left: 8px;
}

/* 仅在鼠标悬停在目录项上时显示按钮 */
.folder-node:hover .folder-node-actions {
  display: flex;
}

.folder-count-tag {
  flex-shrink: 0; /* 防止数量标签被挤压 */
}

/* 侧边栏缩放手柄样式 */
.sidebar-resizer {
  position: absolute;
  top: 0;
  right: 0;
  width: 4px; /* 响应区域宽度 */
  height: 100%;
  cursor: col-resize;
  transition: background 0.2s;
  z-index: 10;
}

/* 缩放手柄悬停或激活时的提示色 */
.sidebar-resizer:hover, .sidebar-resizer:active {
  background: var(--primary-color);
  opacity: 0.3;
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
  margin-bottom: 16px;
  background: white;
  padding: 12px 20px;
  border-radius: 12px;
  border: 1px solid #f1f5f9;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}

.view-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.view-title {
  font-size: 16px;
  font-weight: 700;
  color: #1e293b;
}

.search-bar-integrated {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input-compact {
  width: 240px;
}

.status-select-compact {
  width: 110px;
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
  font-size: 14px;
}

.folder-path-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 13px;
}

.table-code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: #f8fafc;
  padding: 4px 8px;
  border-radius: 6px;
  font-size: 12px;
  color: #475569;
  border: 1px solid #f1f5f9;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  height: 24px;
  border: none;
}

.time-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #64748b;
  font-size: 13px;
}

.creator-text {
  color: #94a3b8;
  font-size: 13px;
}

.action-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

:deep(.el-table__header-wrapper th) {
  background-color: #f8fafc;
  color: #475569;
  font-weight: 600;
  font-size: 13px;
  height: 48px;
}

:deep(.el-tree-node__content) {
  min-height: 34px;
  border-radius: 6px;
  padding-right: 6px;
}

:deep(.el-tree) {
  overflow-x: auto;
}

:deep(.el-tree-node) {
  width: max-content;
  min-width: 100%;
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
    max-height: 200px;
  }
  
  /* 深度覆盖 Element Plus 表格固定列的宽度，防止留白过大 */
  :deep(.el-table__fixed-right) {
    height: 100% !important;
  }
  :deep(.el-table__fixed-column--right) {
    background-color: #fff !important;
  }
}

@media (max-width: 768px) {
  .filter-inputs {
    flex-direction: column;
    align-items: stretch;
  }
  .search-input, .status-select {
    width: 100%;
  }
  .view-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
/* 彻底销毁确认弹窗样式 */
.delete-dialog-body {
  padding: 10px 4px;
}

.delete-warning {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.warning-icon {
  font-size: 24px;
  color: #f56c6c;
}

.warning-text {
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
}

.delete-hint {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 20px;
  padding-left: 36px;
}

.delete-option-area {
  margin-left: 36px;
  padding: 12px 16px;
  background: #fff1f2;
  border: 1px solid #fecdd3;
  border-radius: 8px;
}

.delete-info-area {
  margin-left: 36px;
  padding: 12px 16px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #0369a1;
}

.option-label {
  font-weight: 600;
  color: #991b1b;
}

.option-desc {
  font-size: 12px;
  color: #b91c1c;
  margin-top: 4px;
  line-height: 1.4;
}

:deep(.delete-dialog .el-dialog__header) {
  margin-right: 0;
  border-bottom: 1px solid #f1f5f9;
  padding: 16px 20px;
}

:deep(.delete-dialog .el-dialog__title) {
  font-size: 16px;
  font-weight: 700;
}

:deep(.delete-dialog .el-dialog__footer) {
  border-top: 1px solid #f1f5f9;
  padding: 12px 20px;
}
</style>
