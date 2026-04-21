<template>
  <div class="system-settings">
    <div class="page-header">
      <div class="header-actions">
        <el-button type="primary" size="large" icon="Check" @click="saveAll" :loading="saving">保存配置</el-button>
      </div>
    </div>

    <el-card class="settings-card" shadow="never">

      <el-tabs v-model="activeTab">
        <!-- 列名识别中心 (合并了原有的字典和规范) -->
        <el-tab-pane label="列名识别中心 (映射与策略)" name="naming">
          <div class="tab-content">
            <el-alert 
              title="配置 Excel 中文表头转数据库列名的完整逻辑。系统会按照‘精准字典 > 英文提取 > 智能拼音 > 保底策略’的优先级自动生成规范列名。" 
              type="info" 
              show-icon 
              :closable="false" 
              style="margin-bottom: 20px;" 
            />
            
            <div class="naming-layout">
              <!-- 左侧：全局转换策略 -->
              <div class="left-config">
                <div class="section-header">
                  <div class="section-title"><el-icon><Setting /></el-icon> 1. 全局转换策略参数</div>
                </div>
                <el-form :model="namingConvention" label-position="top" class="settings-form mini-form">
                  <div class="form-grid">
                    <el-form-item label="默认自动前缀">
                      <el-input v-model="namingConvention.column_prefix" placeholder="如：field_" />
                    </el-form-item>
                    <el-form-item label="拼音缩写阈值">
                      <el-input-number v-model="namingConvention.initials_threshold" :min="1" :max="20" style="width: 100%" />
                    </el-form-item>
                  </div>

                  <div class="form-grid">
                    <el-form-item label="列名最大长度">
                      <el-input-number v-model="namingConvention.max_length" :min="10" :max="64" style="width: 100%" />
                    </el-form-item>
                    <el-form-item label="非法字符清洗(正则)">
                      <el-input v-model="namingConvention.replace_regex" placeholder="正则" />
                    </el-form-item>
                  </div>

                  <el-form-item label="字典匹配模式">
                    <el-select v-model="namingConvention.dict_match_mode" style="width: 100%;">
                      <el-option label="包含匹配 (模糊识别)" value="contains" />
                      <el-option label="精确匹配 (严格对应)" value="exact" />
                    </el-select>
                  </el-form-item>

                  <el-divider />
                  
                  <div class="section-header">
                    <div class="section-title"><el-icon><MagicStick /></el-icon> 2. 规则试运行与调试</div>
                  </div>
                  <div class="test-container">
                    <el-input 
                      v-model="ruleTestInput" 
                      placeholder="输入中文表头测试，如：创建时间" 
                      class="test-input"
                      @keyup.enter="runRuleTest"
                    >
                      <template #append>
                        <el-button @click="runRuleTest">运行</el-button>
                      </template>
                    </el-input>
                    <div v-if="ruleTestResult" class="test-result-box mini">
                      <span class="res-label">生成结果：</span>
                      <code class="res-value">{{ ruleTestResult }}</code>
                    </div>
                  </div>

                  <div class="logic-flow-horizontal mini">
                    <div class="logic-title">⚡ 优先级：</div>
                    <div class="flow-steps">
                      <span>英文直取</span> <el-icon><ArrowRight /></el-icon>
                      <span class="highlighter">字典映射</span> <el-icon><ArrowRight /></el-icon>
                      <span>拼音/全拼</span> <el-icon><ArrowRight /></el-icon>
                      <span>清洗保底</span>
                    </div>
                  </div>
                </el-form>
              </div>

              <!-- 右侧：属性高优映射表 (原数仓字典) -->
              <div class="right-dict">
                <div class="section-header-flex">
                   <div class="section-title"><el-icon><Reading /></el-icon> 3. 精准词典映射 (高优先级)</div>
                   <el-button type="success" size="small" icon="Plus" @click="addDictRow">添加词条</el-button>
                </div>
                <el-table :data="dwDictList" border height="550" class="dict-table-compact">
                  <el-table-column label="中文名称" prop="cn">
                    <template #default="scope">
                      <el-input v-model="scope.row.cn" size="small" placeholder="如：年龄" />
                    </template>
                  </el-table-column>
                  <el-table-column label="映射英文列名" prop="en">
                    <template #default="scope">
                      <el-input v-model="scope.row.en" size="small" placeholder="如：age" />
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="60" align="center">
                    <template #default="scope">
                      <el-button type="danger" link @click="removeDictRow(scope.$index)"><el-icon><Delete /></el-icon></el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <!-- 关键词库 remains separate as it is for JSON pairs identification -->
        <el-tab-pane label="智能配对基因库 (KV 归集)" name="keywords">
          <div class="tab-content">
            <el-alert title="定义精确的 Key-Value 配对关系 (如 Desc 和 Amount)。用于识别 Excel 中成对出现的复杂字段，系统会自动将其归并在 JSON 列中。" type="warning" show-icon :closable="false" style="margin-bottom: 20px;" />
            
            <div class="dict-actions">
              <el-button type="success" size="small" icon="Plus" @click="addPairRow">添加配对基因</el-button>
            </div>

            <el-table :data="kwPairsList" border height="550">
              <el-table-column label="Key 特征列名 (键)" prop="key">
                <template #default="scope">
                  <el-input v-model="scope.row.key" placeholder="如：description" />
                </template>
              </el-table-column>
              <el-table-column label="Value 特征列名 (值)" prop="val">
                <template #default="scope">
                  <el-input v-model="scope.row.val" placeholder="如：amount" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="center">
                <template #default="scope">
                  <el-button type="danger" link @click="removePairRow(scope.$index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const activeTab = ref('naming')
const dwDictList = ref([])
const kwPairsList = ref([])
const namingConvention = ref({
  column_prefix: 'field_',
  initials_threshold: 4,
  max_length: 50,
  replace_regex: '[\\s\\[\\]\\(\\)（）【】]',
  numeric_prefix: 'col_',
  pinyin_separator: '_',
  bracket_eng_min_len: 2,
  dict_match_mode: 'contains'
})
const saving = ref(false)
const ruleTestInput = ref('')
const ruleTestResult = ref('')

const runRuleTest = async () => {
  if (!ruleTestInput.value.trim()) return
  try {
    const res = await axios.post('/api/system-config/test-naming', { input: ruleTestInput.value })
    ruleTestResult.value = res.data.result
  } catch (e) {
    ElMessage.error('测试失败')
  }
}

const loadConfigs = async () => {
  try {
    const res = await axios.get('/api/system-config/all')
    const configs = res.data
    
    configs.forEach(c => {
      if (c.configKey === 'dw_dict') {
        const obj = JSON.parse(c.configValue)
        dwDictList.value = Object.entries(obj).map(([cn, en]) => ({ cn, en }))
      } else if (c.configKey === 'kw_pairs') {
        const obj = JSON.parse(c.configValue)
        kwPairsList.value = Object.entries(obj).map(([key, val]) => ({ key, val }))
      } else if (c.configKey === 'naming_convention') {
        namingConvention.value = JSON.parse(c.configValue)
      }
    })
  } catch (e) {
    console.error('Failed to load configs', e)
    ElMessage.error('加载配置失败')
  }
}

const addDictRow = () => {
  dwDictList.value.unshift({ cn: '', en: '' })
}

const removeDictRow = (index) => {
  dwDictList.value.splice(index, 1)
}

const addPairRow = () => {
  kwPairsList.value.unshift({ key: '', val: '' })
}

const removePairRow = (index) => {
  kwPairsList.value.splice(index, 1)
}

const saveAll = async () => {
  saving.value = true
  try {
    // 保存字典
    const dictObj = {}
    dwDictList.value.forEach(item => {
      if (item.cn.trim() && item.en.trim()) {
        dictObj[item.cn.trim()] = item.en.trim()
      }
    })

    // 保存配对
    const pairObj = {}
    kwPairsList.value.forEach(item => {
      if (item.key.trim() && item.val.trim()) {
        pairObj[item.key.trim().toLowerCase()] = item.val.trim().toLowerCase()
      }
    })
    
    await Promise.all([
      axios.post('/api/system-config/update', { key: 'dw_dict', value: dictObj }),
      axios.post('/api/system-config/update', { key: 'kw_pairs', value: pairObj }),
      axios.post('/api/system-config/update', { key: 'naming_convention', value: namingConvention.value })
    ])
    
    ElMessage.success('配置保存成功，即刻生效！')
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadConfigs()
})
</script>

<style scoped>
.system-settings {
  padding: 10px 24px;
  width: 100%;
  margin: 0;
}

.page-header {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: 8px;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.system-settings-container {
  padding: 0;
  width: 100%;
  margin: 0;
}

.tab-content {
  padding: 5px 0;
}

.dict-actions {
  margin-bottom: 20px;
  display: flex;
  justify-content: flex-end;
}

.naming-layout {
  display: flex;
  gap: 32px;
  align-items: flex-start;
}

.left-config {
  flex: 0 0 420px;
}

.right-dict {
  flex: 1;
  min-width: 0;
}

.mini-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.section-header, .section-header-flex {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
}

.section-header-flex {
  justify-content: space-between;
}

.section-title {
  font-weight: 700;
  color: #1e293b;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}

.form-tip {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
  line-height: 1.4;
}

.test-container {
  margin-bottom: 20px;
}

.test-result-box.mini {
  margin-top: 12px;
  padding: 8px 16px;
  background: #fdfdfd;
  border-radius: 6px;
  border: 1px dashed #cbd5e1;
  font-size: 13px;
  display: flex;
  align-items: center;
}

.res-value {
  font-family: 'JetBrains Mono', 'Monaco', monospace;
  color: #2563eb;
  font-weight: 700;
  margin-left: 4px;
}

.logic-flow-horizontal.mini {
  background: #f8fafc;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.logic-title {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 8px;
}

.flow-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}

.flow-steps .highlighter {
  color: #2563eb;
  font-weight: 700;
  background: #eff6ff;
  padding: 2px 6px;
  border-radius: 4px;
}

.dict-table-compact :deep(.el-table__cell) {
  padding: 4px 0;
}

:deep(.el-tabs__content) {
  overflow: visible;
}
</style>
