<template>
  <el-form 
    :model="formData" 
    label-position="top" 
    class="premium-dynamic-form"
  >
    <el-row :gutter="20">
      <el-col 
        v-for="field in schema" 
        :key="field.columnName" 
        :span="field.type === 'textarea' ? 24 : 12"
      >
        <el-form-item 
          :label="field.name" 
          :required="field.required"
          class="form-item-styled"
        >
          <!-- 文本输入框 -->
          <el-input 
            v-if="field.type === 'input' || field.type === 'varchar'" 
            v-model="formData[field.columnName]" 
            :placeholder="'请输入' + field.name" 
            clearable
          />
          
          <!-- 多行文本 -->
          <el-input
            v-if="field.type === 'textarea'"
            v-model="formData[field.columnName]"
            type="textarea"
            :rows="3"
            :placeholder="'请输入' + field.name"
          />
          
          <!-- 数字输入框 -->
          <el-input-number 
            v-if="field.type === 'number' || field.type === 'int'" 
            v-model="formData[field.columnName]" 
            :placeholder="'请输入' + field.name" 
            style="width: 100%"
            controls-position="right"
          />
          
          <!-- 下拉选择框 -->
          <el-select 
            v-if="field.type === 'select'" 
            v-model="formData[field.columnName]" 
            placeholder="请选择"
            style="width: 100%"
            clearable
          >
            <el-option 
              v-for="opt in field.options" 
              :key="opt" 
              :label="opt" 
              :value="opt" 
            />
          </el-select>
    
          <!-- 日期选择框 -->
          <el-date-picker
            v-if="field.type === 'datetime'"
            v-model="formData[field.columnName]"
            type="datetime"
            placeholder="选择日期时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
          />
        </el-form-item>
      </el-col>
    </el-row>

    <div class="form-actions">
      <el-button @click="$emit('cancel')" size="large">取消操作</el-button>
      <el-button type="primary" size="large" @click="handleSubmit" icon="Check">提交保存</el-button>
    </div>
  </el-form>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  schema: {
    type: Array,
    required: true
  },
  initialData: {
    type: Object,
    default: () => ({})
  }
})
const emit = defineEmits(['submit', 'cancel'])

const formData = ref({ ...props.initialData })

const handleSubmit = () => {
  emit('submit', formData.value)
}
</script>

<style scoped>
.premium-dynamic-form {
  padding: 10px 0;
}

.form-item-styled :deep(.el-form-item__label) {
  font-weight: 600;
  color: #475569;
  margin-bottom: 8px !important;
  font-size: 14px;
}

.form-item-styled :deep(.el-input__wrapper),
.form-item-styled :deep(.el-textarea__inner) {
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  padding: 4px 12px;
}

.form-item-styled :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--primary-color) inset !important;
}

.form-actions {
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid #f1f5f9;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.form-actions .el-button {
  min-width: 120px;
}
</style>
