<template>
  <div class="form-list-page">
    <div class="filter-bar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索模板名称或物理表名..."
        prefix-icon="Search"
        clearable
        class="search-input"
        @clear="handleFilter"
      />
      <el-select v-model="statusFilter" placeholder="模板状态" clearable class="status-select">
        <el-option label="所有状态" value="" />
        <el-option label="运行中" value="ACTIVE" />
        <el-option label="已过期" value="EXPIRED" />
        <el-option label="待发布" value="DISABLED" />
      </el-select>
      <el-button type="primary" icon="Search" @click="handleFilter">查询</el-button>
      <el-button icon="Refresh" @click="resetFilter">重置</el-button>
    </div>

    <el-card class="table-card" shadow="never">
      <el-table :data="paginatedForms" style="width: 100%" v-loading="loading">
        <el-table-column prop="name" label="模板名称" min-width="200">
          <template #default="scope">
            <div class="form-name-cell">
              <el-icon class="form-icon"><Document /></el-icon>
              <span class="name-text">{{ scope.row.name }}</span>
            </div>
          </template>
        </el-table-column>
        
        <el-table-column prop="tableName" label="物理表名" width="180">
          <template #default="scope">
            <code class="table-code">{{ scope.row.tableName }}</code>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="120" align="center">
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

        <el-table-column prop="deadline" label="截止时间" width="200">
          <template #default="scope">
            <div class="time-cell">
              <el-icon v-if="scope.row.deadline"><Timer /></el-icon>
              <span>{{ scope.row.deadline ? new Date(scope.row.deadline).toLocaleString() : '长期有效' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="280" align="right" fixed="right">
          <template #default="scope">
            <el-button-group>
              <el-tooltip content="查看并管理数据" placement="top">
                <el-button type="info" size="small" plain icon="View" @click="$router.push(`/fill/${scope.row.id}?admin=true`)">数据</el-button>
              </el-tooltip>
              <el-tooltip v-if="isAdmin" content="编辑模板配置" placement="top">
                <el-button type="primary" size="small" plain icon="Edit" @click="$router.push(`/designer/${scope.row.id}`)">设计</el-button>
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
</template>

<script setup>
import { ref, onMounted, inject, computed } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const currentUser = inject('currentUser', ref(''))
const isAdmin = inject('isAdmin', ref(false))

const forms = ref([])
const loading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('')
const filteredForms = ref([])

const currentPage = ref(1)
const pageSize = ref(10)

// 计算属性：当前页显示的数据
const paginatedForms = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredForms.value.slice(start, end)
})

const handleFilter = () => {
  currentPage.value = 1 // 过滤时重置为第一页
  filteredForms.value = forms.value.filter(form => {
    const matchesSearch = !searchQuery.value || 
      form.name.toLowerCase().includes(searchQuery.value.toLowerCase()) ||
      form.tableName.toLowerCase().includes(searchQuery.value.toLowerCase())
    
    const matchesStatus = !statusFilter.value || form.status === statusFilter.value
    
    return matchesSearch && matchesStatus
  })
}

const handlePaginationChange = () => {
  // 分页改变逻辑，如果需要可以在这里加载后端数据
}

const resetFilter = () => {
  searchQuery.value = ''
  statusFilter.value = ''
  filteredForms.value = [...forms.value]
}

const loadForms = async () => {
  loading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    if (isAdmin.value) params.isAdmin = true
    const res = await axios.get('/api/fill/forms', { params })
    forms.value = res.data
    // 初始化显示列表
    handleFilter()
  } catch (e) {
    ElMessage.error('获取表单模板失败')
  } finally {
    loading.value = false
  }
}

const handleDeleteForm = async (id) => {
  try {
    await axios.delete(`/api/fill/forms/${id}`)
    ElMessage.success('模板及物理表已永久删除')
    await loadForms()
  } catch (e) {
    ElMessage.error('删除操作失败')
  }
}

onMounted(() => {
  loadForms()
})
</script>


<style scoped>
.form-list-page {
  animation: fadeIn 0.4s ease-out;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 32px;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px;
}

.page-subtitle {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 12px;
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
</style>
