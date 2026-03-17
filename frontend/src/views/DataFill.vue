<template>
  <div class="data-fill-page">
    <div class="header-nav">
      <el-page-header @back="$router.push('/tasks')" title="返回我的任务" />
    </div>
    
    <div v-if="loading" class="loading-state">
      <el-skeleton :rows="10" animated />
    </div>

    <div v-else-if="formMeta" class="content-wrapper">
      <!-- 状态提示 Banner -->
      <div v-if="timeLeftMessage" 
           class="deadline-banner" 
           :class="{ 'warning': isNearDeadline, 'expired': isExpired, 'success': hasSubmitted }">
        <el-icon v-if="hasSubmitted"><CircleCheck /></el-icon>
        <el-icon v-else><AlarmClock /></el-icon>
        <span>{{ hasSubmitted ? '您本期已完成填报，感谢配合！' : timeLeftMessage }}</span>
      </div>

      <div class="form-title-section">
        <h1 class="form-title">{{ formMeta.name }}</h1>
        <p class="form-subtitle" v-if="isAdmin">物理表: {{ formMeta.tableName }}</p>
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

      <!-- 数据表格工具栏 -->
      <div class="workbench">
        <div class="action-bar">
          <div class="left">
            <el-button type="primary" size="large" @click="handleAddNew" icon="Plus">单行录入</el-button>
            <el-button size="large" @click="downloadTemplate" icon="Download">下载模板</el-button>
            <el-upload
              :show-file-list="false"
              :http-request="handleUpload"
              accept=".xlsx"
              :disabled="isUploading"
              class="upload-btn"
            >
              <el-button icon="Upload" type="warning" size="large" :loading="isUploading">
                {{ isUploading ? '导入中...' : '上传数据' }}
              </el-button>
            </el-upload>
          </div>
          
          <div class="right" v-if="isAdmin">
             <el-radio-group v-model="importMode" size="small" style="margin-right: 15px;">
              <el-radio-button label="append">追加</el-radio-button>
              <el-radio-button label="overwrite">覆盖</el-radio-button>
            </el-radio-group>
            <el-button type="danger" plain icon="Delete" :disabled="selectedIds.length === 0" @click="handleBatchDelete">批量删除</el-button>
          </div>
        </div>

        <!-- 过滤器 -->
        <div class="filter-panel card-style">
          <div class="filters">
            <template v-for="field in filterFields" :key="'filter_'+field.columnName">
              <div class="filter-item">
                <span class="filter-label">按 {{ field.name }} 筛选</span>
                <el-select
                  v-model="searchParams[field.columnName]"
                  placeholder="全选"
                  clearable
                  filterable
                  @change="handleSearch"
                >
                  <el-option
                    v-for="opt in (filterOptions[field.columnName] || [])"
                    :key="opt"
                    :label="opt"
                    :value="opt"
                  />
                </el-select>
              </div>
            </template>
            <div class="filter-item action-buttons" style="margin-left: auto;">
               <el-button type="primary" @click="handleSearch">立即查询</el-button>
               <el-button @click="resetSearch">重置</el-button>
            </div>
          </div>
        </div>

        <!-- 主表格 -->
        <div class="table-container card-style" v-loading="loading">
          <div class="table-header">
             <h3 class="table-title">{{ isAdmin ? '全量数据管理' : '我填报的数据' }}</h3>
             <!-- Redundant total count removed as per request -->
          </div>
          
          <el-table 
            :data="tableData" 
            border 
            stripe 
            style="width: 100%" 
            v-loading="tableLoading" 
            @selection-change="handleSelectionChange"
            class="custom-table"
          >
            <el-table-column v-if="isAdmin" type="selection" width="55" align="center" />
            <el-table-column type="index" label="序号" width="70" align="center" />
            
            <el-table-column 
              v-for="field in schemaFields" 
              :key="field.columnName" 
              :prop="field.columnName" 
              show-overflow-tooltip
              min-width="170"
            >
              <template #header>
                <div class="header-content">
                  <span class="label-name">{{ field.name }}</span>
                  <span class="column-code">{{ field.columnName }}</span>
                </div>
              </template>
            </el-table-column>
              
            <el-table-column prop="creator" label="填写人" width="150" sortable />
            <el-table-column prop="update_time" label="最后修改" width="180">
              <template #default="scope">
                {{ formatDateTime(scope.row.update_time || scope.row.create_time) }}
              </template>
            </el-table-column>

            <el-table-column label="操作" width="160" align="center" fixed="right">
              <template #default="scope">
                <el-button type="primary" size="small" link @click="handleEdit(scope.row)">编辑</el-button>
                <el-popconfirm title="确定删除这条记录吗？" @confirm="handleDelete(scope.row.id)">
                  <template #reference>
                    <el-button type="danger" size="small" link>删除</el-button>
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
  </div>
</template>

<script setup>
import { ref, onMounted, computed, reactive, inject } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AlarmClock, CircleCheck } from '@element-plus/icons-vue'
import axios from 'axios'
import DynamicForm from '../components/DynamicForm.vue'

const route = useRoute()
const formId = route.params.id
const userEmail = ref(localStorage.getItem('df_user_email') || route.query.userEmail || '')

const loading = ref(true)
const tableLoading = ref(false)
const formMeta = ref(null)
const tableData = ref([])
const isFilling = ref(false)
const editingRowId = ref(null)
const editingData = ref({})

const currentPage = ref(1)
const pageSize = ref(10)
const totalCount = ref(0)
const importMode = ref('append')
const isUploading = ref(false)

const searchParams = ref({})
const filterOptions = ref({})
const selectedIds = ref([])

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

const hasSubmitted = computed(() => {
  return tableData.value.length > 0
})

const handleSelectionChange = (val) => {
  selectedIds.value = val.map(item => item.id)
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
  // 个人工作台模式：强制只看自己填的数据
  if (!isAdmin.value) {
    params.load_user = userEmail.value
  }
  
  try {
    const res = await axios.post(`/api/fill/data/${formId}/list`, params, {
      params: {
        page: currentPage.value,
        size: pageSize.value
      }
    })
    tableData.value = res.data.records || []
    totalCount.value = res.data.total || 0
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
  try {
    await ElMessageBox.confirm(`确定要删除选中的 ${selectedIds.value.length} 条数据吗？`, '警告', {
      type: 'warning'
    })
    await axios.post(`/api/fill/data/${formId}/batchDelete`, selectedIds.value, {
      params: { userEmail: userEmail.value, isAdmin: isAdmin.value }
    })
    ElMessage.success('批量删除成功')
    selectedIds.value = []
    await loadTableData()
  } catch(e) {}
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
  const formData = new FormData()
  formData.append('file', options.file)
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
  padding: 14px 24px;
  border-radius: 12px;
  margin-bottom: 32px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 600;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
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

.form-title-section {
  margin-bottom: 40px;
}

.form-title {
  font-size: 32px;
  font-weight: 800;
  color: #0f172a;
  margin: 0;
  letter-spacing: -0.025em;
}

.form-subtitle {
  color: #64748b;
  font-size: 15px;
  margin-top: 8px;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.action-bar .left {
  display: flex;
  gap: 16px;
}

.upload-btn {
  display: inline-block;
}

.card-style {
  background: white;
  border-radius: 16px;
  box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px -1px rgba(0, 0, 0, 0.1);
  padding: 24px;
  margin-bottom: 24px;
  border: 1px solid #f1f5f9;
}

.filter-panel .filters {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: flex-end;
}

.filter-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: #64748b;
  font-weight: 600;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.table-title {
  font-size: 18px;
  font-weight: 700;
  margin: 0;
  color: #1e293b;
}

.custom-table {
  border-radius: 12px;
  overflow: hidden;
}

:deep(.el-table__header th .cell) {
  white-space: nowrap !important;
  color: #334155;
  font-weight: 700;
  padding: 12px 0;
}

.header-content {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.label-name {
  font-size: 14px;
}

.column-code {
  font-size: 11px;
  color: #94a3b8;
  font-weight: normal;
  margin-top: 2px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.deadline-banner.success {
  background: rgba(236, 253, 245, 0.9);
  border-color: #a7f3d0;
  color: #065f46;
}

.pagination-footer {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
}

.loading-state {
  padding: 80px 0;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
