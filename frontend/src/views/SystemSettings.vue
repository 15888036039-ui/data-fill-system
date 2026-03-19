<template>
  <div class="system-settings">
    <div class="page-header">
      <div class="header-actions">
        <el-button type="primary" size="large" icon="Check" @click="saveAll" :loading="saving">保存配置</el-button>
      </div>
    </div>

    <el-card class="settings-card" shadow="never">

      <el-tabs v-model="activeTab">
        <!-- 字典管理 -->
        <el-tab-pane label="数仓字典 (中英文映射)" name="dict">
          <div class="tab-content">
            <el-alert title="这些映射用于将 Excel 中文表头自动转为数仓规范的英文列名。" type="info" show-icon :closable="false" style="margin-bottom: 15px;" />
            
            <div class="dict-actions">
              <el-button type="success" size="small" @click="addDictRow">添加映射</el-button>
            </div>
            
            <el-table :data="dwDictList" border stripe height="500">
              <el-table-column label="中文名称" prop="cn">
                <template #default="scope">
                  <el-input v-model="scope.row.cn" placeholder="如：部门" />
                </template>
              </el-table-column>
              <el-table-column label="英文列名" prop="en">
                <template #default="scope">
                  <el-input v-model="scope.row.en" placeholder="如：dept" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="scope">
                  <el-button type="danger" link @click="removeDictRow(scope.$index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- 关键词库 -->
        <el-tab-pane label="智能配对基因库 (一对一)" name="keywords">
          <div class="tab-content">
            <el-alert title="定义精确的 Key-Value 配对关系。当 Excel 中出现带相同序号的列（如 Desc 1 和 Amt 1）且命中了定义好的配对时，系统将自动进行归并。" type="warning" show-icon :closable="false" style="margin-bottom: 20px;" />
            
            <div class="dict-actions">
              <el-button type="success" size="small" @click="addPairRow">添加配对基因</el-button>
            </div>

            <el-table :data="kwPairsList" border stripe height="500">
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
              <el-table-column label="操作" width="80" align="center">
                <template #default="scope">
                  <el-button type="danger" link @click="removePairRow(scope.$index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- 列名规范 -->
        <el-tab-pane label="列名生成规范" name="naming">
          <div class="tab-content">
            <el-alert title="定义 Excel 表头转为数据库列名的转换策略。" type="info" show-icon :closable="false" style="margin-bottom: 20px;" />
            
            <el-form :model="namingConvention" label-width="180px" style="max-width: 600px;">
              <el-form-item label="默认自动前缀">
                <el-input v-model="namingConvention.column_prefix" placeholder="如：field_" />
                <div class="form-tip">当识别不到有效列名或列名以数字开头时补齐的前缀</div>
              </el-form-item>
              
              <el-form-item label="拼音缩写阈值">
                <el-input-number v-model="namingConvention.initials_threshold" :min="1" :max="20" />
                <div class="form-tip">汉字超过此字数时，将由全拼转换为缩写（如：创建时间 -> cjsj）</div>
              </el-form-item>

              <el-form-item label="列名最大长度">
                <el-input-number v-model="namingConvention.max_length" :min="10" :max="64" />
                <div class="form-tip">超过此长度的字段名将被截断</div>
              </el-form-item>

              <el-form-item label="非法字符剔除(正则)">
                <el-input v-model="namingConvention.replace_regex" placeholder="正则表达式" />
                <div class="form-tip">匹配这些正则的字符将被完全剔除，默认涵盖空格及各类括号</div>
              </el-form-item>

              <el-divider content-position="left">高级补偿规则</el-divider>

              <el-form-item label="数字开头补偿前缀">
                <el-input v-model="namingConvention.numeric_prefix" placeholder="如：col_" />
                <div class="form-tip">如果转换后的列名以数字开头，将自动补齐此前缀</div>
              </el-form-item>

              <el-form-item label="拼音单词分隔符">
                <el-input v-model="namingConvention.pinyin_separator" placeholder="如：_" />
                <div class="form-tip">全拼模式下每个拼音字母之间的间隔符号</div>
              </el-form-item>

              <el-form-item label="括号英文提取长度">
                <el-input-number v-model="namingConvention.bracket_eng_min_len" :min="1" :max="10" />
                <div class="form-tip">括号内英文字段至少达到此长度才会被优先提取</div>
              </el-form-item>

              <el-form-item label="字典匹配模式">
                <el-select v-model="namingConvention.dict_match_mode" style="width: 100%;">
                  <el-option label="包含匹配 (推荐，模糊识别)" value="contains" />
                  <el-option label="精确匹配 (严格对应映射表)" value="exact" />
                </el-select>
                <div class="form-tip">数仓字典映射时，是匹配表头包含关键词还是必须完全一致</div>
              </el-form-item>
            </el-form>
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

const activeTab = ref('dict')
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
  padding: 20px;
  max-width: 1000px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: 24px;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.system-settings {
  padding: 0;
  max-width: 1200px;
  margin: 0 auto;
}

.tab-content {
  padding: 10px 0;
}

.dict-actions {
  margin-bottom: 10px;
  display: flex;
  justify-content: flex-end;
}

.section-title {
  font-weight: bold;
  margin-bottom: 10px;
  color: #333;
}

.keyword-tip, .form-tip {
  margin-top: 5px;
  font-size: 12px;
  color: #999;
}

:deep(.el-tabs__content) {
  overflow: visible;
}
</style>
