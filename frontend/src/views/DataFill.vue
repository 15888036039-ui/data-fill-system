<template>
  <div class="data-fill-page">
    <div class="header-nav flat-header">
      <el-page-header @back="isAdmin ? $router.push('/forms') : $router.push('/tasks')">
        <template #content>
          <div class="header-content-box">
            <span class="nav-form-name">{{ formMeta?.name }}</span>
            <div class="header-sub-info">
              <span v-if="isAdmin && formMeta" class="nav-table-name">创建人: 管理员</span>
              <el-divider direction="vertical" />
              <el-icon class="info-icon"><InfoFilled /></el-icon>
            </div>
          </div>
        </template>
      </el-page-header>
    </div>
    
    <div v-if="loading" class="loading-state">
      <el-skeleton :rows="10" animated />
    </div>

    <div v-else-if="formMeta" class="content-wrapper">
      <!-- 状态提示 Banner (更扁平化) -->
      <div v-if="timeLeftMessage || lockStatus.hasSubmitted" 
           class="slim-banner" 
           :class="{ 
             'warning': !isAdmin && !lockStatus.hasSubmitted && isNearDeadline && !isExpired && !lockStatus.isLocked, 
             'expired': !isAdmin && (isExpired || lockStatus.isLocked) && !lockStatus.hasSubmitted, 
             'success': !isAdmin && lockStatus.hasSubmitted,
             'admin': isAdmin && lockStatus.adminStats
           }">
        <el-icon v-if="lockStatus.isLocked"><CircleCheck /></el-icon>
        <el-icon v-else-if="lockStatus.hasSubmitted"><CircleCheck /></el-icon>
        <el-icon v-else><AlarmClock /></el-icon>
        
        <span v-if="isAdmin && lockStatus.adminStats" class="banner-text">
          当前任务进度：
          <span class="sub-info">已填报: {{ lockStatus.adminStats.submittedCount }} 人</span>
          <span v-if="lockStatus.adminStats.totalExpected > 0" class="sub-info">未填报: {{ lockStatus.adminStats.pendingUsers?.length || 0 }} 人</span>
          <el-tooltip v-if="lockStatus.adminStats.totalExpected > 0" placement="top">
            <template #content>
              <div style="max-height: 200px; overflow-y: auto;">
                <div v-if="lockStatus.adminStats.submittedUsers?.length > 0">
                  <b style="color: #67c23a;">● 已提交：</b><br/>
                  {{ lockStatus.adminStats.submittedUsers.join(', ') }}
                </div>
                <div v-if="lockStatus.adminStats.pendingUsers?.length > 0" style="margin-top: 8px;">
                  <b style="color: #e6a23c;">● 未提交：</b><br/>
                  {{ lockStatus.adminStats.pendingUsers.join(', ') }}
                </div>
                <div v-if="lockStatus.adminStats.submittedUsers?.length === 0 && lockStatus.adminStats.pendingUsers?.length === 0">
                  暂无名单信息
                </div>
              </div>
            </template>
            <el-icon class="info-icon" style="color: #0369a1; cursor: pointer; margin-left: 4px;"><InfoFilled /></el-icon>
          </el-tooltip>
        </span>
        <span v-else-if="lockStatus.hasSubmitted" class="banner-text">
          本期填报任务已完成！
          <span v-if="lastSubmitTimeFormatted" class="sub-info">最近提交: {{ lastSubmitTimeFormatted }}</span>
          <span v-if="nextFillTime" class="sub-info">下次填报时间：{{ nextFillTime }}</span>
        </span>
        <span v-else-if="lockStatus.isLocked">
          填报锁定 {{ isAdmin ? '(管理员模式)' : '' }}
        </span>
        <span v-else-if="lockStatus.isUpcoming && !isAdmin" class="banner-text">
          本期填报任务尚未开启。预计开启时间：{{ formatDateTime(lockStatus.startTimeOfCycle) }}
        </span>
        <span v-else-if="!isAdmin">{{ timeLeftMessage }}</span>
      </div>

      <!-- 填报指引/注释框 (新增需求 #3) -->
      <el-alert
        v-if="formMeta?.description"
        :title="formMeta?.name + ' - 填报指引'"
        type="info"
        :description="formMeta.description"
        show-icon
        :closable="true"
        class="description-banner"
      />

      <!-- 填报区域 (新增/编辑) -->
      <DataEditor
        v-model="isFilling"
        :editing-row-id="editingRowId"
        :editing-data="editingData"
        :schema-fields="formFields"
        :form-id="formId"
        :user-email="userEmail"
        :is-admin="isAdmin"
        @success="loadTableData()"
      />

      <!-- 操作日志弹窗 -->
      <OperationLogs
        v-model="logVisible"
        :form-id="formId"
      />

      <!-- 全新扁平工具栏 -->
      <div class="flat-toolbar">
        <div class="toolbar-row main-actions">
          <div class="left-group">
            <el-button type="primary" icon="Plus" @click="handleAddNew" :disabled="!canAdd">新增数据</el-button>
            <el-button icon="Download" @click="downloadTemplate" class="action-btn">下载模板</el-button>
            <el-upload
              :show-file-list="false"
              :http-request="handleUpload"
              accept=".xlsx"
              :disabled="isUploading || isLocked"
              class="inline-upload"
            >
              <el-button icon="Upload" :loading="isUploading" :disabled="!canUpload" class="action-btn">上传数据</el-button>
            </el-upload>
            <el-button icon="Memo" @click="logVisible = true" class="action-btn">操作日志</el-button>
          </div>
          
          <div class="right-group">
             <div v-if="isAdmin" class="import-mode-select">
               <el-tag type="info" effect="plain">导入模式：追加</el-tag>
            </div>
            <div class="divider"></div>
            <el-button 
              type="danger" 
              link 
              icon="Delete" 
              :disabled="selectedIds.length === 0 || !canDelete" 
              @click="handleBatchDelete"
            >
              <span v-if="selectedIds.length > 0">
                {{ isSelectAllFiltered ? `全部删除 (${totalCount})` : `批量删除 (${selectedIds.length})` }}
              </span>
            </el-button>
            <el-tooltip content="刷新数据" placement="top">
              <el-button icon="Refresh" link @click="loadTableData" />
            </el-tooltip>
          </div>
        </div>

        <FilterBar
          :filter-fields="filterFields"
          :initial-params="searchParams"
          @search="handleSearch"
          @reset="resetSearch"
        />
      </div>

        <!-- 批量操作扩展提示 -->
        <div v-if="selectedIds.length > 0 && totalCount > tableData.length" class="selection-banner">
          <template v-if="!isSelectAllFiltered">
            已选择本页 {{ selectedIds.length }} 条数据。
            <el-button type="primary" link @click="selectAllFiltered">选择所有 {{ totalCount }} 条符合筛选条件的数据</el-button>
          </template>
          <template v-else>
            已选择所有 {{ totalCount }} 条符合筛选条件的数据。
            <el-button type="primary" link @click="isSelectAllFiltered = false">取消全选</el-button>
          </template>
        </div>

        <!-- 主表格 (标题已移除) -->
        <div class="table-container card-style" v-loading="loading">
          
          <el-table 
            :data="tableData" 
            border 
            style="width: 100%" 
            v-loading="tableLoading" 
            @selection-change="handleSelectionChange"
            class="custom-table"
          >
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column type="index" label="序号" width="70" align="center" />
            
            <el-table-column 
              v-for="field in tableFields" 
              :key="field.columnName" 
              :prop="field.columnName" 
              :label="field.name"
              show-overflow-tooltip
              min-width="150"
            >
              <template #default="scope">
                {{ formatCellValue(getRowValue(scope.row, field.columnName)) }}
              </template>
            </el-table-column>
              
            <el-table-column prop="load_user" label="填写人" width="150" sortable>
               <template #default="scope">
                {{ getRowValue(scope.row, 'load_user') || getRowValue(scope.row, 'creator') || getRowValue(scope.row, 'loadUser') || '-' }}
              </template>
            </el-table-column>

            <el-table-column label="操作" width="160" align="center" fixed="right">
              <template #default="scope">
                <el-button 
                  type="primary" 
                  size="small" 
                  link 
                  @click="handleEdit(scope.row)" 
                  :disabled="!canEdit || isRowLocked(scope.row)"
                >编辑</el-button>
                <el-button 
                  type="danger" 
                  size="small" 
                  link 
                  @click="confirmDelete(scope.row)" 
                  :disabled="!canDelete || isRowLocked(scope.row)"
                >删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-footer">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              :total="totalCount"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </div>
      </div>
    </div>
  </template>

<script setup>
import { ref, onMounted, onUnmounted, computed, reactive, inject, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { AlarmClock, CircleCheck, InfoFilled } from '@element-plus/icons-vue'
import axios from 'axios'
import FilterBar from '../components/FilterBar.vue'
import DataEditor from '../components/DataEditor.vue'
import OperationLogs from '../components/OperationLogs.vue'

const route = useRoute()
const formId = route.params.id

const formMeta = ref(null)
const loading = ref(true)
const tableLoading = ref(false)
const isFilling = ref(false)
const isUploading = ref(false)
const tableData = ref([])
const totalCount = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const editingRowId = ref(null)
const editingData = ref({})
const logVisible = ref(false)

const currentUser = inject('currentUser', ref(''))
const userEmail = computed(() => currentUser.value)

const searchParams = ref({})
const getSerializedSearchParams = () => {
  const params = { ...searchParams.value }
  for (const key in params) {
    if (Array.isArray(params[key])) {
      if (params[key].length === 2 && params[key][0] && params[key][1]) {
        params[key] = params[key].join(',')
      } else {
        delete params[key]
      }
    } else if (key.endsWith('_start')) {
      const baseKey = key.slice(0, -6)
      const start = params[key]
      const end = params[baseKey + '_end']
      
      if (start || end) {
        params[baseKey] = `${start || ''},${end || ''}`
      }
      delete params[key]
      delete params[baseKey + '_end']
    } else if (key.endsWith('_end')) {
      const baseKey = key.slice(0, -4)
      if (!params[baseKey + '_start']) {
        const end = params[key]
        if (end) {
          params[baseKey] = `,${end}`
        }
      }
      delete params[key]
    }
  }
  return params
}
const selectedIds = ref([])
const isSelectAllFiltered = ref(false)

const isAdminGlobal = inject('isAdmin', ref(true))
const isAdmin = computed(() => isAdminGlobal.value)

const now = ref(new Date())

const timeLeftMessage = computed(() => {
  if (!formMeta.value || !formMeta.value.deadline) return ''
  const deadline = new Date(formMeta.value.deadline)
  const diff = deadline - now.value
  
  if (diff <= 0) return '任务已截止，当前可能无法提交'
  
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
  const seconds = Math.floor((diff % (1000 * 60)) / 1000)

  if (days > 0) {
    return `剩余 ${days} 天 ${hours} 小时 ${minutes} 分截止，请及时完成`
  }
  if (hours > 0) {
    return `剩余最后 ${hours} 小时 ${minutes} 分 ${seconds} 秒，请尽快填报`
  }
  return `剩余最后 ${minutes} 分 ${seconds} 秒，请尽快填报`
})

const isNearDeadline = computed(() => {
  if (!formMeta.value || !formMeta.value.deadline) return false
  const diff = new Date(formMeta.value.deadline) - new Date()
  return diff > 0 && diff < (1000 * 60 * 60 * 24 * 3) // 3天内预警
})

const isExpired = computed(() => {
  if (!formMeta.value || !formMeta.value.deadline) return false
  return new Date() > new Date(formMeta.value.deadline)
})

const lockStatus = ref({
  isLocked: false,
  isUpcoming: false,
  startTimeOfCycle: null,
  hasSubmitted: false,
  graceEndTime: null
})

const isLocked = computed(() => {
  return lockStatus.value.isLocked && !isAdmin.value
})

const canAdd = computed(() => {
  if (isAdmin.value) return true
  // 任务未开启、已全局锁定，均不允许操作；否则检查单独的“允许新增”开关
  if (lockStatus.value.isUpcoming || isLocked.value) return false
  return formMeta.value?.allowAdd !== false
})

const canUpload = computed(() => {
  if (isAdmin.value) return true
  // 任务未开启、已全局锁定，均不允许上传
  if (lockStatus.value.isUpcoming || isLocked.value) return false
  return true
})

const canEdit = computed(() => {
  if (isAdmin.value) return true
  if (lockStatus.value.isUpcoming || isLocked.value) return false
  return formMeta.value?.allowEdit !== false
})

const canDelete = computed(() => {
  if (isAdmin.value) return true
  return !isLocked.value && formMeta.value?.allowDelete !== false
})

const nextFillTime = computed(() => {
  // 优先使用后端返回的权威值（与 UserTasks 列表完全一致）
  if (lockStatus.value.nextFillTime) {
    const d = new Date(lockStatus.value.nextFillTime)
    if (!isNaN(d.getTime())) {
      const year = d.getFullYear()
      const month = d.getMonth() + 1
      const day = d.getDate()
      const hours = String(d.getHours()).padStart(2, '0')
      const minutes = String(d.getMinutes()).padStart(2, '0')
      return `${year}/${month}/${day} ${hours}:${minutes}:00`
    }
  }

  // fallback: 本地估算（仅在后端尚未返回时使用）
  if (!formMeta.value || !formMeta.value.deadline) return null
  const mode = formMeta.value.reminderMode
  if (!mode || mode === 'DEADLINE') return null
  
  const rDays = formMeta.value.reminderDays || 0
  const deadline = new Date(formMeta.value.deadline)
  const lastReminder = new Date(deadline.getTime() - rDays * 24 * 60 * 60 * 1000)
  
  let nextDate = new Date(lastReminder)
  if (mode === 'WEEKLY') {
    nextDate.setDate(nextDate.getDate() + 7)
  } else if (mode === 'MONTHLY') {
    nextDate.setMonth(nextDate.getMonth() + 1)
  } else {
    return null
  }
  
  const year = nextDate.getFullYear()
  const month = nextDate.getMonth() + 1
  const day = nextDate.getDate()
  const hours = String(nextDate.getHours()).padStart(2, '0')
  const minutes = String(nextDate.getMinutes()).padStart(2, '0')
  
  return `${year}/${month}/${day} ${hours}:${minutes}:00`
})

const lastSubmitTimeFormatted = computed(() => {
  if (!lockStatus.value.lastSubmitTime) return null
  const d = new Date(lockStatus.value.lastSubmitTime)
  if (isNaN(d.getTime())) return null
  
  const year = d.getFullYear()
  const month = d.getMonth() + 1
  const day = d.getDate()
  const hours = String(d.getHours()).padStart(2, '0')
  const minutes = String(d.getMinutes()).padStart(2, '0')
  const seconds = String(d.getSeconds()).padStart(2, '0')
  return `${year}/${month}/${day} ${hours}:${minutes}:${seconds}`
})

const graceTimeLeft = ref('')
let timer = null
let timerNow = null

const isRowLocked = (row) => {
  return false // 根据需求变更：用户可以一直操作自己填报过的数据
}

const updateGraceCountdown = () => {
  if (!lockStatus.value.graceEndTime) return
  const end = new Date(lockStatus.value.graceEndTime).getTime()
  const now = new Date().getTime()
  const diff = end - now

  if (diff <= 0) {
    graceTimeLeft.value = ''
    lockStatus.value.isLocked = true // 倒计时结束，自动锁定
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    return
  }

  const hours = Math.floor(diff / (1000 * 60 * 60))
  const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
  const seconds = Math.floor((diff % (1000 * 60)) / 1000)
  graceTimeLeft.value = `${hours}时${minutes}分${seconds}秒`
}

const handleSelectionChange = (selection) => {
  const pk = formMeta.value?.pkColumn || 'id'
  selectedIds.value = selection.map(row => getRowValue(row, pk))
  // 如果不是全选本页，则取消“全选所有过滤数据”的状态
  if (selection.length < tableData.value.length) {
    isSelectAllFiltered.value = false
  }
}

const handleSearch = (params) => {
  searchParams.value = params || {}
  currentPage.value = 1
  loadTableData()
}

const resetSearch = () => {
  searchParams.value = {}
  currentPage.value = 1
  loadTableData()
}

const formatDateTime = (val) => {
  if (!val) return '-'
  return new Date(val).toLocaleString()
}

const formatCellValue = (val) => {
  if (val === null || val === undefined) return ''
  
  // 处理对象类型 (日期或 JSON 对象)
  if (typeof val === 'object') {
    if (val instanceof Date) return formatDateTime(val)
    
    // 特殊处理：PostgreSQL 的 PGobject 对象 (Jackson 序列化后会带 {type: 'jsonb', value: '...'})
    if (val.type && val.value !== undefined && (val.type === 'jsonb' || val.type === 'json')) {
      const jsonStr = val.value
      try {
        const parsed = JSON.parse(jsonStr)
        return JSON.stringify(parsed)
      } catch (e) {
        // 如果解析失败，至少把转义符去了
        return String(jsonStr).replace(/\\"/g, '"').replace(/\\\\/g, '\\')
      }
    }

    try {
      // 普通对象直接序列化
      return JSON.stringify(val)
    } catch (e) {
      return String(val)
    }
  }

  // 处理字符串类型 (可能是 JSON 字符串)
  if (typeof val === 'string') {
    const trimmed = val.trim()
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        const parsed = JSON.parse(trimmed)
        return JSON.stringify(parsed)
      } catch (e) {
        return val.replace(/\\"/g, '"').replace(/\\\\/g, '\\')
      }
    }
  }
  
  return val
}

const getRowValue = (row, key) => {
  if (!row || !key) return undefined
  if (row[key] !== undefined) return row[key]
  // Fallback for case sensitivity
  const lowerKey = key.toLowerCase()
  const foundKey = Object.keys(row).find(k => k.toLowerCase() === lowerKey)
  return foundKey ? row[foundKey] : undefined
}

const filterByVisibility = (fieldsList) => {
  if (isAdmin.value) return fieldsList
  const email = (userEmail.value || '').trim().toLowerCase()
  return fieldsList.filter(f => {
    if (!f.visibleEmails || !Array.isArray(f.visibleEmails) || f.visibleEmails.length === 0) {
      return true
    }
    return f.visibleEmails.some(e => e.trim().toLowerCase() === email)
  })
}

const tableFields = computed(() => {
  if (!formMeta.value || !formMeta.value.forms) return []
  try {
    const allFields = JSON.parse(formMeta.value.forms)
    // 列表模式：过滤掉勾选了“列表隐藏”的字段，再根据可见权限过滤
    const listFields = allFields.filter(f => !f.hideInList)
    return filterByVisibility(listFields)
  } catch (e) {
    return []
  }
})

const formFields = computed(() => {
  if (!formMeta.value || !formMeta.value.forms) return []
  try {
    const allFields = JSON.parse(formMeta.value.forms)
    // 填报模式：过滤掉勾选了“填报隐藏”的字段，再根据可见权限过滤
    const fillFields = allFields.filter(f => !f.hideInForm)
    return filterByVisibility(fillFields)
  } catch (e) {
    return []
  }
})

// 兼容旧逻辑名（如果其他地方引用了 schemaFields）
const schemaFields = formFields

const dynamicFilterOptions = ref({})

const loadDynamicFilterOptions = async () => {
  if (!formMeta.value || !formMeta.value.forms) return
  try {
    const allFields = JSON.parse(formMeta.value.forms)
    const visibleAllFields = filterByVisibility(allFields)
    const targetFields = visibleAllFields.filter(f => 
      f.filterable && 
      f.filterType === 'select' && 
      (!f.filterOptions || f.filterOptions.length === 0) && 
      (!f.options || f.options.length === 0)
    )
    
    await Promise.all(targetFields.map(async (f) => {
      try {
        if (f.filterOptionsSql && f.filterOptionsSql.trim()) {
          const res = await axios.get(`/api/fill/data/${formId}/filter-options-by-sql`, {
            params: {
              columnName: f.columnName,
              userEmail: userEmail.value
            }
          })
          dynamicFilterOptions.value[f.columnName] = res.data || []
        } else {
          const res = await axios.get(`/api/fill/data/${formId}/distinct/${f.columnName}?userEmail=${userEmail.value}&isAdmin=${isAdmin.value}`)
          dynamicFilterOptions.value[f.columnName] = res.data || []
        }
      } catch (err) {
        console.warn(`Failed to fetch dynamic filter options for column: ${f.columnName}`, err)
      }
    }))
  } catch (e) {
    console.error('Error parsing forms for dynamic filter options', e)
  }
}

const filterFields = computed(() => {
  const filterable = tableFields.value.filter(f => f.filterable)
  let fieldsToUse = filterable.length > 0 ? filterable : []
  if (fieldsToUse.length === 0) {
    const policy = formMeta.value?.defaultFilterPolicy
    if (policy !== 'NONE') {
      fieldsToUse = tableFields.value.slice(0, 3)
    }
  }
  
  return fieldsToUse.map(f => {
    const copy = { ...f }
    if (copy.filterType === 'select' && (!copy.filterOptions || copy.filterOptions.length === 0) && (!copy.options || copy.options.length === 0)) {
      copy.filterOptions = dynamicFilterOptions.value[copy.columnName] || []
    }
    return copy
  })
})

const loadFormMeta = async () => {
  try {
    const res = await axios.get(`/api/fill/forms/${formId}`)
    formMeta.value = res.data
    await loadTableData()
    await loadDynamicFilterOptions()
  } catch (e) {
    ElMessage.error('加载任务配置失败')
  } finally {
    loading.value = false
  }
}

const loadTableData = async () => {
  tableLoading.value = true
  const params = getSerializedSearchParams()
  
  try {
    const res = await axios.post(`/api/fill/data/${formId}/list?userEmail=${userEmail.value}&isAdmin=${isAdmin.value}`, params, {
      params: {
        page: currentPage.value,
        size: pageSize.value
      }
    })
    tableData.value = res.data.records || []
    totalCount.value = res.data.total || 0
    if (res.data.lockStatus) {
      lockStatus.value = res.data.lockStatus
      if (lockStatus.value.graceEndTime && !lockStatus.value.isLocked) {
        if (!timer) timer = setInterval(updateGraceCountdown, 1000)
        updateGraceCountdown()
      }
    }
  } catch (e) {
    ElMessage.error('加载表格数据失败')
  } finally {
    tableLoading.value = false
  }
}

const handleAddNew = () => {
  editingRowId.value = null
  editingData.value = {}
  isFilling.value = true
}

const handleEdit = (row) => {
  const pk = formMeta.value?.pkColumn || 'id'
  editingRowId.value = getRowValue(row, pk) || null // 强制获取主键标识
  editingData.value = { ...row }
  isFilling.value = true
}


const handleBatchDelete = async () => {
  if (selectedIds.value.length === 0) return
  
  const count = isSelectAllFiltered.value ? totalCount.value : selectedIds.value.length
  const title = isSelectAllFiltered.value ? '危险：全部删除' : '批量删除'
  const message = `确定要删除${isSelectAllFiltered.value ? '所有筛选出的' : '选中的'} ${count} 条数据吗？`
  
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: isSelectAllFiltered.value ? 'el-button--danger' : ''
    })
    
    if (isSelectAllFiltered.value) {
      // 调用批量删除所有过滤数据的接口
      await axios.post(`/api/fill/data/${formId}/deleteAllFiltered`, getSerializedSearchParams(), {
        params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
      })
    } else {
      // 调用普通批量删除接口
      await axios.post(`/api/fill/data/${formId}/batchDelete`, selectedIds.value, {
        params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
      })
    }
    
    ElMessage.success('删除成功')
    selectedIds.value = []
    isSelectAllFiltered.value = false
    await loadTableData()
  } catch(e) {
    if (e !== 'cancel') {
        ElMessage.error(e?.response?.data?.message || '操作失败')
    }
  }
}

const selectAllFiltered = () => {
  isSelectAllFiltered.value = true
}

const confirmDelete = async (row) => {
  try {
    await ElMessageBox.confirm('确定要删除这条记录吗？', '删除确认', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    const pk = formMeta.value?.pkColumn || 'id'
    const dataId = getRowValue(row, pk)
    await handleDelete(dataId)
  } catch (e) {
    // cancelled
  }
}

const handleDelete = async (dataId) => {
  try {
    await axios.delete(`/api/fill/data/${formId}/${dataId}`, {
      params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
    })
    ElMessage.success('记录已删除')
    await loadTableData()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

const downloadTemplate = () => window.open(`/api/fill/template/${formId}`)

const handleUpload = async (options) => {
  const file = options.file
  
  // 增加前端初步校验：20MB 限制（与后端配置一致）
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('上传文件过大（超过 20MB），为了确保系统稳定性，请将数据分批进行导入。')
    return
  }
  
  const loading = ElLoading.service({
    lock: true,
    text: '极速解析与写入中，请稍后...',
    background: 'rgba(255, 255, 255, 0.8)'
  })

  const formData = new FormData()
  formData.append('file', file)
  formData.append('mode', 'append')
  if (userEmail.value) formData.append('load_user', userEmail.value)
  
  isUploading.value = true
  try {
    const res = await axios.post(`/api/fill/import/${formId}`, formData)
    handleImportSuccess(res.data)
  } catch (e) {
    handleImportError(e)
  } finally {
    isUploading.value = false
    loading.close()
  }
}

const handleImportSuccess = (response) => {
  if (response.success) {
    ElMessage.success(`成功导入 ${response.count} 条记录`)
    loadTableData()
  } else if (response.hasValidationErrors) {
    // 处理大批量数据的校验错误提示
    const summary = `检测到共 <strong style="color: #ef4444; font-size: 15px;">${response.errorCount}</strong> 处数据不合规。为了保障数据一致性，本次导入已全部安全回滚（未向数据库写入任何记录）。`
    const downloadUrl = `/api/fill/import/error-report/${response.reportId}`
    
    ElMessageBox.confirm(
      `${summary}<br/><br/><span style="color: #64748b; font-size: 13px; font-weight: 500;">请下载详细错误清单，修改后重新上传。</span>`,
      '导入校验失败',
      {
        confirmButtonText: '下载错误清单',
        cancelButtonText: '关闭',
        type: 'error',
        dangerouslyUseHTMLString: true,
        distinguishCancelAndClose: true
      }
    ).then(() => {
      window.open(downloadUrl)
    }).catch(() => {})
  } else {
    ElMessage.error(response.message || '导入失败，请检查文件格式')
  }
}

const handleImportError = (err) => {
  const errorMsg = err.response?.data?.message || '导入过程中发生错误，请重试'
  ElMessage.error(errorMsg)
}

const handleSizeChange = (val) => { pageSize.value = val; handleSearch() }
const handleCurrentChange = (val) => { currentPage.value = val; loadTableData() }

onMounted(() => { 
  if (formId) loadFormMeta() 
  // 全局计时器，每秒刷新 now
  timerNow = setInterval(() => {
    now.value = new Date()
  }, 1000)
})
onUnmounted(() => { 
  if (timer) clearInterval(timer) 
  if (timerNow) clearInterval(timerNow)
})

watch([userEmail, isAdmin], ([newEmail]) => {
  if (newEmail && formMeta.value) {
    loadTableData()
  }
}, { immediate: true })
</script>

<style scoped>
.data-fill-page {
  animation: fadeIn 0.4s ease-out;
}

.header-nav {
  padding: 16px 0;
}

.content-wrapper {
  width: 100%;
  margin: 0;
}

.deadline-banner {
  background: rgba(254, 242, 242, 0.8);
  backdrop-filter: blur(8px);
  border: 1px solid #fee2e2;
  color: #991b1b;
  padding: 10px 20px;
  border-radius: 10px;
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
  font-size: 14px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.deadline-banner.warning {
  background: rgba(255, 251, 235, 0.8);
  border-color: #fef3c7;
  color: #92400e;
}

.deadline-banner.expired {
  background: rgba(241, 245, 249, 0.8);
  border-color: #e2e8f0;
  color: #64748b;
}

.header-nav {
  margin-bottom: 8px;
}

.flat-header :deep(.el-page-header__left) {
  margin-right: 16px;
}

.header-content-box {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-sub-info {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #64748b;
  gap: 8px;
}

.info-icon {
  font-size: 16px;
  cursor: help;
  color: #94a3b8;
}

.slim-banner {
  padding: 8px 16px;
  border-radius: 6px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  background: #f0f9ff;
  border: 1px solid #e0f2fe;
  color: #0369a1;
  transition: all 0.3s ease;
}

.slim-banner.success {
  background: #f0fdf4;
  border-color: #bbf7d0;
  color: #166534;
}

.slim-banner.warning {
  background: #fffbeb;
  border-color: #fef3c7;
  color: #92400e;
}

.slim-banner.admin {
  background: #f0f9ff;
  border-color: #e0f2fe;
  color: #0369a1;
}

.slim-banner.expired, .slim-banner.locked {
  background: #f8fafc;
  border-color: #e2e8f0;
  color: #475569;
}

.banner-text {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}

.sub-info {
  font-weight: 500;
  opacity: 0.9;
  font-size: 13px;
}

.slim-banner.success .sub-info {
  color: #166534;
}

.flat-toolbar {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  margin-bottom: 16px;
}

.toolbar-row {
  padding: 8px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toolbar-row.main-actions {
  border-bottom: 1px solid #f1f5f9;
}

.left-group, .right-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.divider {
  width: 1px;
  height: 14px;
  background: #e2e8f0;
}

.table-header {
  padding: 0 4px;
  margin-bottom: 8px;
}

.table-title {
  font-size: 15px;
  font-weight: 600;
  color: #475569;
}

.pagination-footer {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  padding-bottom: 8px;
}

.loading-state {
  padding: 80px 0;
}

.selection-banner {
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  color: #0369a1;
  padding: 8px 16px;
  border-radius: 4px;
  margin-bottom: 12px;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

.description-banner {
  margin-bottom: 20px;
  border: 1px solid #bae6fd;
  background-color: #f0f9ff;
  border-radius: 8px;
}

:deep(.description-banner .el-alert__title) {
  font-weight: 700;
  font-size: 15px;
  color: #0369a1;
}

:deep(.description-banner .el-alert__description) {
  font-size: 13px;
  line-height: 1.6;
  color: #0c4a6e;
  margin-top: 4px;
}
</style>
