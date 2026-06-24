<template>
  <el-dialog
    v-model="visible"
    title="数据操作日志"
    width="600px"
    custom-class="log-dialog"
    @close="handleClose"
  >
    <div v-loading="loading" style="min-height: 200px; max-height: 500px; overflow-y: auto; padding: 10px;">
      <el-empty v-if="!operationLogs.length && !loading" description="暂无操作记录" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="(log, index) in operationLogs"
          :key="log.id"
          :timestamp="log.createTime"
          :type="getLogType(log.operationType)"
          :hollow="index !== 0"
        >
          <div class="log-item-content">
            <div class="log-header">
              <span class="log-user">{{ log.userEmail }}</span>
              <el-tag size="small" :type="getLogType(log.operationType)" effect="plain" class="log-tag">
                {{ getLogTypeText(log.operationType) }}
              </el-tag>
            </div>
            <div class="log-desc">{{ log.operationDesc }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, watch, inject } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: Boolean,
  formId: String
})

const emit = defineEmits(['update:modelValue'])

const currentUser = inject('currentUser', ref(''))
const visible = ref(props.modelValue)
const loading = ref(false)
const operationLogs = ref([])

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val && props.formId) {
    loadLogs()
  }
})

watch(() => visible.value, (val) => {
  emit('update:modelValue', val)
})

const handleClose = () => {
  visible.value = false
}

const loadLogs = async () => {
  loading.value = true
  try {
    const res = await axios.get(`/api/fill/data/${props.formId}/logs`, {
      params: { userEmail: currentUser.value }
    })
    operationLogs.value = res.data || []
  } catch (e) {
    ElMessage.error('加载操作日志失败')
  } finally {
    loading.value = false
  }
}

const getLogType = (type) => {
  switch (type) {
    case 'ADD': return 'success'
    case 'UPDATE': return 'warning'
    case 'DELETE': return 'danger'
    case 'UPLOAD': return 'primary'
    default: return 'info'
  }
}

const getLogTypeText = (type) => {
  switch (type) {
    case 'ADD': return '新增'
    case 'UPDATE': return '修改'
    case 'DELETE': return '删除'
    case 'UPLOAD': return '导入'
    default: return type
  }
}
</script>

<style scoped>
.log-item-content {
  padding: 4px 8px;
}
.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.log-user {
  font-weight: 500;
  font-size: 13px;
  color: #334155;
}
.log-desc {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
</style>
