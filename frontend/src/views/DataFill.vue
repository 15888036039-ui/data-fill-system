<template>
  <div class="data-fill-page">
    <div class="header-nav flat-header">
      <el-page-header @back="$router.push('/tasks')">
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
             'warning': isNearDeadline || (lockStatus.hasSubmitted && !lockStatus.isLocked), 
             'expired': isExpired || lockStatus.isLocked, 
             'success': lockStatus.hasSubmitted && !lockStatus.isLocked 
           }">
        <el-icon v-if="lockStatus.isLocked"><CircleCheck /></el-icon>
        <el-icon v-else><AlarmClock /></el-icon>
        
        <span v-if="lockStatus.hasSubmitted">
          已完成填报，您可以根据需要随时调整已提交的数据。
        </span>
        <span v-else-if="lockStatus.isLocked">
          填报锁定 {{ isAdmin ? '(管理员模式)' : '' }}
        </span>
        <span v-else>{{ timeLeftMessage }}</span>
      </div>

      <!-- 填报区域 (新增/编辑) -->
      <el-dialog 
        v-model="isFilling" 
        :title="editingRowId ? '修改数据' : '单行录入'"
        width="650px"
        destroy-on-close
      >
        <DynamicForm 
          v-if="isFilling"
          :schema="schemaFields" 
          :initial-data="editingData"
          @submit="submitData"
          @cancel="isFilling = false" 
        />
      </el-dialog>

      <!-- 全新扁平工具栏 -->
      <div class="flat-toolbar">
        <div class="toolbar-row main-actions">
          <div class="left-group">
            <el-button type="primary" icon="Plus" @click="handleAddNew" :disabled="isLocked">新增数据</el-button>
            <el-button icon="Download" @click="downloadTemplate" class="action-btn">下载模板</el-button>
            <el-upload
              :show-file-list="false"
              :http-request="handleUpload"
              accept=".xlsx"
              :disabled="isUploading || isLocked"
              class="inline-upload"
            >
              <el-button icon="Upload" :loading="isUploading" :disabled="isLocked" class="action-btn">上传数据</el-button>
            </el-upload>
          </div>
          
          <div class="right-group">
             <div v-if="isAdmin" class="import-mode-select">
               <el-radio-group v-model="importMode" size="small">
                <el-radio-button label="append">追加</el-radio-button>
                <el-radio-button label="overwrite">覆盖</el-radio-button>
              </el-radio-group>
            </div>
            <div class="divider"></div>
            <el-button 
              type="danger" 
              link 
              icon="Delete" 
              :disabled="selectedIds.length === 0 || isLocked" 
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

        <div class="toolbar-row filter-line">
          <div class="filter-inputs">
            <template v-for="field in filterFields" :key="'filter_'+field.columnName">
              <el-select
                v-model="searchParams[field.columnName]"
                :placeholder="field.name"
                size="default"
                clearable
                filterable
                class="filter-select"
                @change="handleSearch"
              >
                <el-option
                  v-for="opt in (filterOptions[field.columnName] || [])"
                  :key="opt"
                  :label="opt"
                  :value="opt"
                />
              </el-select>
            </template>
            <div class="filter-actions-inline">
              <el-button type="primary" size="default" icon="Search" @click="handleSearch">查询</el-button>
              <el-button size="default" icon="RefreshRight" @click="resetSearch">重置</el-button>
            </div>
          </div>
        </div>
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
            stripe 
            style="width: 100%" 
            v-loading="tableLoading" 
            @selection-change="handleSelectionChange"
            class="custom-table"
          >
            <el-table-column type="selection" width="55" align="center" />
            <el-table-column type="index" label="序号" width="70" align="center" />
            
            <el-table-column 
              v-for="field in schemaFields" 
              :key="field.columnName" 
              :prop="field.columnName" 
              :label="field.name"
              show-overflow-tooltip
              min-width="150"
            />
              
            <el-table-column prop="creator" label="填写人" width="150" sortable />
            <el-table-column prop="update_time" label="最后修改" width="180">
              <template #default="scope">
                {{ formatDateTime(scope.row.update_time || scope.row.create_time) }}
              </template>
            </el-table-column>

            <el-table-column label="操作" width="160" align="center" fixed="right">
              <template #default="scope">
                <el-button 
                  type="primary" 
                  size="small" 
                  link 
                  @click="handleEdit(scope.row)" 
                  :disabled="isLocked || isRowLocked(scope.row)"
                >编辑</el-button>
                <el-popconfirm 
                  title="确定删除这条记录吗？" 
                  @confirm="handleDelete(scope.row.id)" 
                  :disabled="isLocked || isRowLocked(scope.row)"
                >
                  <template #reference>
                    <el-button 
                      type="danger" 
                      size="small" 
                      link 
                      :disabled="isLocked || isRowLocked(scope.row)"
                    >删除</el-button>
                  </template>
                </el-popconfirm>
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
import { ref, onMounted, onUnmounted, computed, reactive, inject } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { AlarmClock, CircleCheck } from '@element-plus/icons-vue'
import axios from 'axios'
import DynamicForm from '../components/DynamicForm.vue'

const route = useRoute()
const formId = route.params.id

const currentUser = inject('currentUser', ref(''))
const userEmail = computed(() => currentUser.value)

const loading = ref(true)
const tableLoading = ref(false)
const formMeta = ref(null)
const tableData = ref([])
const totalCount = ref(0)
const isFilling = ref(false)
const editingRowId = ref(null)
const editingData = ref({})

const currentPage = ref(1)
const pageSize = ref(10)
const importMode = ref('append')
const isUploading = ref(false)

const searchParams = ref({})
const filterOptions = ref({})
const selectedIds = ref([])
const isSelectAllFiltered = ref(false)

const isAdminGlobal = inject('isAdmin', ref(true))
const isAdmin = computed(() => isAdminGlobal.value)

const timeLeftMessage = computed(() => {
  if (!formMeta.value || !formMeta.value.deadline) return ''
  const deadline = new Date(formMeta.value.deadline)
  const now = new Date()
  const diff = deadline - now
  
  if (diff <= 0) return '任务已截止，当前可能无法提交'
  
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))
  if (days > 0) return `剩余 ${days} 天截止，请及时完成`
  
  const hours = Math.floor(diff / (1000 * 60 * 60))
  return `剩余最后 ${hours} 小时，请尽快填报`
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
  hasSubmitted: false,
  graceEndTime: null
})

const isLocked = computed(() => {
  return lockStatus.value.isLocked && !isAdmin.value
})

const graceTimeLeft = ref('')
let timer = null

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
  selectedIds.value = selection.map(row => row.id)
  // 如果不是全选本页，则取消“全选所有过滤数据”的状态
  if (selection.length < tableData.value.length) {
    isSelectAllFiltered.value = false
  }
}

const handleSearch = () => {
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

const schemaFields = computed(() => {
  if (!formMeta.value || !formMeta.value.forms) return []
  try {
    return JSON.parse(formMeta.value.forms)
  } catch (e) {
    return []
  }
})

const filterFields = computed(() => {
  const filterable = schemaFields.value.filter(f => f.filterable)
  return filterable.length > 0 ? filterable : schemaFields.value.slice(0, 3)
})

const loadFilterOptions = async () => {
  try {
    const res = await axios.get(`/api/fill/data/${formId}/filters`, {
      params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
    })
    filterOptions.value = res.data || {}
  } catch (e) {}
}

const loadFormMeta = async () => {
  try {
    const res = await axios.get(`/api/fill/forms/${formId}`)
    formMeta.value = res.data
    await loadFilterOptions()
    await loadTableData()
  } catch (e) {
    ElMessage.error('加载任务配置失败')
  } finally {
    loading.value = false
  }
}

const loadTableData = async () => {
  tableLoading.value = true
  const params = { ...searchParams.value }
  
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
  editingRowId.value = row.id
  editingData.value = { ...row }
  isFilling.value = true
}

const submitData = async (formDataVal) => {
  try {
    const payload = { ...formDataVal }
    // 注入创建人信息
    if (userEmail.value) {
      payload.creator = userEmail.value
      payload.applicantEmail = userEmail.value
    }
    
    if (editingRowId.value) {
      await axios.put(`/api/fill/data/${formId}/${editingRowId.value}`, payload, {
        params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
      })
      ElMessage.success('数据已成功修改')
    } else {
      await axios.post(`/api/fill/data/${formId}`, payload)
      ElMessage.success('填报成功！')
    }
    
    isFilling.value = false
    await loadTableData()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败，请重试')
  }
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
      await axios.post(`/api/fill/data/${formId}/deleteAllFiltered`, searchParams.value, {
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
        ElMessage.error(e.response?.data?.message || '操作失败')
    }
  }
}

const selectAllFiltered = () => {
  isSelectAllFiltered.value = true
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
  
  const loading = ElLoading.service({
    lock: true,
    text: '极速解析与写入中，请稍后...',
    background: 'rgba(255, 255, 255, 0.8)'
  })

  const formData = new FormData()
  formData.append('file', file)
  formData.append('mode', importMode.value)
  if (userEmail.value) formData.append('creator', userEmail.value)
  
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

onMounted(() => { if (formId) loadFormMeta() })
onUnmounted(() => { if (timer) clearInterval(timer) })
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
  padding: 6px 12px;
  border-radius: 4px;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}

.slim-banner.success {
  background: #f0fdf4;
  border-color: #bbf7d0;
  color: #166534;
}

.slim-banner.expired, .slim-banner.locked {
  background: #f8fafc;
  border-color: #e2e8f0;
  color: #475569;
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

.filter-inputs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.filter-actions-inline {
  display: flex;
  gap: 8px;
}

.filter-select {
  width: 120px;
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
</style>
