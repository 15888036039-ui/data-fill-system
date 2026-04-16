<template>
  <div class="form-designer-page">
    <div class="page-header sticky-header">
      <div class="header-info">
        <div class="header-breadcrumb">
          <span class="breadcrumb-item" @click="$router.push('/forms')">模板管理</span>
          <el-icon><ArrowRight /></el-icon>
          <span class="breadcrumb-current">{{ isEditMode ? '编辑填报模板' : '创建新模板' }}</span>
        </div>
        <h1 class="page-title">
          {{ isEditMode ? (formMeta.name || '正在读取...') : '新建填报模板' }}
        </h1>
      </div>
      <div class="header-actions">
        <el-button @click="$router.push('/forms')" class="btn-cancel">取消并返回</el-button>
        <el-button v-if="!isEditMode" type="primary" size="large" icon="Platform" @click="submitFormAndCreateTable">{{ bindExistingTableMode ? '确认绑定并发布' : '创建并发布模板' }}</el-button>
        <el-button v-else type="primary" size="large" icon="Check" @click="updateFormMeta">完成并保存</el-button>
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
              <el-input v-model="formMeta.name" placeholder="请输入模板展示名称" />
            </el-form-item>
            <el-form-item label="填报指引 / 备注说明">
              <el-input
                v-model="formMeta.description"
                type="textarea"
                :rows="3"
                placeholder="在此输入填报指引，将以横幅形式展示在填报页面顶部，用于引导用户正确填写数据。"
              />
            </el-form-item>
            <el-divider border-style="dashed" style="margin: 12px 0" />
            <el-form-item label="数据库物理模式 (Schema)" required>
              <el-select
                v-model="formMeta.schemaName"
                filterable
                placeholder="选择数据库模式"
                style="width: 100%"
                :loading="schemaLoading"
                :disabled="isEditMode"
              >
                <el-option
                  v-for="item in availableSchemas"
                  :key="item"
                  :label="item"
                  :value="item"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="数据库物理表名" required>
              <el-input
                v-model="formMeta.tableName"
                placeholder="请输入数据库物理表名"
                :disabled="isEditMode"
              />
            </el-form-item>
            <el-form-item label="数据库表注释">
              <el-input
                v-model="formMeta.tableComment"
                placeholder="请输入物理表注释（可选）"
              />
            </el-form-item>
            <el-form-item label="所属目录">
              <el-select
                v-model="formMeta.folderId"
                clearable
                filterable
                placeholder="选择目录，不选则归入未分类"
                style="width: 100%"
                :loading="folderLoading"
              >
                <el-option label="未分类" value="" />
                <el-option
                  v-for="item in folderOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="分组标识 (Group Tag)">
              <el-input
                v-model="formMeta.groupTag"
                placeholder="用于不同链接的展示过滤"
              />
            </el-form-item>
            <el-form-item label="默认筛选器策略" required>
              <el-select v-model="formMeta.defaultFilterPolicy" style="width: 100%">
                <el-option label="不展示默认筛选器 (NONE)" value="NONE" />
                <el-option label="展示前三个字段 (FIRST_THREE)" value="FIRST_THREE" />
              </el-select>
              <div style="font-size: 12px; color: #94a3b8; margin-top: 4px;">当没有手动指定任何字段为“筛选器”时，系统将采取此兜底策略。</div>
            </el-form-item>
            
            <div class="form-row">
              <el-form-item label="运营状态" style="flex: 1">
                <el-select v-model="formMeta.status" style="width: 100%">
                  <el-option label="运行中（可填报）" value="ACTIVE" />
                  <el-option label="已过期（禁止填报）" value="EXPIRED" />
                  <el-option label="停用（管理员可见）" value="DISABLED" />
                </el-select>
              </el-form-item>
              
              <el-form-item label="用户数据删除方式" style="flex: 1" required>
                <el-select v-model="formMeta.hardDelete" style="width: 100%">
                  <el-option label="软删除 (标记为已删除，管理员可查)" :value="false" />
                  <el-option label="彻底硬删除 (直接从库中移除，谨慎)" :value="true" />
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

            <div v-if="formMeta.reminderMode === 'DEADLINE'">
              <el-form-item label="提醒时间" required>
                <el-date-picker
                  v-model="formMeta.reminderDateTime"
                  type="datetime"
                  placeholder="请选择"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  style="width: 100%"
                />
              </el-form-item>
              <el-form-item label="截止时间" required>
                <el-date-picker
                  v-model="formMeta.deadline"
                  type="datetime"
                  placeholder="请选择"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  style="width: 100%"
                />
              </el-form-item>
            </div>

            <div v-if="formMeta.reminderMode === 'MONTHLY'">
              <div class="form-row">
                <el-form-item label="提醒日 (每月几号)" style="flex: 1" required>
                  <el-input-number v-model="formMeta.monthlyDay" :min="1" :max="31" style="width: 100%" />
                </el-form-item>
                <el-form-item label="提醒时点" style="flex: 1" required>
                  <el-time-picker
                    v-model="formMeta.reminderTime"
                    format="HH:mm"
                    value-format="HH:mm"
                    placeholder="请选择"
                    style="width: 100%"
                  />
                </el-form-item>
              </div>
              <div class="form-row">
                <el-form-item label="截止日 (每月几号)" style="flex: 1" required>
                  <el-input-number v-model="formMeta.deadlineMonthlyDay" :min="1" :max="31" style="width: 100%" />
                </el-form-item>
                <el-form-item label="截止时点" style="flex: 1" required>
                  <el-time-picker
                    v-model="formMeta.deadlineTime"
                    format="HH:mm"
                    value-format="HH:mm"
                    placeholder="请选择"
                    style="width: 100%"
                  />
                </el-form-item>
              </div>
            </div>

            <div v-if="formMeta.reminderMode === 'WEEKLY'">
              <div class="form-row">
                <el-form-item label="提醒日 (每周几)" style="flex: 1" required>
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
                <el-form-item label="提醒时点" style="flex: 1" required>
                  <el-time-picker
                    v-model="formMeta.reminderTime"
                    format="HH:mm"
                    value-format="HH:mm"
                    placeholder="请选择"
                    style="width: 100%"
                  />
                </el-form-item>
              </div>
              <div class="form-row">
                <el-form-item label="截止日 (每周几)" style="flex: 1" required>
                  <el-select v-model="formMeta.deadlineWeeklyDayOfWeek" style="width: 100%">
                    <el-option label="星期一" :value="1" />
                    <el-option label="星期二" :value="2" />
                    <el-option label="星期三" :value="3" />
                    <el-option label="星期四" :value="4" />
                    <el-option label="星期五" :value="5" />
                    <el-option label="星期六" :value="6" />
                    <el-option label="星期日" :value="7" />
                  </el-select>
                </el-form-item>
                <el-form-item label="截止时点" style="flex: 1" required>
                  <el-time-picker
                    v-model="formMeta.deadlineTime"
                    format="HH:mm"
                    value-format="HH:mm"
                    placeholder="请选择"
                    style="width: 100%"
                  />
                </el-form-item>
              </div>
            </div>

            <el-form-item label="填报人邮箱 (发送提醒邮件)">
              <el-select
                v-model="recipientList"
                multiple
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="选择通知收件人"
                style="width: 100%"
                :loading="userListLoading"
              >
                <el-option
                  v-for="u in recipientOptions"
                  :key="u.value"
                  :label="u.label"
                  :value="u.value"
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
              <el-button icon="Tickets" plain @click="referenceTemplateDialogVisible = true" v-if="!isEditMode">导入参考模板</el-button>
              <el-button icon="Document" plain @click="existingTableDialogVisible = true" v-if="!isEditMode">从已有表识别</el-button>
              <el-button type="primary" plain icon="Plus" @click="addField">新增字段</el-button>
            </div>
          </div>

          <p class="field-info" v-if="!isEditMode">
            定义用户需要填写的具体内容。完成后我们将为您在后端自动创建对应的物理表结构。
          </p>
          <el-alert
            v-if="!isEditMode && bindExistingTableMode"
            title="检测到物理表已存在，系统将直接绑定您现有的数据库表，不再创建新表。"
            type="warning"
            show-icon
            style="margin-bottom: 16px;"
            :closable="false"
          />
          <el-alert
            v-if="displayMissingColumns.length > 0"
            title="检测到物理表缺少系统必要的审计字段，部分功能（删除、更新、填报记录）将受限。"
            type="error"
            show-icon
            style="margin-bottom: 24px;"
            cross-origin
          >
            <template #default>
              <div>缺失列：<el-tag size="small" type="danger" v-for="c in displayMissingColumns" :key="c" style="margin-right: 4px">{{ c }}</el-tag></div>
              <div style="margin-top: 8px">
                <el-button type="primary" size="small" @click="repairTableColumns" :loading="isRepairing">一键补齐缺失审计列</el-button>
              </div>
            </template>
          </el-alert>
          <el-alert
            v-if="isEditMode"
            title="当前处于元数据编辑模式。管理员可以修改业务字段；系统内置保留列将保持锁定或由内核自动管理。"
            type="success"
            show-icon
            style="margin-bottom: 24px;"
            :closable="false"
          />

          <!-- 键值对预览 -->
          <div v-if="parsedKvConfig.length > 0" class="kv-preview-banner">
            <el-icon style="margin-right: 8px"><Connection /></el-icon>
            <div class="kv-info-text">
              当前已识别出 <b>{{ parsedKvConfig.length }}</b> 组配对 (共 {{ parsedKvConfig.reduce((acc, p) => acc + p.suffixes.length * 2, 0) }} 列原始字段) 将归集到 <b>{{ kvTargetColumnsText }}</b>。
            </div>
            <el-button type="primary" link @click="pairConfirmDialogVisible = true">查看记录</el-button>
          </div>

          <div class="fields-list">
             <el-table :data="fields" style="width: 100%" row-key="_uid">
                <el-table-column type="expand">
                  <template #default="props">
                    <div style="padding: 16px 24px; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0; margin: 8px;">
                      <div style="font-weight: 600; margin-bottom: 16px; font-size: 14px; color: #475569; display: flex; align-items: center;">
                        <el-icon style="margin-right: 6px"><Filter /></el-icon> 字段校验与数据约束
                      </div>
                      <el-form label-position="top" size="default">
                        <el-row :gutter="24">
                          <el-col :span="8">
                            <el-form-item label="正则表达式校验 (Regex)">
                              <el-input v-model="props.row.pattern" placeholder="例: ^1[3-9]\d{9}$" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="8">
                             <el-form-item label="预期校验失败提示">
                              <el-input v-model="props.row.patternMsg" placeholder="请输入发生错误时的提示语" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="8">
                            <div v-if="props.row.type === 'number' || props.row.dbType?.toLowerCase().includes('int') || props.row.dbType?.toLowerCase().includes('numeric')">
                              <div style="display: flex; gap: 12px;">
                                <el-form-item label="最小值" style="flex: 1">
                                  <el-input-number v-model="props.row.min" style="width: 100%" />
                                </el-form-item>
                                <el-form-item label="最大值" style="flex: 1">
                                  <el-input-number v-model="props.row.max" style="width: 100%" />
                                </el-form-item>
                              </div>
                            </div>
                            <div v-else-if="props.row.type === 'input' || props.row.type === 'textarea'">
                              <div style="display: flex; gap: 12px;">
                                <el-form-item label="最小字符数" style="flex: 1">
                                  <el-input-number v-model="props.row.minLength" :min="0" style="width: 100%" />
                                </el-form-item>
                                <el-form-item label="最大字符数" style="flex: 1">
                                  <el-input-number v-model="props.row.maxLength" :min="0" style="width: 100%" />
                                </el-form-item>
                              </div>
                            </div>
                          </el-col>
                        </el-row>
                      </el-form>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="中文显示名" min-width="180">
                  <template #default="scope">
                    <el-input v-model="scope.row.name" placeholder="字段标题" />
                  </template>
                </el-table-column>
                <el-table-column label="物理列名 (英文)" min-width="180">
                  <template #default="scope">
                    <el-input v-model="scope.row.columnName" placeholder="c_name" :disabled="scope.row.systemLocked" />
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
                      :disabled="scope.row.systemLocked"
                      style="width: 100%"
                      @change="(val) => handleDbTypeChange(val, scope.row)"
                    >
                      <el-option label="varchar(255)" value="varchar(255)" />
                      <el-option label="text" value="text" />
                      <el-option label="int4" value="int4" />
                      <el-option label="int8" value="int8" />
                      <el-option label="numeric(15, 2)" value="numeric(15, 2)" />
                      <el-option label="timestamp" value="timestamp" />
                      <el-option label="date" value="date" />
                      <el-option label="bool" value="bool" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="主键" width="70" align="center">
                  <template #default="scope">
                    <el-radio v-model="formMeta.pkColumn" :label="scope.row.columnName"> &nbsp; </el-radio>
                  </template>
                </el-table-column>
                <el-table-column label="必填" width="80" align="center">
                  <template #default="scope">
                    <el-switch v-model="scope.row.required" />
                  </template>
                </el-table-column>
                <el-table-column label="筛选" width="80" align="center">
                  <template #default="scope">
                    <el-switch v-model="scope.row.filterable" />
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

          <div v-if="headerMappings.length > 0" class="preview-section">
            <div class="section-title preview-title">
              <el-icon><Tickets /></el-icon> 表头映射预览
            </div>
            <div class="preview-meta" v-if="referenceParserProfile">
              解析规则：{{ referenceParserProfile }}
            </div>
            <div class="fields-list">
              <el-table :data="headerMappings" style="width: 100%" max-height="280">
                <el-table-column label="上传文件表头" prop="excelHeader" min-width="260" />
                <el-table-column label="对应数据库字段" prop="columnName" min-width="220">
                  <template #default="scope">
                    <span>{{ scope.row.columnName || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="JSON归并" width="100" align="center">
                  <template #default="scope">
                    <el-tag v-if="scope.row.jsonMapped" size="small" type="success">是</el-tag>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>

          <div v-if="referenceRows.length > 0" class="preview-section">
            <div class="section-title preview-title">
              <el-icon><Document /></el-icon> 数据库表参考
            </div>
            <div class="fields-list">
              <el-table :data="referenceRows" style="width: 100%" max-height="360">
                <el-table-column label="字段名称" prop="columnName" min-width="180" />
                <el-table-column label="字段类型" prop="fieldType" min-width="110" />
                <el-table-column label="精度" prop="precision" min-width="90" />
                <el-table-column label="非空" prop="notNull" width="80" align="center" />
                <el-table-column label="取值范围" prop="valueRange" min-width="180" show-overflow-tooltip />
                <el-table-column label="注释" prop="comment" min-width="180" show-overflow-tooltip />
              </el-table>
            </div>
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
                default-first-option
                :reserve-keyword="false"
                placeholder="选择或搜索用户，留空表示仅管理员可见"
                style="width: 100%"
                :loading="userListLoading"
              >
                <el-option
                  v-for="u in fillUserOptions"
                  :key="u.value"
                  :label="u.label"
                  :value="u.value"
                />
              </el-select>
              <div style="font-size: 12px; color: #94a3b8; margin-top: 4px;">不选择任何用户 = 只有管理员可以查看和填报</div>
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

    <el-dialog
      v-model="referenceTemplateDialogVisible"
      title="导入参考模板"
      width="560px"
      destroy-on-close
      class="custom-dialog"
    >
      <div class="import-config-body" v-loading="isParsingReferenceTemplate" element-loading-text="正在解析参考模板...">
        <div class="upload-area">
          <el-upload
            drag
            action=""
            :auto-upload="false"
            :show-file-list="false"
            :on-change="onReferenceTemplateFileChange"
            accept=".xlsx,.csv"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">
              将参考模板拖到此处，或 <em>点击上传</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">支持 .xlsx / .csv，需包含表名、表注释、筛选器、表头映射和数据库表参考区块</div>
            </template>
          </el-upload>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="existingTableDialogVisible"
      title="从已有表识别结构"
      width="520px"
      destroy-on-close
      class="custom-dialog"
    >
      <div class="import-config-body" v-loading="isInspectingExistingTable" element-loading-text="正在读取数据库表结构...">
        <el-form label-position="top">
          <el-form-item label="已有物理模式 (Schema)" required>
            <el-select
              v-model="existingTableForm.schemaName"
              filterable
              placeholder="选择模式"
              style="width: 100%"
              :loading="schemaLoading"
            >
              <el-option
                v-for="item in availableSchemas"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="已有物理表名" required>
            <el-input
              v-model="existingTableForm.tableName"
              placeholder="例如: ods_order_detail"
              @keyup.enter="inspectExistingTable"
            />
          </el-form-item>
          <div class="tip-box">
            <el-icon><InfoFilled /></el-icon>
            <span>该表需已存在于当前 PostgreSQL 库中。系统会按现有字段结构识别并绑定；仅在识别到键值对场景时，才会额外使用 JSON 字段。</span>
          </div>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="existingTableDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="inspectExistingTable">识别并使用</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="pairConfirmDialogVisible"
      title="智能配对确认"
      width="600px"
      class="custom-dialog"
    >
      <div class="pair-confirm-body">
        <el-alert v-if="lastParseResult?.truncated" 
          :title="'由于列数超过上限 (1000)，仅识别了前 1000 列（共 ' + lastParseResult.totalColumns + ' 列），超出部分请手动添加。'"
          type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
        <p class="desc-text">我们在 Excel 中检测到以下潜在的“键值对”组合。合并后这些数据在导入时将自动归集到对应的 JSON 字段中：<code>{{ kvTargetColumnsText }}</code>。</p>
        
        <el-table :data="confirmingPairs" style="width: 100%">
          <el-table-column label="配对名称" prop="displayName" />
          <el-table-column label="包含后缀">
            <template #default="scope">
              <span>{{ formatSuffixSummary(scope.row.suffixes) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="确认合并" width="100" align="center">
            <template #default="scope">
              <el-checkbox v-model="scope.row.confirmed" />
            </template>
          </el-table-column>
        </el-table>
        
        <div class="tip-box">
          <el-icon><InfoFilled /></el-icon>
          <span>未被勾选的组合将作为“普通字段”平铺展开。</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="pairConfirmDialogVisible = false">返回修改</el-button>
        <el-button type="primary" @click="finalizeFields">确认解析结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, inject, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting, Notification, Grid, User, Plus, Upload, Delete, Check, Platform, UploadFilled, InfoFilled, Connection, Tickets, Document, ArrowRight } from '@element-plus/icons-vue'
import axios from 'axios'

const router = useRouter()
const route = useRoute()
const currentUser = inject('currentUser', ref('管理员'))

const isEditMode = ref(!!route.params.id)

const formMeta = reactive({
  name: '',
  schemaName: 'public',
  tableName: '',
  tableComment: '',
  folderId: '',
  status: 'ACTIVE',
  deadline: '',
  reminderDays: null, // 已废弃: 保留用于兼容旧数据，新表单不再使用
  reminderTime: '',
  recipientEmails: '',
  reminderMode: 'DEADLINE',
  monthlyDay: null,
  weeklyDayOfWeek: null,
  reminderDateTime: '',
  deadlineMonthlyDay: null,
  deadlineWeeklyDayOfWeek: null,
  deadlineTime: '',
  cycleDays: 0,
  fillUserEmails: '',
  kvConfig: '',
  referenceTemplateConfig: '',
  hardDelete: false,
  pkColumn: 'id',
  groupTag: '',
  description: '',
  defaultFilterPolicy: 'FIRST_THREE'
})

let _fieldUidCounter = 1
const fields = ref([
  { _uid: _fieldUidCounter++, name: '', columnName: '', originalColumnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false, systemLocked: false, pattern: '', patternMsg: '', min: null, max: null, minLength: null, maxLength: null }
])

const importDialogVisible = ref(false)
const referenceTemplateDialogVisible = ref(false)
const existingTableDialogVisible = ref(false)
const isInspectingExistingTable = ref(false)
const isParsingReferenceTemplate = ref(false)
const bindExistingTableMode = ref(false)
const existingTableForm = reactive({
  schemaName: 'public',
  tableName: ''
})
const importConfig = reactive({
  smartType: true,
  kvPairEnabled: true // 恢复启用智能键值对匹配
})
const isParsing = ref(false)
const pairConfirmDialogVisible = ref(false)
const confirmingPairs = ref([])
const lastParseResult = ref(null)
const lastFileName = ref('')
const headerMappings = ref([])
const referenceRows = ref([])
const referenceParserProfile = ref('')
const missingColumns = ref([])
const isRepairing = ref(false)

const displayMissingColumns = computed(() => {
  let list = [...missingColumns.value]
  // 如果当前指定的主键不是 'id'，且该主键确实存在于识别出的字段中，则不再提示缺失 id
  const pk = formMeta.pkColumn || 'id'
  if (list.includes('id') && pk !== 'id') {
    if (fields.value.some(f => f.columnName === pk)) {
      list = list.filter(c => c !== 'id')
    }
  }
  return list
})

const parsedKvConfig = computed(() => {
  try {
    return formMeta.kvConfig ? JSON.parse(formMeta.kvConfig) : []
  } catch (e) {
    return []
  }
})

const kvTargetColumns = computed(() => {
  const targets = (parsedKvConfig.value || [])
    .map(p => (p?.suggestedColumnName ? String(p.suggestedColumnName) : 'extra_data'))
    .filter(Boolean)
  // 去重：不区分大小写
  const seen = new Set()
  const result = []
  targets.forEach(t => {
    const key = String(t).toLowerCase()
    if (seen.has(key)) return
    seen.add(key)
    result.push(t)
  })
  return result
})

const kvTargetColumnsText = computed(() => {
  const targets = kvTargetColumns.value
  if (!targets || targets.length === 0) return '内置 JSON 扩展列'
  if (targets.length === 1) return targets[0]
  return targets.slice(0, 2).join(', ') + ` 等${targets.length}个`
})

// 数据库模式列表
const availableSchemas = ref([])
const schemaLoading = ref(false)

const loadSchemas = async () => {
  schemaLoading.value = true
  try {
    const res = await axios.get('/api/fill/schemas')
    availableSchemas.value = res.data || ['public']
    if (!availableSchemas.value.includes('public')) {
      availableSchemas.value.unshift('public')
    }
  } catch (e) {
    availableSchemas.value = ['public']
  } finally {
    schemaLoading.value = false
  }
}

// 用户列表（权限分配）
const allUsers = ref([])
const fillUserList = ref([])
const userListLoading = ref(false)

const loadUserList = async () => {
  userListLoading.value = true
  try {
    const res = await axios.get('/api/user/options')
    allUsers.value = res.data || []
  } catch (e) {
    allUsers.value = []
  } finally {
    userListLoading.value = false
  }
}

const recipientOptions = computed(() => {
  return allUsers.value.map(user => ({
    label: user.label || user.email || user.username,
    value: user.email || user.username
  }))
})

const fillUserOptions = computed(() => {
  return allUsers.value.map(user => ({
    label: user.label || user.email || user.username,
    value: user.username
  }))
})
const recipientList = ref([])
const folderTree = ref([])
const folderLoading = ref(false)
const folderOptions = computed(() => {
  const options = []
  const walk = (nodes, parents = []) => {
    ;(nodes || []).forEach(node => {
      if (node.systemNode) return
      const nextParents = [...parents, node.name]
      options.push({
        value: node.id,
        label: nextParents.join(' / ')
      })
      walk(node.children, nextParents)
    })
  }
  walk(folderTree.value)
  return options
})

const loadFolderTree = async () => {
  folderLoading.value = true
  try {
    const params = {}
    if (currentUser.value) params.userEmail = currentUser.value
    const res = await axios.get('/api/fill/folders/tree', { params })
    folderTree.value = res.data || []
  } catch (e) {
    folderTree.value = []
  } finally {
    folderLoading.value = false
  }
}

const syncReferenceTemplateConfig = () => {
  const payload = {
    parserProfile: referenceParserProfile.value || '',
    headerMappings: headerMappings.value || [],
    referenceRows: referenceRows.value || [],
    kvPairs: parsedKvConfig.value || []
  }
  formMeta.referenceTemplateConfig = JSON.stringify(payload)
}

const hydrateKvPairPreview = (kvPairs = [], originalHeaders = []) => {
  confirmingPairs.value = (kvPairs || []).map(pair => ({
    ...pair,
    confirmed: true,
    suffixes: (pair.suffixes || []).slice().sort((a, b) => parseInt(a || '0', 10) - parseInt(b || '0', 10))
  }))
  lastParseResult.value = {
    fields: fields.value,
    originalHeaders,
    truncated: false,
    totalColumns: originalHeaders.length
  }
}

const formatSuffixSummary = (suffixes = []) => {
  if (!suffixes || suffixes.length === 0) return '-'
  const nums = suffixes
    .map(s => Number.parseInt(s, 10))
    .filter(n => Number.isFinite(n))
    .sort((a, b) => a - b)
  if (nums.length === 0) return suffixes.join(', ')
  const uniqueNums = [...new Set(nums)]
  const start = uniqueNums[0]
  const end = uniqueNums[uniqueNums.length - 1]
  return start === end ? `${start}` : `${start}-${end}（共${uniqueNums.length}组）`
}

const addField = () => {
  fields.value.push({ _uid: _fieldUidCounter++, name: '', columnName: '', originalColumnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false, systemLocked: false, pattern: '', patternMsg: '', min: null, max: null, minLength: null, maxLength: null })
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
  
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('上传文件过大（超过 20MB），为了确保系统稳定性，请将数据分批进行识别或导入。')
    return
  }
  
  lastFileName.value = file.name
  const formData = new FormData()
  formData.append('file', file)
  formData.append('smartType', importConfig.smartType)
  formData.append('kvPairEnabled', importConfig.kvPairEnabled)

  isParsing.value = true
  try {
    const res = await axios.post('/api/fill/forms/parseExcel', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    bindExistingTableMode.value = false
    
    lastParseResult.value = res.data
    if (res.data.potentialPairs && res.data.potentialPairs.length > 0) {
      confirmingPairs.value = res.data.potentialPairs.map(p => ({ 
        ...p, 
        confirmed: true,
        suffixes: (p.suffixes || []).sort((a, b) => parseInt(a) - parseInt(b))
      }))
      pairConfirmDialogVisible.value = true
      importDialogVisible.value = false
    } else {
      applyParsedResults(res.data)
      importDialogVisible.value = false
    }
  } catch (e) {
    ElMessage.error('解析失败: ' + (e.response?.data?.message || '网络异常'))
  } finally {
    isParsing.value = false
  }
}

const onReferenceTemplateFileChange = async (uploadFile) => {
  if (!uploadFile.raw) return
  const file = uploadFile.raw
  
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('上传文件过大（超过 20MB），为了确保系统稳定性，请将数据分批进行参考或识别。')
    return
  }
  
  lastFileName.value = file.name
  const formData = new FormData()
  formData.append('file', file)

  isParsingReferenceTemplate.value = true
  try {
    const res = await axios.post('/api/fill/forms/parseReferenceTemplate', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    bindExistingTableMode.value = false
    formMeta.tableName = res.data.tableName || ''
    formMeta.tableComment = res.data.tableComment || ''
    formMeta.name = res.data.tableComment || res.data.tableName || lastFileName.value.replace(/\.[^/.]+$/, '')
    formMeta.kvConfig = JSON.stringify(res.data.kvPairs || [])
    headerMappings.value = res.data.headerMappings || []
    referenceRows.value = res.data.referenceRows || []
    referenceParserProfile.value = res.data.parserProfile || ''
    applyParsedResults({ fields: res.data.fields || [] })
    const kvPairs = res.data.kvPairs || []
    const originalHeaders = headerMappings.value.map(item => {
      const h = (item.excelHeader || '').trim()
      if (!h || h === '...') return item.columnName || ''
      return item.excelHeader
    })
    hydrateKvPairPreview(kvPairs, originalHeaders)
    if (kvPairs.length > 0) {
      pairConfirmDialogVisible.value = true
    }
    syncReferenceTemplateConfig()
    referenceTemplateDialogVisible.value = false
    ElMessage.success('参考模板已解析并回填，可直接发布建表')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '参考模板解析失败')
  } finally {
    isParsingReferenceTemplate.value = false
  }
}

const inspectExistingTable = async () => {
  if (!existingTableForm.tableName) {
    ElMessage.warning('请输入物理表名')
    return
  }
  isInspectingExistingTable.value = true
  try {
    const res = await axios.get('/api/fill/inspectTable', {
      params: { 
        tableName: existingTableForm.tableName,
        schemaName: existingTableForm.schemaName
      }
    })
    
    // 后端返回的是 ExcelParseResult 对象，其 fields 属性才是字段数组
    if (res.data && res.data.fields && res.data.fields.length > 0) {
      fields.value = res.data.fields.map(f => ({
        _uid: _fieldUidCounter++,
        ...f,
        required: f.required || false,
        filterable: f.filterable || false,
        systemLocked: isSystemManagedField(f)
      }))
      
      formMeta.tableName = existingTableForm.tableName
      formMeta.schemaName = existingTableForm.schemaName
      bindExistingTableMode.value = true
      existingTableDialogVisible.value = false
      missingColumns.value = res.data.missingColumns || []
      
      // 如果识别到的表中原来就有主键（后端返回或已存在于 fields），则自动选中
      if (res.data.pkColumn) {
          formMeta.pkColumn = res.data.pkColumn
      } else if (fields.value.some(f => f.columnName === 'id')) {
          formMeta.pkColumn = 'id'
      }

      if (missingColumns.value.length > 0) {
        ElMessageBox.alert(
          `已识别表结构，但检测到物理表缺少审计列：[${missingColumns.value.join(', ')}]。建议先补齐审计列，否则删除和更新功能将受限。`,
          '配置风险提示',
          { type: 'warning', confirmButtonText: '知道了' }
        )
      } else {
        ElMessage.success('已识别已有表结构，发布时将直接绑定该表')
      }
    } else {
      ElMessage.warning('未识别到该表的业务字段')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '读取已有表结构失败')
  } finally {
    isInspectingExistingTable.value = false
  }
}

const repairTableColumns = async () => {
  const isBindingNew = !route.params.id && bindExistingTableMode.value
  if (!route.params.id && !isBindingNew) {
    ElMessage.warning('请先成功保存模板后再执行补齐操作。')
    return
  }
  
  const targetCols = displayMissingColumns.value
  if (targetCols.length === 0) {
    ElMessage.success('字段均已齐备，无需补齐')
    return
  }

  try {
    isRepairing.value = true
    let url = ''
    if (route.params.id) {
        url = `/api/fill/forms/${route.params.id}/repairTable?userEmail=${currentUser.value}`
    } else {
        url = `/api/fill/forms/repairTableByName?schemaName=${formMeta.schemaName}&tableName=${formMeta.tableName}&userEmail=${currentUser.value}`
    }
    
    const res = await axios.post(url, targetCols)
    if (res.data.success && res.data.success.length > 0) {
      ElMessage.success(`成功补齐字段: ${res.data.success.join(', ')}`)
      missingColumns.value = missingColumns.value.filter(c => !res.data.success.includes(c))
    }
    if (res.data.failed && res.data.failed.length > 0) {
      ElMessage.error(`部分字段补齐失败: ${res.data.failed.join(', ')}`)
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  } finally {
    isRepairing.value = false
  }
}

const finalizeFields = () => {
  if (!lastParseResult.value) return
  
  const activePairs = confirmingPairs.value.filter(p => p.confirmed)
  const rejectedPairs = confirmingPairs.value.filter(p => !p.confirmed)
  // 必须从解析出的基础字段开始，而不是从 fields.value (因为这时候 fields.value 可能还没更新)
  const finalFields = lastParseResult.value.fields ? [...lastParseResult.value.fields] : []
  
  // 1. 处理拒绝的配对：将被排除的原始列还原成标准字段
  rejectedPairs.forEach(p => {
    const allIdx = [...(p.keyIndices || []), ...(p.valueIndices || [])]
    allIdx.forEach(idx => {
      const headerName = lastParseResult.value.originalHeaders[idx]
      const colName = generateColumnName(headerName)
      if (headerName && !finalFields.some(f => f.columnName === colName)) {
        finalFields.push({
          name: headerName,
          columnName: colName,
          type: 'input',
          dbType: 'VARCHAR(255)',
          required: false,
          filterable: false
        })
      }
    })
  })

  // 2. 处理确认的配对：添加虚拟 JSON 字段
  activePairs.forEach(p => {
    const colName = p.suggestedColumnName || 'extra_data'
    // 允许通过显示名区分，或者是唯一的列名
    if (!finalFields.some(f => f.columnName === colName)) {
      finalFields.push({
        name: `扩展项 (${p.displayName})`,
        columnName: colName,
        type: 'input',
        dbType: 'JSONB',
        required: false,
        filterable: false,
        id_mark: true // 标记为虚拟/既有
      })
    }
  })

  formMeta.kvConfig = JSON.stringify(activePairs)
  applyParsedResults({ fields: finalFields })
  pairConfirmDialogVisible.value = false
  syncReferenceTemplateConfig()
}

const isSystemManagedField = (field) => {
  const reserved = ['id', 'delete_flag', 'w_insert_dt', 'w_update_dt', 'load_user', 'job_instance', 'extra_data']
  return reserved.includes((field?.columnName || '').toLowerCase())
}

const generateColumnName = (label) => {
  if (!label) return ''
  let name = label.toLowerCase().trim()
  
  // 特殊业务术语规范：去除 /Territory
  name = name.replace(/\/territory/g, '')
  
  // 规范：去除 #
  name = name.replace(/#/g, '')
  
  // 规范：处理 / (取前半部分，除非是 Country/Code 这种已处理的情况)
  // 但为了保留 Express or Ground 这种，我们先转空格
  name = name.replace(/\//g, ' ')
  
  // 规范：替换非字母数字为下划线
  name = name.replace(/[^a-z0-9_]/g, '_')
  
  // 规范：合并连续下划线
  name = name.replace(/__+/g, '_')
  
  // 规范：首尾清理
  name = name.replace(/^_+|_+$/g, '')
  
  return name
}

const applyParsedResults = (data) => {
  if (!data || !data.fields) return
  
  if (data.truncated) {
    ElMessage.warning(`注意：该 Excel 包含 ${data.totalColumns} 列，系统仅识别了前 1000 列。`)
  }
  
  fields.value = data.fields.map(f => {
    const row = {
      _uid: _fieldUidCounter++,
      ...f,
      columnName: f.columnName || '',
      originalColumnName: f.originalColumnName || '',
      type: f.type || 'input',
      dbType: f.dbType || 'VARCHAR(255)',
      required: f.required || false,
      filterable: f.filterable || false,
      systemLocked: isSystemManagedField(f)
    }
    if (!row.columnName) {
        row.columnName = generateColumnName(f.name)
    }
    handleDbTypeChange(row.dbType, row)
    return row
  })
    
  ElMessage.success(`成功识别出 ${fields.value.length} 个字段。`)
  if (!formMeta.name && lastFileName.value) {
    formMeta.name = lastFileName.value.replace(/\.[^/.]+$/, "")
  }
}

const validateForm = () => {
  if (!formMeta.name || !formMeta.tableName) {
    ElMessage.error('名称和物理表名必填')
    return false
  }
  // 物理表名正则校验 (仅允许小写字母、数字、下划线，必须字母开头)
  if (!/^[a-z][a-z0-9_]*$/.test(formMeta.tableName)) {
    ElMessage.error('物理表名格式不正确（仅允许小写字母、数字、下划线，且必须以字母开头）')
    return false
  }

  for (let i = 0; i < fields.value.length; i++) {
    const f = fields.value[i]
    if (!f.name || !f.columnName) {
      ElMessage.error(`第 ${i + 1} 个字段的名称和物理列名必填`)
      return false
    }
    if (!/^[a-z][a-z0-9_]*$/.test(f.columnName)) {
      ElMessage.error(`字段 "${f.name}" 的物理列名 "${f.columnName}" 格式不计（仅允许小写字母、数字、下划线，且必须以字母开头）`)
      return false
    }
  }
  return true
}

const submitFormAndCreateTable = async () => {
  if (!validateForm()) return

  // 1. 无论是新建还是绑定模式，都先检查物理表冲突情况
  try {
    const checkRes = await axios.get('/api/fill/checkTable', {
      params: { 
        schemaName: formMeta.schemaName || 'public', 
        tableName: formMeta.tableName 
      }
    })
    
    // 如果该物理表（Schema + Table Name）已经在系统中被其他模板占用了，显示强冲突错误
    if (checkRes.data.metaExists) {
      const conflictName = checkRes.data.conflictTemplateName || '其他模板'
      ElMessage.error(`物理表 [${formMeta.schemaName}.${formMeta.tableName}] 已被系统中的模板「${conflictName}」占用，请重命名表名或更换模式(Schema)！`)
      return
    }

    // [拦截逻辑] 如果是绑定已有表，强制检查审计列
    const currentMissing = checkRes.data.missingColumns || []
    if (checkRes.data.physicalExists && (bindExistingTableMode.value || currentMissing.length > 0)) {
        // 更新全局状态以便列表展示
        missingColumns.value = currentMissing
        
        // 核心拦截：如果缺少主键，或者设置了软删除但缺少 delete_flag
        const pk = formMeta.pkColumn || 'id'
        const isPkMissing = currentMissing.includes(pk) && !fields.value.some(f => f.columnName === pk)
        
        // 注意：即使 currentMissing 包含 id，但如果 pk 指向了另一个已存在的业务列，则不认为 NeedsId
        const needsId = (pk === 'id') ? currentMissing.includes('id') : isPkMissing
        const missingDeleteFlag = currentMissing.includes('delete_flag') && !formMeta.hardDelete
        
        if (needsId || missingDeleteFlag) {
            const msg = needsId ? `物理表中缺少您指定的主键列 "${pk}"` : '物理表缺少 "delete_flag" 状态位'
            await ElMessageBox.confirm(
                `${msg}，直接发布将导致删除或更新功能不可用。建议先在字段列表上方点击「一键补齐」，是否仍要强行发布？`,
                '发布拦截警告',
                { confirmButtonText: '强行发布 (不建议)', cancelButtonText: '返回修改', type: 'error' }
            )
        }
    }

    // 只有在非绑定模式下（即用户选择了“由系统创建新表”），如果发现物理表已存在，才弹出“转为绑定”的选择框
    if (!bindExistingTableMode.value && checkRes.data.physicalExists) {
      try {
        await ElMessageBox.confirm(
          `检测到数据库中已存在物理表 "${formMeta.schemaName}.${formMeta.tableName}"。是否直接绑定您已存在的这个表，直接使用已有的表而不创建新表了？`,
          '检测到已有物理表',
          {
            confirmButtonText: '确认绑定并使用',
            cancelButtonText: '取消',
            type: 'warning'
          }
        )
        // 用户确认，标记为绑定模式
        bindExistingTableMode.value = true
      } catch (e) {
        // 用户取消，中断发布
        return
      }
    }
  } catch (e) {
    console.warn('校验物理表状态失败', e)
    // 校验接口报错不阻断后续发布，交由后端最终校验兜底
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
    forms: JSON.stringify(formattedFields),
    folderId: formMeta.folderId || null,
    kvConfig: formMeta.kvConfig,
    pkColumn: formMeta.pkColumn || 'id',
    referenceTemplateConfig: formMeta.referenceTemplateConfig || null,
    creator: currentUser.value
  }

  try {
    const url = bindExistingTableMode.value ? '/api/fill/forms/bindExistingTable' : '/api/fill/forms/createTable'
    await axios.post(url, payload, { params: { userEmail: currentUser.value } })
    ElMessage.success(bindExistingTableMode.value ? '绑定发布成功！' : '发布成功！')
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
    if (!formMeta.schemaName) {
        formMeta.schemaName = 'public'
    }
    if (res.data.recipientEmails) {
        try { recipientList.value = JSON.parse(res.data.recipientEmails) } catch (e) { recipientList.value = [] }
    }
    if (res.data.fillUserEmails) {
        try { fillUserList.value = JSON.parse(res.data.fillUserEmails) } catch (e) { fillUserList.value = [] }
    }
    if (res.data.forms) {
        const parsed = JSON.parse(res.data.forms)
        fields.value = parsed.map(f => ({
          _uid: _fieldUidCounter++,
          ...f,
          originalColumnName: f.columnName || '',
          systemLocked: isSystemManagedField(f)
        }))
    }
    if (res.data.kvConfig) {
      try {
        hydrateKvPairPreview(JSON.parse(res.data.kvConfig), [])
      } catch (e) {
        confirmingPairs.value = []
      }
    }
    if (res.data.referenceTemplateConfig) {
      try {
        const parsedConfig = JSON.parse(res.data.referenceTemplateConfig)
        headerMappings.value = parsedConfig.headerMappings || []
        referenceRows.value = parsedConfig.referenceRows || []
        referenceParserProfile.value = parsedConfig.parserProfile || ''
        if (parsedConfig.kvPairs) {
          const originalHeaders = headerMappings.value.map(item => {
            const h = (item.excelHeader || '').trim()
            if (!h || h === '...') return item.columnName || ''
            return item.excelHeader
          })
          hydrateKvPairPreview(parsedConfig.kvPairs, originalHeaders)
        }
      } catch (e) {
        headerMappings.value = []
        referenceRows.value = []
        referenceParserProfile.value = ''
      }
    }
  } catch (e) {
    ElMessage.error('加载任务配置失败')
  }
}

const updateFormMeta = async () => {
  if (!validateForm()) return
  const id = route.params.id
  const payload = {
    ...formMeta,
    recipientEmails: recipientList.value.length > 0 ? JSON.stringify(recipientList.value) : null,
    fillUserEmails: fillUserList.value.length > 0 ? JSON.stringify(fillUserList.value) : null,
    folderId: formMeta.folderId || null,
    forms: JSON.stringify(fields.value.map(f => {
      const { id_mark, systemLocked, ...rest } = f
      return rest
    })),
    kvConfig: formMeta.kvConfig,
    pkColumn: formMeta.pkColumn || 'id',
    referenceTemplateConfig: formMeta.referenceTemplateConfig || null,
    creator: formMeta.creator || currentUser.value
  }
  try {
    await axios.put(`/api/fill/forms/${id}`, payload, { params: { userEmail: formMeta.creator || currentUser.value } })
    ElMessage.success('修改成功')
    router.push('/forms')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  }
}

onMounted(() => {
  loadSchemas()
  loadUserList()
  loadFolderTree()
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
  padding: 24px 0;
  background: white;
  z-index: 100;
  transition: all 0.3s;
}

.sticky-header {
  position: sticky;
  top: 0;
  padding: 16px 0;
  border-bottom: 1px solid #f1f5f9;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
}

.header-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.header-breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #94a3b8;
  margin-bottom: 4px;
}

.breadcrumb-item {
  cursor: pointer;
  transition: color 0.2s;
}

.breadcrumb-item:hover {
  color: var(--primary-color);
}

.breadcrumb-current {
  color: #64748b;
  font-weight: 500;
}

.btn-cancel {
  border: 1px solid #e2e8f0;
  color: #64748b;
}

.btn-cancel:hover {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #1e293b;
}

.page-title {
  font-size: 28px;
  font-weight: 900;
  color: #1e293b;
  margin: 0;
  letter-spacing: -0.5px;
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

.kv-preview-banner {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  font-size: 14px;
  color: #475569;
}

.preview-section {
  margin-top: 24px;
}

.preview-title {
  margin-bottom: 12px;
}

.preview-meta {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 10px;
}

.kv-info-text {
  flex: 1;
}

.kv-info-text b {
  color: var(--primary-color);
}

.pair-confirm-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.desc-text {
  font-size: 14px;
  color: #475569;
  line-height: 1.6;
}

.tip-box {
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #0369a1;
  font-size: 13px;
}
</style>
