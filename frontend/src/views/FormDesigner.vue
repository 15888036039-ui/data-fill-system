<template>
  <div class="form-designer-page">
    <div class="page-header">
      <div class="header-actions">
        <el-button @click="$router.push('/forms')">取消返回</el-button>
        <el-button v-if="!isEditMode" type="primary" size="large" icon="Platform" @click="submitFormAndCreateTable">创建并发布</el-button>
        <el-button v-else type="primary" size="large" icon="Check" @click="updateFormMeta">保存设置</el-button>
      </div>
    </div>

    <div class="designer-container">
      <div class="config-sidebar">
        <el-card class="sidebar-card" shadow="never">
          <div class="section-title">
            <el-icon><Setting /></el-icon> 基础配置
          </div>
          <el-form :model="formMeta" label-position="top" class="meta-form">
            <el-form-item label="模板中文名" required>
              <el-input v-model="formMeta.name" placeholder="例如: 员工入职登记表" />
            </el-form-item>
            <el-form-item label="数据库物理表名" required>
              <el-input
                v-model="formMeta.tableName"
                placeholder="例如: df_emp_reg (建议 df_ 前缀)"
                :disabled="isEditMode"
              />
            </el-form-item>
            
            <div class="form-row">
              <el-form-item label="运营状态" style="flex: 1">
                <el-select v-model="formMeta.status" style="width: 100%">
                  <el-option label="运行中（可填报）" value="ACTIVE" />
                  <el-option label="已过期（禁止填报）" value="EXPIRED" />
                  <el-option label="停用（管理员可见）" value="DISABLED" />
                </el-select>
              </el-form-item>
            </div>

            <el-divider />
            
            <div class="section-title">
              <el-icon><Notification /></el-icon> 提醒与截止策略
            </div>
            
            <el-form-item label="提醒模式">
              <el-radio-group v-model="formMeta.reminderMode" class="mode-radio">
                <el-radio-button label="DEADLINE">固定截止</el-radio-button>
                <el-radio-button label="MONTHLY">每月循环</el-radio-button>
                <el-radio-button label="WEEKLY">每周循环</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item v-if="formMeta.reminderMode === 'DEADLINE'" label="截止时间" required>
              <el-date-picker
                v-model="formMeta.deadline"
                type="datetime"
                placeholder="选择截止日期"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item 
              v-if="formMeta.reminderMode === 'MONTHLY'" 
              label="每月几号触发提醒" 
              required
            >
              <el-input-number v-model="formMeta.monthlyDay" :min="1" :max="31" style="width: 100%" />
            </el-form-item>

            <el-form-item 
              v-if="formMeta.reminderMode === 'WEEKLY'" 
              label="每周几触发提醒" 
              required
            >
              <el-select v-model="formMeta.weeklyDayOfWeek" style="width: 100%">
                <el-option label="星期一" :value="1" />
                <el-option label="星期二" :value="2" />
                <el-option label="星期三" :value="3" />
                <el-option label="星期四" :value="4" />
                <el-option label="星期五" :value="5" />
                <el-option label="星期六" :value="6" />
                <el-option label="星期日" :value="7" />
              </el-select>
            </el-form-item>

            <div class="form-row">
              <el-form-item :label="formMeta.reminderMode === 'DEADLINE' ? '提前提醒天数' : '提醒后要求完成天数'" style="flex: 1">
                <el-input-number v-model="formMeta.reminderDays" :min="1" :max="30" style="width: 100%" />
              </el-form-item>
              <el-form-item label="具体提醒时点" style="flex: 1">
                <el-time-picker
                  v-model="formMeta.reminderTime"
                  format="HH:mm"
                  value-format="HH:mm"
                  placeholder="选择时间"
                  style="width: 100%"
                />
              </el-form-item>
            </div>

            <el-form-item label="收件人邮箱 (管理员通知)">
              <el-select
                v-model="recipientList"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="选择通知收件人"
                style="width: 100%"
                :loading="userListLoading"
              >
                <el-option
                  v-for="u in allUserEmails"
                  :key="u"
                  :label="u"
                  :value="u"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </el-card>
      </div>

      <div class="fields-main">
        <el-card class="fields-card" shadow="never">
          <div class="card-header-flex">
            <div class="section-title">
              <el-icon><Grid /></el-icon> 表单字段定义
            </div>
            <div class="field-actions">
              <el-button icon="Upload" plain @click="importDialogVisible = true" v-if="!isEditMode">从 Excel 导入结构</el-button>
              <el-button type="primary" plain icon="Plus" @click="addField">新增字段</el-button>
            </div>
          </div>

          <p class="field-info" v-if="!isEditMode">
            定义用户需要填写的具体内容。完成后我们将为您在后端自动创建对应的物理表结构。
          </p>
          <el-alert
            v-else
            title="当前处于元数据编辑模式，物理表结构已锁定。您可以修改字段显示名称或在末尾新增字段，但不可更改已有字段的物理列名和数据类型。"
            type="success"
            show-icon
            style="margin-bottom: 24px;"
            :closable="false"
          />

          <div class="fields-list">
             <el-table :data="fields" style="width: 100%">
                <el-table-column label="中文显示名" min-width="180">
                  <template #default="scope">
                    <el-input v-model="scope.row.name" placeholder="字段标题" />
                  </template>
                </el-table-column>
                <el-table-column label="物理列名 (英文)" min-width="180">
                  <template #default="scope">
                    <el-input v-model="scope.row.columnName" placeholder="c_name" :disabled="isEditMode && !!scope.row.id_mark" />
                  </template>
                </el-table-column>
                <el-table-column label="字段属性 (PG 类型)" min-width="200">
                  <template #default="scope">
                    <el-select
                      v-model="scope.row.dbType"
                      filterable
                      allow-create
                      default-first-option
                      placeholder="例如: VARCHAR(255)"
                      :disabled="isEditMode && !!scope.row.id_mark"
                      style="width: 100%"
                      @change="(val) => handleDbTypeChange(val, scope.row)"
                    >
                      <el-option label="VARCHAR(255)" value="VARCHAR(255)" />
                      <el-option label="TEXT" value="TEXT" />
                      <el-option label="INTEGER" value="INTEGER" />
                      <el-option label="NUMERIC(15, 4)" value="NUMERIC(15, 4)" />
                      <el-option label="TIMESTAMP" value="TIMESTAMP" />
                      <el-option label="BOOLEAN" value="BOOLEAN" />
                      <el-option label="JSONB" value="JSONB" />
                      <el-option label="DATE" value="DATE" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="必填" width="80" align="center">
                  <template #default="scope">
                    <el-switch v-model="scope.row.required" :disabled="isEditMode" />
                  </template>
                </el-table-column>
                <el-table-column label="筛选" width="80" align="center">
                  <template #default="scope">
                    <el-switch v-model="scope.row.filterable" :disabled="isEditMode" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" align="center">
                  <template #default="scope">
                    <el-button type="danger" icon="Delete" circle plain @click="removeField(scope.$index)" />
                  </template>
                </el-table-column>
             </el-table>
          </div>
          
          <div class="add-field-placeholder" @click="addField">
            <el-icon><Plus /></el-icon> <span>点击添加更多业务字段...</span>
          </div>

          <el-divider />
          
          <div class="section-title">
            <el-icon><User /></el-icon> 权限访问控制
          </div>
          <el-form label-position="top">
            <el-form-item label="授权用户列表">
              <el-select
                v-model="fillUserList"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="选择或搜索用户，留空表示对所有人开放"
                style="width: 100%"
                :loading="userListLoading"
              >
                <el-option
                  v-for="u in allUsers"
                  :key="u"
                  :label="u"
                  :value="u"
                />
              </el-select>
              <div style="font-size: 12px; color: #94a3b8; margin-top: 4px;">不选择任何用户 = 所有人都可以查看和填报</div>
            </el-form-item>
          </el-form>
        </el-card>
      </div>
    </div>

    <el-dialog
      v-model="importDialogVisible"
      title="Excel 结构识别配置"
      width="560px"
      destroy-on-close
      class="custom-dialog"
    >
      <div class="import-config-body" v-loading="isParsing" element-loading-text="正在智能解析 Excel 结构...">
        <div class="config-section">
          <div class="item-label">识别模式</div>
          <el-radio-group v-model="importConfig.kvPairEnabled" class="mode-cards">
            <el-radio :label="false" border>
              <div class="radio-content">
                <span class="m-title">标准字段模式</span>
                <span class="m-desc">推荐：将每一列都识别为独立的数据库字段。</span>
              </div>
            </el-radio>
            <el-radio :label="true" border>
              <div class="radio-content">
                <span class="m-title">键值对模式 (Key/Value)</span>
                <span class="m-desc">灵活：自动识别成对的属性并归集到 JSON 容器中。</span>
              </div>
            </el-radio>
          </el-radio-group>
        </div>

        <div class="upload-area">
          <el-upload
            drag
            action=""
            :auto-upload="false"
            :show-file-list="false"
            :on-change="onFileChange"
            accept=".xlsx"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">
              将 Excel 文件拖到此处，或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">仅支持 .xlsx 格式，首行需为表头</div>
            </template>
          </el-upload>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, watch, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Setting, Notification, Grid, User, Plus, Upload, Delete, Check, Platform, UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'

const router = useRouter()
const route = useRoute()

const isEditMode = ref(!!route.params.id)

const formMeta = reactive({
  name: '',
  tableName: '',
  status: 'ACTIVE',
  deadline: '',
  reminderDays: 3,
  reminderTime: '09:00',
  recipientEmails: '',
  reminderMode: 'DEADLINE',
  monthlyDay: 10,
  weeklyDayOfWeek: 1,
  cycleDays: 0,
  fillUserEmails: ''
})

const fields = ref([
  { name: '', columnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false }
])

const importDialogVisible = ref(false)
const importConfig = reactive({
  smartType: true,
  kvPairEnabled: false // 默认改用标准模式
})
const isParsing = ref(false)

// 用户列表（权限分配）
const allUsers = ref([])
const fillUserList = ref([])
const userListLoading = ref(false)

const loadUserList = async () => {
  userListLoading.value = true
  try {
    const res = await axios.get('/api/user/list')
    allUsers.value = res.data || []
  } catch (e) {
    allUsers.value = []
  } finally {
    userListLoading.value = false
  }
}

// 用户名加上 @furniwell.com 后缀生成邮箱列表
const allUserEmails = computed(() => 
  allUsers.value.map(u => u.includes('@') ? u : u + '@furniwell.com')
)
const recipientList = ref([])

const addField = () => {
  fields.value.push({ name: '', columnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false })
}

const handleDbTypeChange = (dbType, row) => {
  const typeStr = (dbType || '').toUpperCase()
  if (typeStr.includes('TIMESTAMP') || typeStr.includes('DATE') || typeStr.includes('TIME')) {
      row.type = 'datetime'
  } else if (typeStr.includes('INT') || typeStr.includes('NUMERIC') || typeStr.includes('DECIMAL') || typeStr.includes('FLOAT') || typeStr.includes('DOUBLE')) {
      row.type = 'number'
  } else if (typeStr.includes('TEXT') || typeStr.includes('JSON')) {
      row.type = 'textarea'
  } else if (typeStr.includes('BOOLEAN') || typeStr.includes('BOOL')) {
      row.type = 'switch'
  } else {
      row.type = 'input'
  }
}

const removeField = (index) => {
  if (fields.value.length > 1) {
    fields.value.splice(index, 1)
  }
}

const onFileChange = async (uploadFile) => {
  if (!uploadFile.raw) return
  const file = uploadFile.raw
  const formData = new FormData()
  formData.append('file', file)
  formData.append('smartType', importConfig.smartType)
  formData.append('kvPairEnabled', importConfig.kvPairEnabled)
  // 模式统一：前端不再选择模式，由后端执行最全的智能拆分识别

  isParsing.value = true
  try {
    const res = await axios.post('/api/fill/forms/parseExcel', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (res.data && res.data.length > 0) {
      fields.value = res.data.map(f => {
        const row = {
          name: f.name,
          columnName: f.columnName,
          type: f.type || 'input',
          dbType: f.dbType || 'VARCHAR(255)',
          required: f.required || false,
          filterable: f.filterable || false
        }
        // 导入时自动执行一次推断识别，确保 type 字段正确
        handleDbTypeChange(row.dbType, row)
        return row
      })
        
      // 汇总报告
      const types = res.data.reduce((acc, f) => {
        acc[f.type] = (acc[f.type] || 0) + 1
        return acc
      }, {})
      let report = `成功解析出 ${res.data.length} 个字段。`
      if (types.datetime) report += ` 自动识别 ${types.datetime} 个日期。`
      if (types.select) report += ` 识别 ${types.select} 个下拉列表。`
        
      ElMessage.success(report)
      if (!formMeta.name) formMeta.name = file.name.replace(/\.[^/.]+$/, "")
      importDialogVisible.value = false // 成功后关闭
    } else {
      ElMessage.warning('未在 Excel 中识别出有效的业务字段')
    }
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.response?.data?.message || '网络异常'))
  } finally {
    isParsing.value = false
  }
}

const submitFormAndCreateTable = async () => {
  if (!formMeta.name || !formMeta.tableName) {
    ElMessage.error('名称和物理表名必填')
    return
  }
  const formattedFields = fields.value.map(f => ({
    name: f.name,
    columnName: f.columnName,
    type: f.type,
    dbType: f.dbType,
    required: f.required,
    filterable: f.filterable,
    options: null
  }))

  const payload = {
    ...formMeta,
    deadline: formMeta.reminderMode === 'DEADLINE' ? (formMeta.deadline || null) : null,
    recipientEmails: recipientList.value.length > 0 ? JSON.stringify(recipientList.value) : null,
    fillUserEmails: fillUserList.value.length > 0 ? JSON.stringify(fillUserList.value) : null,
    forms: JSON.stringify(formattedFields)
  }

  try {
    await axios.post('/api/fill/forms/createTable', payload)
    ElMessage.success('发布成功！')
    router.push('/forms')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '发布失败')
  }
}

const loadFormForEdit = async () => {
  const id = route.params.id
  try {
    const res = await axios.get(`/api/fill/forms/${id}`)
    Object.assign(formMeta, res.data)
    if (res.data.recipientEmails) {
        try { recipientList.value = JSON.parse(res.data.recipientEmails) } catch (e) { recipientList.value = [] }
    }
    if (res.data.fillUserEmails) {
        try { fillUserList.value = JSON.parse(res.data.fillUserEmails) } catch (e) { fillUserList.value = [] }
    }
    if (res.data.forms) {
        const parsed = JSON.parse(res.data.forms)
        fields.value = parsed.map(f => ({ ...f, id_mark: true })) // 标记为已有字段
    }
  } catch (e) {}
}

const updateFormMeta = async () => {
  const id = route.params.id
  const payload = {
    ...formMeta,
    recipientEmails: recipientList.value.length > 0 ? JSON.stringify(recipientList.value) : null,
    fillUserEmails: fillUserList.value.length > 0 ? JSON.stringify(fillUserList.value) : null,
    forms: JSON.stringify(fields.value.map(f => {
      const { id_mark, ...rest } = f
      return rest
    }))
  }
  try {
    await axios.put(`/api/fill/forms/${id}`, payload)
    ElMessage.success('修改成功')
    router.push('/forms')
  } catch (e) {}
}

onMounted(() => {
  loadUserList()
  if (isEditMode.value) loadFormForEdit()
})
</script>

<style scoped>
.form-designer-page {
  animation: fadeIn 0.4s ease-out;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32px;
}

.page-title {
  font-size: 26px;
  font-weight: 800;
  color: #0f172a;
  margin: 0 0 6px;
}

.page-subtitle {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

.designer-container {
  display: flex;
  gap: 28px;
  align-items: flex-start;
}

.config-sidebar {
  width: 400px;
  flex-shrink: 0;
}

.fields-main {
  flex: 1;
}

.form-row {
  display: flex;
  gap: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  color: #334155;
  margin-bottom: 20px;
}

.section-title .el-icon {
  color: var(--primary-color);
}

.card-header-flex {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.field-actions {
  display: flex;
  gap: 12px;
}

.field-info {
  font-size: 13px;
  color: #94a3b8;
  margin-top: -8px;
  margin-bottom: 12px;
}

.fields-list {
  border: 1px solid #f1f5f9;
  border-radius: 12px;
  overflow: hidden;
}

.add-field-placeholder {
  margin-top: 20px;
  border: 2px dashed #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  text-align: center;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-weight: 500;
}

.add-field-placeholder:hover {
  background: #f8fafc;
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.mode-radio :deep(.el-radio-button__inner) {
  padding: 8px 16px;
}

.sidebar-card, .fields-card {
  border-radius: 16px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 增强样式 */
.custom-dialog :deep(.el-dialog__body) {
  padding-top: 10px;
}

.import-config-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.item-label {
  font-size: 14px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 12px;
}

.mode-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.mode-cards :deep(.el-radio) {
  margin-right: 0;
  height: auto;
  padding: 12px 16px;
  display: flex;
  align-items: center;
}

.radio-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.m-title {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
}

.m-desc {
  font-size: 12px;
  color: #94a3b8;
}

.switches-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.upload-area {
  margin-top: 10px;
}

.text-primary {
  color: var(--primary-color);
}
</style>
