<template>
  <div class="toolbar-row filter-line">
    <div class="filter-inputs-group">
      <template v-for="field in filterFields" :key="'filter_'+field.columnName">
        <div class="filter-item">
          <span class="filter-label">{{ field.name }}</span>
          <el-select
            v-if="field.filterType === 'select'"
            v-model="internalParams[field.columnName]"
            :placeholder="'请选择' + field.name"
            clearable
            filterable
            class="filter-input"
          >
            <el-option
              v-for="opt in (field.filterOptions || field.options || [])"
              :key="opt"
              :label="opt"
              :value="opt"
            />
          </el-select>
          <el-date-picker
            v-else-if="field.filterType === 'daterange'"
            v-model="internalParams[field.columnName]"
            type="daterange"
            single-panel
            placement="bottom-start"
            range-separator="-"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            clearable
            class="filter-input filter-input-range"
          />
          <el-date-picker
            v-else-if="field.filterType === 'monthrange'"
            v-model="internalParams[field.columnName]"
            type="monthrange"
            single-panel
            placement="bottom-start"
            range-separator="-"
            start-placeholder="开始月份"
            end-placeholder="结束月份"
            value-format="YYYY-MM"
            clearable
            class="filter-input filter-input-range"
          />
          <el-input
            v-else
            v-model="internalParams[field.columnName]"
            :placeholder="'请输入' + field.name"
            clearable
            class="filter-input"
            @keyup.enter="handleSearch"
          />
        </div>
      </template>
      <div class="filter-actions-inline">
        <el-button type="primary" size="default" icon="Search" @click="handleSearch">查询</el-button>
        <el-button size="default" icon="RefreshRight" @click="handleReset">重置</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, reactive } from 'vue'

const props = defineProps({
  filterFields: {
    type: Array,
    default: () => []
  },
  initialParams: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits(['search', 'reset'])

const internalParams = reactive({ ...props.initialParams })

watch(() => props.initialParams, (newVal) => {
  Object.keys(internalParams).forEach(key => delete internalParams[key])
  Object.assign(internalParams, newVal)
}, { deep: true })

const handleSearch = () => {
  emit('search', { ...internalParams })
}

const handleReset = () => {
  Object.keys(internalParams).forEach(key => delete internalParams[key])
  emit('reset')
}
</script>

<style scoped>
.filter-line {
  display: flex;
  padding: 12px 20px;
  background: #f8fafc;
  border-bottom: 1px solid #f1f5f9;
}

.filter-inputs-group {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: center;
  width: 100%;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
  white-space: nowrap;
}

.filter-input {
  width: 180px;
}

.filter-actions-inline {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
</style>

<style>
/* 覆盖 Element Plus 时间选择器的根容器及内部间距，避免 scoped 样式限制且不污染全局其他组件 */
.filter-inputs-group .filter-input-range {
  width: 220px !important;
  padding: 0 8px !important;
}

.filter-inputs-group .filter-input-range .el-range-input {
  font-size: 13px !important;
  width: 80px !important;
}

.filter-inputs-group .filter-input-range .el-range-separator {
  padding: 0 !important;
  width: 14px !important;
  color: #94a3b8 !important;
}

.filter-inputs-group .filter-input-range .el-range__icon {
  margin-right: 4px !important;
  margin-left: 0 !important;
}

.filter-inputs-group .filter-input-range .el-range__close-icon {
  width: 14px !important;
}
</style>

