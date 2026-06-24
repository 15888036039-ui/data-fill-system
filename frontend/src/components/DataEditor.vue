<template>
  <el-dialog 
    v-model="visible" 
    :title="editingRowId ? '修改数据' : '单行录入'"
    width="650px"
    destroy-on-close
    @close="handleClose"
  >
    <DynamicForm 
      v-if="visible"
      :schema="schemaFields" 
      :initial-data="editingData"
      @submit="submitData"
      @cancel="handleClose" 
    />
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import DynamicForm from './DynamicForm.vue'

const props = defineProps({
  modelValue: Boolean,
  editingRowId: [String, Number],
  editingData: Object,
  schemaFields: Array,
  formId: String,
  userEmail: String,
  isAdmin: Boolean
})

const emit = defineEmits(['update:modelValue', 'success'])

const visible = ref(props.modelValue)

watch(() => props.modelValue, (val) => {
  visible.value = val
})

watch(() => visible.value, (val) => {
  emit('update:modelValue', val)
})

const handleClose = () => {
  visible.value = false
}

const submitData = async (formDataVal) => {
  try {
    const payload = { ...formDataVal }
    // 统一注入用户与申请人信息（用于权限与审批流）
    if (props.userEmail) {
      payload.load_user = props.userEmail
      payload.applicantEmail = props.userEmail
    }
    
    if (props.editingRowId) {
      // 更新操作
      await axios.put(`/api/fill/data/${props.formId}/${props.editingRowId}`, payload, {
        params: { userEmail: props.userEmail, isAdmin: props.isAdmin }
      })
      ElMessage.success('数据已成功修改')
    } else {
      // 新增操作
      await axios.post(`/api/fill/data/${props.formId}`, payload, {
        params: { userEmail: props.userEmail }
      })
      ElMessage.success('填报成功！')
    }
    
    emit('success')
    handleClose()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败，请重试')
  }
}
</script>
