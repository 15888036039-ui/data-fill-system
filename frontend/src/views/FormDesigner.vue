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
        <el-button v-if="!isEditMode" type="primary" icon="Platform" @click="submitFormAndCreateTable">{{ bindExistingTableMode ? '确认绑定并发布' : '创建并发布模板' }}</el-button>
        <el-button v-else type="primary" icon="Check" @click="updateFormMeta">完成并保存</el-button>
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
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="数据库物理模式 (Schema)" required>
                  <el-select
                    v-model="formMeta.schemaName"
                    filterable
                    placeholder="选择模式"
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
              </el-col>
              <el-col :span="12">
                <el-form-item label="数据库物理表名" required>
                  <el-input
                    v-model="formMeta.tableName"
                    placeholder="请输入物理表名"
                    :disabled="isEditMode"
                  />
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="数据库表注释">
                  <el-input
                    v-model="formMeta.tableComment"
                    placeholder="请输入物理表注释"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="所属目录">
                  <el-select
                    v-model="formMeta.folderId"
                    clearable
                    filterable
                    placeholder="归入目录"
                    style="width: 100%"
                    :loading="folderLoading"
                  >
                    <el-option label="默认" value="" />
                    <el-option
                      v-for="item in folderOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="分组标识 (Group Tag)">
                  <el-input
                    v-model="formMeta.groupTag"
                    placeholder="用于页面过滤"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="默认筛选器策略" required>
                  <el-select v-model="formMeta.defaultFilterPolicy" style="width: 100%">
                    <el-option label="不展示 (NONE)" value="NONE" />
                    <el-option label="前三个字段 (FIRST_THREE)" value="FIRST_THREE" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
            <div style="font-size: 12px; color: #94a3b8; margin-top: -8px; margin-bottom: 16px;">策略说明：当未指定筛选字段时，系统将采取此兜底逻辑。</div>
            
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="运营状态">
                  <el-select v-model="formMeta.status" style="width: 100%">
                    <el-option label="运行中" value="ACTIVE" />
                    <el-option label="已过期" value="EXPIRED" />
                    <el-option label="停用" value="DISABLED" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="用户数据删除方式" required>
                  <el-select v-model="formMeta.hardDelete" style="width: 100%">
                    <el-option label="软删除 (标记)" :value="false" />
                    <el-option label="硬删除 (移除)" :value="true" />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>

            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="允许普通用户新增">
                   <el-select v-model="formMeta.allowAdd" style="width: 100%">
                     <el-option label="允许" :value="true" />
                     <el-option label="禁止" :value="false" />
                   </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="允许普通用户修改">
                   <el-select v-model="formMeta.allowEdit" style="width: 100%">
                     <el-option label="允许" :value="true" />
                     <el-option label="禁止" :value="false" />
                   </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="允许普通用户删除">
                   <el-select v-model="formMeta.allowDelete" style="width: 100%">
                     <el-option label="允许" :value="true" />
                     <el-option label="禁止" :value="false" />
                   </el-select>
                </el-form-item>
              </el-col>
            </el-row>

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
              <!-- <el-button icon="Upload" plain @click="importDialogVisible = true" v-if="!isEditMode">从 Excel 导入结构</el-button> -->
              <el-button icon="Tickets" plain @click="referenceTemplateDialogVisible = true" v-if="!isEditMode">智能识别 Excel 模版</el-button>
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
            v-if="isSimpleRename"
            title="检测到字段名变更"
            type="info"
            show-icon
            style="margin-bottom: 24px;"
            :closable="false"
          >
            <template #default>
              <div style="display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;">
                <div style="display: flex; align-items: center; gap: 8px;">
                  <span>检测到物理表字段 <code>{{ missingBusinessColumns[0] }}</code> 已更名为 <code>{{ untrackedBusinessColumns[0] }}</code>，系统可为您一键修复绑定。</span>
                </div>
                <el-button type="primary" size="small" @click="handleAutoRenameFix" :loading="isSyncingColumns" icon="Refresh">一键修复重命名</el-button>
              </div>
            </template>
          </el-alert>

          <el-alert
            v-if="untrackedBusinessColumns.length > 0 && !isSimpleRename"
            title="检测到物理表中存在未注册的列。系统可将其识别为新字段或与下方丢失字段重新绑定。"
            type="warning"
            show-icon
            style="margin-bottom: 24px;"
            :closable="false"
          >
            <template #default>
              <div v-for="c in untrackedBusinessColumns" :key="c" style="margin-bottom: 8px; display: flex; align-items: center; gap: 12px;">
                <el-tag size="small" type="warning">{{ c }}</el-tag>
                <el-icon><Right /></el-icon>
                <el-select v-model="untrackedMapping[c]" placeholder="作为新字段追加" size="small" style="width: 220px" clearable>
                  <el-option label="作为新字段追加" value="__NEW__" />
                  <el-option v-for="m in missingBusinessColumns" :key="m" :label="'替换/映射到丢失列: ' + m" :value="m" />
                </el-select>
              </div>
              <el-button type="warning" size="small" @click="syncUntrackedColumns" icon="Refresh" :loading="isSyncingColumns" style="margin-top: 4px">执行同步/绑定</el-button>
            </template>
          </el-alert>

          <el-alert
            v-if="missingBusinessColumns.length > 0 && !isSimpleRename"
            title="检测到配置中的部分业务字段在物理表中已丢失。这会导致填报提交失败！"
            type="error"
            show-icon
            style="margin-bottom: 24px;"
            :closable="false"
          >
            <template #default>
              <div style="margin-bottom: 8px;">
                丢失列：<el-tag size="small" type="danger" v-for="c in missingBusinessColumns" :key="c" style="margin-right: 4px">{{ c }}</el-tag>
              </div>
              <div style="display: flex; gap: 12px;">
                <el-button type="primary" size="small" @click="repairMissingBusinessColumns" :loading="isRepairing">在数据库中重建丢失列</el-button>
                <el-button type="danger" plain size="small" @click="removeMissingBusinessColumns" icon="Close">从配置中移除丢失列</el-button>
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
             <el-table :data="fields" style="width: 100%" row-key="_uid" :row-class-name="tableRowClassName">
                <el-table-column width="40" align="center">
                  <template #default="scope">
                    <el-tooltip :content="scope.row.systemLocked ? '系统审计字段位置固定' : '按住拖拽排序'" placement="top">
                      <span>
                        <el-icon v-if="!scope.row.systemLocked" class="drag-handle" style="cursor: move; color: #94a3b8;"><Sort /></el-icon>
                        <el-icon v-else style="color: #cbd5e1;"><Lock /></el-icon>
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
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
                        
                        <div style="border: 1px solid #e2e8f0; padding: 16px; border-radius: 8px; margin-top: 16px; margin-bottom: 16px; background-color: #ffffff;">
                          <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 12px;">
                            <span style="font-weight: 600; font-size: 13px; color: #1e293b; display: flex; align-items: center;">
                              进阶：自定义 SQL 校验 (维度表关联) 
                              <el-tooltip content="用于关联维度表进行校验，占位符为 :val" placement="top">
                                <el-icon style="margin-left: 4px; color: #3b82f6;"><InfoFilled /></el-icon>
                              </el-tooltip>
                            </span>
                            <el-input v-model="props.row._testSqlValue" placeholder="测试值" size="small" style="width: 150px;" />
                            <el-button type="primary" size="small" @click="testSql(props.row)" :loading="props.row._testingSql">点击测试 SQL</el-button>
                          </div>
                          <el-input 
                            v-model="props.row.validationSql" 
                            type="textarea" 
                            :rows="3" 
                            placeholder="例: SELECT distinct third_sort_desc FROM dim.dim_product WHERE third_sort_desc = :val" 
                            style="font-family: monospace;"
                          />
                        </div>
                        <el-form-item label="维度校验失败提示语">
                          <el-input v-model="props.row.validationSqlMsg" placeholder="例如: 数据错误，未在维度表中找到该值" />
                        </el-form-item>

                        <div style="border: 1px solid #e2e8f0; padding: 16px; border-radius: 8px; margin-top: 16px; margin-bottom: 8px; background-color: #ffffff;">
                          <div style="font-weight: 600; font-size: 13px; color: #1e293b; margin-bottom: 12px; display: flex; align-items: center;">
                            填报可见权限控制
                            <el-tooltip content="配置允许填报/查看此字段的用户邮箱列表。若不配置，则所有人均可填报/查看。" placement="top">
                              <el-icon style="margin-left: 4px; color: #3b82f6;"><InfoFilled /></el-icon>
                            </el-tooltip>
                          </div>
                          <el-form-item label="可见用户邮箱">
                            <el-select
                              v-model="props.row.visibleEmails"
                              multiple
                              filterable
                              allow-create
                              default-first-option
                              placeholder="请选择或输入邮箱地址"
                              style="width: 100%"
                            >
                              <el-option
                                v-for="item in recipientOptions"
                                :key="item.value"
                                :label="item.label"
                                :value="item.value"
                              />
                            </el-select>
                          </el-form-item>
                        </div>
                      </el-form>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="中文显示名" min-width="180">
                  <template #default="scope">
                    <div style="display: flex; align-items: center; gap: 8px;">
                      <el-input v-model="scope.row.name" placeholder="字段标题" />
                      <el-tag v-if="(scope.row.columnName || '').toLowerCase() === (formMeta.pkColumn || 'id').toLowerCase()" size="small" type="warning" effect="dark" style="flex-shrink: 0; background-color: #f59e0b; border-color: #d97706; color: #ffffff; font-weight: bold;">主键</el-tag>
                      <el-tag v-else-if="scope.row.systemLocked" size="small" type="info" effect="plain" style="flex-shrink: 0;">系统</el-tag>
                    </div>
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
                <el-table-column label="必填" width="80" align="center">
                  <template #default="scope">
                    <el-switch v-model="scope.row.required" />
                  </template>
                </el-table-column>
                <el-table-column label="填报" width="80" align="center">
                  <template #header>
                    <el-tooltip content="开启后，该字段将出现在 Excel 模板和单行填报表单中" placement="top">
                      <span>填报 <el-icon><InfoFilled /></el-icon></span>
                    </el-tooltip>
                  </template>
                  <template #default="scope">
                    <el-switch 
                      v-model="scope.row.hideInForm" 
                      :active-value="false" 
                      :inactive-value="true"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="列表" width="80" align="center">
                  <template #header>
                    <el-tooltip content="开启后，该字段将出现在查询结果展示列表中" placement="top">
                      <span>查阅 <el-icon><InfoFilled /></el-icon></span>
                    </el-tooltip>
                  </template>
                  <template #default="scope">
                    <el-switch 
                      v-model="scope.row.hideInList" 
                      :active-value="false" 
                      :inactive-value="true"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="筛选" width="100" align="center">
                  <template #default="scope">
                    <div style="display: flex; flex-direction: column; align-items: center; gap: 4px;">
                      <el-switch v-model="scope.row.filterable" />
                      <el-popover
                        v-if="scope.row.filterable"
                        placement="left"
                        title="筛选器配置"
                        :width="420"
                        trigger="click"
                      >
                        <template #reference>
                          <el-button 
                            type="primary" 
                            link 
                            size="small" 
                            icon="Setting"
                            style="margin-top: 2px;"
                          >配置</el-button>
                        </template>
                        <div style="display: flex; gap: 16px; padding: 4px 0; align-items: flex-start;">
                          <div style="flex: 1; min-width: 120px;">
                            <span style="font-size: 13px; color: #64748b; font-weight: 500; display: block; margin-bottom: 6px;">筛选控件类型</span>
                            <el-select v-model="scope.row.filterType" size="small" style="width: 100%;" :teleported="false">
                              <el-option label="输入框" value="input" />
                              <el-option label="下拉选择" value="select" />
                              <el-option label="日期范围" value="daterange" />
                              <el-option label="月份范围" value="monthrange" />
                            </el-select>
                          </div>
                          <div v-if="scope.row.filterType === 'select'" style="flex: 2;">
                            <span style="font-size: 13px; color: #64748b; font-weight: 500; display: block; margin-bottom: 6px;">选项来源</span>
                            <el-radio-group v-model="scope.row._filterSource" size="small" style="margin-bottom: 8px;" @change="(val) => { if(val==='manual') scope.row.filterOptionsSql = ''; else scope.row.filterOptions = []; }">
                              <el-radio-button label="manual">手动输入/表单去重</el-radio-button>
                              <el-radio-button label="sql">SQL 维度查询</el-radio-button>
                            </el-radio-group>
                            
                            <div v-if="scope.row._filterSource !== 'sql'">
                              <el-input 
                                :model-value="Array.isArray(scope.row.filterOptions) ? scope.row.filterOptions.join(',') : (scope.row.filterOptions || '')"
                                @update:model-value="(val) => { scope.row.filterOptions = val ? val.split(',').map(s => s.trim()).filter(Boolean) : [] }"
                                placeholder="例如: A,B,C" 
                                size="small" 
                                type="textarea"
                                :rows="2"
                              />
                              <span style="font-size: 11px; color: #94a3b8; margin-top: 6px; display: block; line-height: 1.4;">
                                💡 如果为空则遍历表单数据后去重
                              </span>
                            </div>
                            
                            <div v-else>
                              <el-input 
                                v-model="scope.row.filterOptionsSql"
                                placeholder="例: SELECT DISTINCT category FROM dim.dim_product ORDER BY category" 
                                size="small" 
                                type="textarea"
                                :rows="3"
                                style="font-family: monospace;"
                              />
                              <div style="margin-top: 6px; display: flex; justify-content: space-between; align-items: center;">
                                <span style="font-size: 11px; color: #94a3b8; line-height: 1.4;">
                                  💡 仅支持 SELECT 语句，取结果集第一列
                                </span>
                                <el-button type="primary" link size="small" @click="testFilterOptionsSql(scope.row)" :loading="scope.row._testingFilterSql">测试 SQL</el-button>
                              </div>
                            </div>
                          </div>
                        </div>
                      </el-popover>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="80" align="center">
                  <template #default="scope">
                    <el-tooltip :content="scope.row.systemLocked ? '系统锁定字段不可删除' : '删除字段'" placement="top">
                      <span>
                        <el-button 
                          type="danger" 
                          icon="Delete" 
                          circle 
                          plain 
                          @click="removeField(scope.$index)" 
                          :disabled="scope.row.systemLocked" 
                        />
                      </span>
                    </el-tooltip>
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
                placeholder="选择或搜索用户"
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
            </el-form-item>
            <el-form-item label="授权部门列表">
              <el-select
                v-model="fillDepartmentList"
                multiple
                filterable
                default-first-option
                :reserve-keyword="false"
                placeholder="选择授权访问的部门"
                style="width: 100%"
                :loading="departmentListLoading"
              >
                <el-option
                  v-for="d in allDepartments"
                  :key="d.value"
                  :label="d.label"
                  :value="d.value"
                />
              </el-select>
              <div style="font-size: 12px; color: #94a3b8; margin-top: 4px;">若用户和部门都不选择，则只有管理员可以查看和填报</div>
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
      title="智能识别 Excel 模版"
      width="560px"
      destroy-on-close
      class="custom-dialog"
    >
      <div class="import-config-body" v-loading="isParsingReferenceTemplate" element-loading-text="正在智能识别 Excel 模版...">
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
      v-model="pkConflictDialogVisible"
      :title="pkConflictInfo.hasExistingId ? '🛠️ 已有物理表 id 字段冲突确认与选择' : '🛠️ 物理表主键绑定与系统审计列补齐选择'"
      width="620px"
      :close-on-click-modal="false"
      class="custom-dialog pk-conflict-dialog"
    >
      <div style="padding: 10px 0;">
        <!-- 情况一：物理表已存在 id 列（冲突） -->
        <div v-if="pkConflictInfo.hasExistingId" style="background-color: #fffaf0; border: 1px solid #ffe3b3; border-radius: 8px; padding: 14px; margin-bottom: 20px; display: flex; align-items: flex-start; gap: 12px;">
          <el-icon style="font-size: 24px; color: #e6a23c; flex-shrink: 0; margin-top: 2px;"><WarningFilled /></el-icon>
          <div>
            <div style="font-size: 14px; font-weight: bold; color: #c27d1a; margin-bottom: 6px;">检测到物理表已存在 id 字段</div>
            <div style="font-size: 13px; color: #666; line-height: 1.5;">
              系统默认会在您的物理表中自动创建和管理标准的自增 <code>id</code> 主键列。为了保证这一机制最佳运行，如果您决定使用系统分配的标准 <code>id</code> 列，<b>请先去数据库中删除该 id 列</b>！
            </div>
          </div>
        </div>

        <!-- 情况二：物理表缺少 id 审计列（缺失） -->
        <div v-else style="background-color: #fef2f2; border: 1px solid #fecaca; border-radius: 8px; padding: 14px; margin-bottom: 20px; display: flex; align-items: flex-start; gap: 12px;">
          <el-icon style="font-size: 24px; color: #ef4444; flex-shrink: 0; margin-top: 2px;"><CircleCloseFilled /></el-icon>
          <div>
            <div style="font-size: 14px; font-weight: bold; color: #b91c1c; margin-bottom: 6px;">
              {{ pkConflictInfo.isCompositePrimaryKey ? '检测到物理表为主键配置受限' : '检测到物理表缺少系统必要的审计字段 [id]' }}
            </div>
            <div style="font-size: 13px; color: #666; line-height: 1.5;">
              <span v-if="pkConflictInfo.isCompositePrimaryKey">
                检测到该物理表已在数据库中设定为联合主键 <strong>[{{ pkConflictInfo.detectedPrimaryKey }}]</strong>。由于数据更新和删除需要单行精准定位，系统不支持直接绑定。请选择下方一键自动补齐标准的自增 <code>id</code> 列。<br/>
              </span>
              <span v-else>
                <span v-if="pkConflictInfo.detectedPrimaryKey">
                  已成功识别物理表结构！检测到该物理表在数据库中已设定主键字段为：<strong>[{{ pkConflictInfo.detectedPrimaryKey }}]</strong>。<br/>
                </span>
                为了确保表单数据在进行更新、删除、填报记录时正常运作，强烈建议您在物理表中补齐标准的自增 <code>id</code> 主键列！
              </span>
            </div>
          </div>
        </div>

        <div style="font-size: 14px; font-weight: bold; color: #334155; margin-bottom: 12px;">
          {{ pkConflictInfo.hasExistingId ? '如果您不想去数据库删除该 id 列，系统支持以下绑定方式，请选择：' : '请选择您的主键绑定与补齐方式：' }}
        </div>
        
        <div class="choice-cards-container">
          <!-- 情况一：缺少 id 时的两个最精简选项 -->
          <template v-if="!pkConflictInfo.hasExistingId">
            <!-- 选项 1：一键自动补齐 (推荐!) -->
            <div 
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'auto_repair_id' }"
              @click="pkConflictSelectedOption = 'auto_repair_id'"
            >
              <div class="choice-card-header">
                <span class="choice-title">⚡ 一键自动补齐标准的自增 id 主键 (推荐)</span>
                <el-tag size="small" type="success" effect="dark">极速补齐</el-tag>
              </div>
              <div class="choice-description">
                系统将自动在您的数据库物理表中添加自增 <code>id</code> 列并设为表单主键，完美支持所有功能。
              </div>
            </div>

            <!-- 选项 2：有物理主键时绑定物理主键，无物理主键时选择暂不处理 -->
            <div 
              v-if="pkConflictInfo.detectedPrimaryKey && !pkConflictInfo.isCompositePrimaryKey"
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'detected_pk' }"
              @click="pkConflictSelectedOption = 'detected_pk'"
            >
              <div class="choice-card-header">
                <span class="choice-title">💎 直接绑定物理主键 [{{ pkConflictInfo.detectedPrimaryKey }}]</span>
                <el-tag size="small" type="success" effect="dark">当前主键</el-tag>
              </div>
              <div class="choice-description">
                直接绑定使用物理表中已设定的主键作为表单主键，系统将暂不补齐 <code>id</code> 列。
              </div>
            </div>

            <div 
              v-else
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'just_skip' }"
              @click="pkConflictSelectedOption = 'just_skip'"
            >
              <div class="choice-card-header">
                <span class="choice-title">📦 稍后手动配置 / 暂不处理</span>
                <el-tag size="small" type="info" effect="plain">暂不处理</el-tag>
              </div>
              <div class="choice-description">
                先完成表结构识别，稍后在左侧配置面板中手动设定或补齐主键。
              </div>
            </div>
          </template>

          <!-- 情况二：已有 id 冲突时的两个最精简选项 -->
          <template v-else>
            <!-- 选项 1：去数据库删除 id -->
            <div 
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'delete_db_id' }"
              @click="pkConflictSelectedOption = 'delete_db_id'"
            >
              <div class="choice-card-header">
                <span class="choice-title">🔄 去数据库删除 id (由系统自动重建)</span>
                <el-tag size="small" type="info" effect="plain">推荐重建</el-tag>
              </div>
              <div class="choice-description">
                手工删除或重命名已有的 <code>id</code> 字段，由系统重新创建标准的自增主键，兼容性最佳。
              </div>
            </div>

            <!-- 选项 2：有物理主键时绑定物理主键，无物理主键时选择直接使用现有 id -->
            <div 
              v-if="pkConflictInfo.detectedPrimaryKey && !pkConflictInfo.isCompositePrimaryKey"
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'detected_pk' }"
              @click="pkConflictSelectedOption = 'detected_pk'"
            >
              <div class="choice-card-header">
                <span class="choice-title">💎 直接绑定物理主键 [{{ pkConflictInfo.detectedPrimaryKey }}]</span>
                <el-tag size="small" type="success" effect="dark">当前主键</el-tag>
              </div>
              <div class="choice-description">
                <span v-if="(pkConflictInfo.detectedPrimaryKey || '').toLowerCase() === 'id'">
                  使用当前物理主键 <code>id</code> 作为表单主键继续绑定，不进行重建或删除。
                </span>
                <span v-else>
                  直接使用物理表已有主键 <code>[{{ pkConflictInfo.detectedPrimaryKey }}]</code> 作为表单主键，表内的 <code>id</code> 字段将作为普通的非主键数据列保留。
                </span>
              </div>
            </div>

            <div 
              v-else
              class="choice-card"
              :class="{ active: pkConflictSelectedOption === 'existing_id' }"
              @click="pkConflictSelectedOption = 'existing_id'"
            >
              <div class="choice-card-header">
                <span class="choice-title">📦 直接使用表内现有 id 字段做为主键</span>
                <el-tag size="small" type="warning" effect="dark">自备主键</el-tag>
              </div>
              <div class="choice-description">
                直接使用数据库物理表中当前已有的 <code>id</code> 字段做为表单主键，不再对其进行删除或重建。
              </div>
            </div>
          </template>
        </div>
      </div>
      <template #footer>
        <el-button @click="handlePkConflictCancel">取消返回</el-button>
        <el-button type="primary" @click="handlePkConflictConfirm" :disabled="!pkConflictSelectedOption">确认并继续</el-button>
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
import { Setting, Notification, Grid, User, Plus, Upload, Delete, Check, Refresh, Right, Platform, UploadFilled, InfoFilled, Connection, Tickets, Document, ArrowRight, Sort, Lock } from '@element-plus/icons-vue'
import axios from 'axios'
import Sortable from 'sortablejs'

const router = useRouter()
const route = useRoute()
const currentUser = inject('currentUser', ref('管理员'))

const isEditMode = ref(!!route.params.id)

const formMeta = reactive({
  name: '',
  schemaName: 'ods',
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
  defaultFilterPolicy: 'FIRST_THREE',
  allowAdd: true,
  allowEdit: true,
  allowDelete: true
})

let _fieldUidCounter = 1
const fields = ref([
  { _uid: _fieldUidCounter++, name: '', columnName: '', originalColumnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false, filterType: 'input', filterOptions: [], filterOptionsSql: '', _filterSource: 'manual', hideInForm: false, hideInList: false, systemLocked: false, pattern: '', patternMsg: '', min: null, max: null, minLength: null, maxLength: null, validationSql: '', validationSqlMsg: '', visibleEmails: [], visibleEmailsStr: '' }
])

const importDialogVisible = ref(false)
const referenceTemplateDialogVisible = ref(false)
const existingTableDialogVisible = ref(false)
const isInspectingExistingTable = ref(false)
const isParsingReferenceTemplate = ref(false)
const bindExistingTableMode = ref(false)
const existingTableForm = reactive({
  schemaName: 'ods',
  tableName: ''
})
const importConfig = reactive({
  smartType: true,
  kvPairEnabled: true // 恢复启用智能键值对匹配
})
const isParsing = ref(false)
const pairConfirmDialogVisible = ref(false)

const pkConflictDialogVisible = ref(false)
const pkConflictSelectedOption = ref('')
const pkConflictInfo = ref({
  hasExistingId: false,
  detectedPrimaryKey: '',
  isCompositePrimaryKey: false,
  fields: [],
  schemaName: '',
  tableName: '',
  missingColumns: [],
  resData: null
})

const handlePkConflictCancel = () => {
  pkConflictDialogVisible.value = false
  isInspectingExistingTable.value = false
  ElMessage.info('已取消识别。')
}

const handlePkConflictConfirm = async () => {
  pkConflictDialogVisible.value = false
  const info = pkConflictInfo.value
  const res = info.resData
  
  let targetPk = 'id'
  let needAutoRepair = false
  
  if (pkConflictSelectedOption.value === 'detected_pk') {
    targetPk = info.detectedPrimaryKey || 'id'
  } else if (pkConflictSelectedOption.value === 'delete_db_id') {
    ElMessage.warning('请先去数据库手动删除或重命名已有的 id 列，然后重试。')
    isInspectingExistingTable.value = false
    return
  } else if (pkConflictSelectedOption.value === 'auto_repair_id') {
    targetPk = 'id'
    needAutoRepair = true
  } else if (pkConflictSelectedOption.value === 'just_skip') {
    targetPk = info.detectedPrimaryKey || ''
  }
  
  try {
    let mappedFields = res.fields.map(f => ({
      _uid: _fieldUidCounter++,
      ...f,
      required: f.required || false,
      filterable: f.filterable || false,
      filterType: f.filterType || 'input',
      filterOptions: f.filterOptions || [],
      filterOptionsSql: f.filterOptionsSql || '',
      _filterSource: f.filterOptionsSql ? 'sql' : 'manual',
      hideInForm: f.hideInForm || false,
      hideInList: f.hideInList || false,
      visibleEmails: f.visibleEmails || [],
      visibleEmailsStr: f.visibleEmails ? f.visibleEmails.join(', ') : '',
      systemLocked: isSystemManagedField(f)
    }))
    
    mappedFields.sort((a, b) => {
      const aName = (a.columnName || '').toLowerCase()
      const bName = (b.columnName || '').toLowerCase()
      if (aName === 'id') return -1
      if (bName === 'id') return 1
      return 0
    })
    
    ensureSystemFields(mappedFields)
    fields.value = mappedFields
    
    formMeta.tableName = info.tableName
    formMeta.schemaName = info.schemaName
    bindExistingTableMode.value = true
    existingTableDialogVisible.value = false
    missingColumns.value = res.missingColumns || []
    
    formMeta.insertDtColumn = res.detectedInsertDt
    formMeta.updateDtColumn = res.detectedUpdateDt
    formMeta.deleteFlagColumn = res.detectedDeleteFlag
    
    formMeta.pkColumn = targetPk
    
    if (needAutoRepair) {
      ElMessage.success('表结构识别成功，正在后台为您自动补齐自增 id 主键...')
      setTimeout(async () => {
        try {
          isRepairing.value = true
          const url = `/api/fill/forms/repairTableByName?schemaName=${formMeta.schemaName}&tableName=${formMeta.tableName}&userEmail=${currentUser.value}`
          const repairRes = await axios.post(url, ['id'])
          if (repairRes.data.success && repairRes.data.success.length > 0) {
            ElMessage.success('🎉 自增 id 主键已成功自动补齐到物理表中！')
            await inspectExistingTable(formMeta.schemaName, formMeta.tableName, true)
          } else {
            ElMessage.error('自动补齐失败，请在页面手动点击「一键补齐」')
          }
        } catch (e) {
          ElMessage.error('自动补齐过程中发生网络错误')
        } finally {
          isRepairing.value = false
        }
      }, 300)
    } else {
      ElMessage.success(`已成功识别表结构，主键已绑定为: [${formMeta.pkColumn}]`)
    }
  } catch (e) {
    ElMessage.error('字段绑定失败')
  } finally {
    isInspectingExistingTable.value = false
  }
}

const testSql = async (row) => {
  if (!row.validationSql) {
    ElMessage.warning('请先输入校验 SQL')
    return
  }
  if (!row._testSqlValue) {
    ElMessage.warning('请先输入测试值')
    return
  }
  row._testingSql = true
  try {
    const res = await axios.post(`/api/fill/test-sql`, null, {
      params: {
        sql: row.validationSql,
        testValue: row._testSqlValue,
        userEmail: currentUser.value
      }
    })
    if (res.data && res.data.success) {
      if (res.data.passed) {
        ElMessage.success(res.data.message || '测试通过')
      } else {
        ElMessage.warning(res.data.message || '测试未查得结果')
      }
    } else {
      ElMessage.error(res.data?.message || '测试失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '网络或服务端异常')
  } finally {
    row._testingSql = false
  }
}

const testFilterOptionsSql = async (row) => {
  if (!row.filterOptionsSql || !row.filterOptionsSql.trim()) {
    ElMessage.warning('请输入获取下拉选项的 SQL 语句')
    return
  }
  
  row._testingFilterSql = true
  try {
    const res = await axios.get('/api/fill/filter-options-sql', {
      params: {
        sql: row.filterOptionsSql.trim(),
        userEmail: currentUser.value
      }
    })
    
    if (Array.isArray(res.data)) {
      if (res.data.length === 0) {
        ElMessage.warning('测试成功，但该 SQL 未查得任何数据')
      } else {
        ElMessageBox.alert(`成功获取到 ${res.data.length} 条选项，前 5 条预览：<br/>${res.data.slice(0, 5).join('<br/>')}`, 'SQL 测试成功', {
          dangerouslyUseHTMLString: true,
          type: 'success'
        })
      }
    } else {
      ElMessage.error('SQL 测试返回格式异常')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || 'SQL 测试失败，请检查语句')
  } finally {
    row._testingFilterSql = false
  }
}

const confirmingPairs = ref([])
const lastParseResult = ref(null)
const lastFileName = ref('')
const headerMappings = ref([])
const referenceRows = ref([])
const referenceParserProfile = ref('')
const missingColumns = ref([])
const untrackedBusinessColumns = ref([])
const untrackedMapping = reactive({}) // 用于存储列映射关系
const missingBusinessColumns = ref([])
const isRepairing = ref(false)
const isSyncingColumns = ref(false)

const displayMissingColumns = computed(() => {
  if (formMeta.pkColumn && formMeta.pkColumn !== 'id') {
    return missingColumns.value.filter(col => col !== 'id')
  }
  return missingColumns.value
})

const isSimpleRename = computed(() => {
  return untrackedBusinessColumns.value.length === 1 && missingBusinessColumns.value.length === 1
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

// 拖拽排序初始化
const initDragSort = () => {
  const el = document.querySelector('.fields-list .el-table__body-wrapper tbody')
  if (!el) return
  
  Sortable.create(el, {
    handle: '.drag-handle',
    animation: 150,
    ghostClass: 'drag-ghost',
    onMove: (evt) => {
      // 禁止移动到系统列的位置
      if (evt.related.classList.contains('system-locked-row')) {
        return false
      }
      return true
    },
    onEnd: (evt) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex === newIndex) return
      
      // 物理 DOM 已经变了，需要手动同步 Vue 数组
      // 为了精确同步，我们先根据 DOM 结构记录下新的顺序，或者直接操作数组
      // 注意：eltable 渲染后 oldIndex/newIndex 对应的是 tbody 里的 tr 索引
      const targetRow = fields.value.splice(oldIndex, 1)[0]
      fields.value.splice(newIndex, 0, targetRow)
      
      // 强制刷新一次，防止 DOM 和数据不同步
      const raw = [...fields.value]
      fields.value = []
      setTimeout(() => {
        fields.value = raw
      }, 0)
    }
  })
}

const tableRowClassName = ({ row }) => {
  const isPk = (row.columnName || '').toLowerCase() === (formMeta.pkColumn || 'id').toLowerCase()
  if (isPk) {
    return 'pk-row'
  }
  return row.systemLocked ? 'system-locked-row' : ''
}

// 数据库模式列表
const availableSchemas = ref([])
const schemaLoading = ref(false)

const loadSchemas = async () => {
  schemaLoading.value = true
  try {
    const res = await axios.get('/api/fill/schemas')
    availableSchemas.value = res.data || []
    if (!isEditMode.value && availableSchemas.value.length > 0) {
      formMeta.schemaName = availableSchemas.value[0]
      existingTableForm.schemaName = availableSchemas.value[0]
    }
  } catch (e) {
    availableSchemas.value = []
  } finally {
    schemaLoading.value = false
  }
}

// 用户列表（权限分配）
const allUsers = ref([])
const fillUserList = ref([])
const userListLoading = ref(false)

const allDepartments = ref([])
const fillDepartmentList = ref([])
const departmentListLoading = ref(false)

const loadDepartmentList = async () => {
  departmentListLoading.value = true
  try {
    const res = await axios.get('/api/user/departments')
    allDepartments.value = res.data || []
  } catch (e) {
    allDepartments.value = []
  } finally {
    departmentListLoading.value = false
  }
}

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
  fields.value.push({ _uid: _fieldUidCounter++, name: '', columnName: '', originalColumnName: '', type: 'input', dbType: 'VARCHAR(255)', optionsStr: '', required: false, filterable: false, filterType: 'input', filterOptions: [], filterOptionsSql: '', _filterSource: 'manual', hideInForm: false, hideInList: false, systemLocked: false, pattern: '', patternMsg: '', min: null, max: null, minLength: null, maxLength: null, validationSql: '', validationSqlMsg: '', visibleEmails: [], visibleEmailsStr: '' })
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
    
    // [新逻辑] 检查主键冲突
    if (res.data.hasIdConflict) {
      ElMessageBox.alert(
        res.data.conflictMessage || '检测到 Excel 中已包含 id 字段。为了由系统统一管理主键并确保 COPY 导入性能，请管理员先删除 Excel 中的 id 列，然后再次上传识别。',
        '物理表主键冲突',
        { type: 'error', confirmButtonText: '知道了' }
      )
      importDialogVisible.value = false
      return
    }
    
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
    
    // [新逻辑] 检查主键冲突
    if (res.data.hasIdConflict) {
      ElMessageBox.alert(
        res.data.conflictMessage || '检测到参考模板中已包含 id 字段。为保证系统自动分配主键，请管理员先在参考模板中移除该列，然后再次识别。',
        '物理表主键冲突',
        { type: 'error', confirmButtonText: '知道了' }
      )
      referenceTemplateDialogVisible.value = false
      return
    }
    
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

const inspectExistingTable = async (sName, tName, silent = false) => {
  const schemaName = (typeof sName === 'string') ? sName : existingTableForm.schemaName
  const tableName = (typeof tName === 'string') ? tName : existingTableForm.tableName
  if (!tableName) {
    if (!sName) ElMessage.warning('请输入物理表名')
    return
  }
  isInspectingExistingTable.value = true
  try {
    const res = await axios.get('/api/fill/inspectTable', {
      params: { 
        tableName: tableName,
        schemaName: schemaName
      }
    })
    
    // 检查物理表中是否已存在 id 字段及是否缺失
    const hasExistingId = res.data.fields && res.data.fields.some(f => (f.columnName || '').toLowerCase() === 'id')
    const isMissingId = res.data.missingColumns && res.data.missingColumns.includes('id')
    
    if ((hasExistingId || isMissingId) && !silent) {
      // 触发精美的大弹窗，将解析结果存入 pkConflictInfo 等待用户决定
      pkConflictInfo.value = {
        hasExistingId: hasExistingId,
        detectedPrimaryKey: res.data.detectedPrimaryKey || '',
        isCompositePrimaryKey: res.data.isCompositePrimaryKey || res.data.compositePrimaryKey || false,
        fields: res.data.fields || [],
        schemaName: schemaName,
        tableName: tableName,
        missingColumns: res.data.missingColumns || [],
        resData: res.data
      }
      
      // 默认选中策略：
      if (hasExistingId) {
        if (res.data.detectedPrimaryKey && !res.data.isCompositePrimaryKey && !res.data.compositePrimaryKey) {
          pkConflictSelectedOption.value = 'detected_pk'
        } else {
          pkConflictSelectedOption.value = 'existing_id'
        }
      } else {
        pkConflictSelectedOption.value = 'auto_repair_id' // 强烈推荐自动补齐标准的自增 id 主键
      }
      
      pkConflictDialogVisible.value = true
      return
    }

    // 没有冲突也没有缺失 id 时，按标准流程直接导入绑定
    if (res.data && res.data.fields && res.data.fields.length > 0) {
      let mappedFields = res.data.fields.map(f => ({
        _uid: _fieldUidCounter++,
        ...f,
        required: f.required || false,
        filterable: f.filterable || false,
        filterType: f.filterType || 'input',
        filterOptions: f.filterOptions || [],
        filterOptionsSql: f.filterOptionsSql || '',
        _filterSource: f.filterOptionsSql ? 'sql' : 'manual',
        hideInForm: f.hideInForm || false,
        hideInList: f.hideInList || false,
        visibleEmails: f.visibleEmails || [],
        visibleEmailsStr: f.visibleEmails ? f.visibleEmails.join(', ') : '',
        systemLocked: isSystemManagedField(f)
      }))
      
      mappedFields.sort((a, b) => {
        const aName = (a.columnName || '').toLowerCase()
        const bName = (b.columnName || '').toLowerCase()
        if (aName === 'id') return -1
        if (bName === 'id') return 1
        return 0
      })
      
      ensureSystemFields(mappedFields)
      fields.value = mappedFields
      
      formMeta.tableName = tableName
      formMeta.schemaName = schemaName
      bindExistingTableMode.value = true
      existingTableDialogVisible.value = false
      missingColumns.value = res.data.missingColumns || []
      
      formMeta.insertDtColumn = res.data.detectedInsertDt
      formMeta.updateDtColumn = res.data.detectedUpdateDt
      formMeta.deleteFlagColumn = res.data.detectedDeleteFlag
      
      formMeta.pkColumn = res.data.detectedPrimaryKey || 'id'

      if (!silent) {
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

const checkTableConsistency = async () => {
  if (!formMeta.tableName) return
  try {
    const res = await axios.get('/api/fill/checkTable', {
      params: { 
        schemaName: formMeta.schemaName || 'public', 
        tableName: formMeta.tableName 
      }
    })
    missingColumns.value = res.data.missingColumns || []
    untrackedBusinessColumns.value = res.data.untrackedBusinessColumns || []
    missingBusinessColumns.value = res.data.missingBusinessColumns || []
    
    // 初始化映射建议
    untrackedBusinessColumns.value.forEach(c => {
      // 智能推断：如果库里多一个，配置里少一个，认为是重命名
      if (untrackedBusinessColumns.value.length === 1 && missingBusinessColumns.value.length === 1) {
        untrackedMapping[c] = missingBusinessColumns.value[0]
      } else {
        untrackedMapping[c] = '__NEW__'
      }
    })
  } catch (e) {
    console.warn('一致性检查失败', e)
  }
}

const handleAutoRenameFix = async () => {
  if (!isSimpleRename.value) return
  // 由于 checkTableConsistency 已经预设了 untrackedMapping，这里直接调同步即可
  await syncUntrackedColumns()
}

const syncUntrackedColumns = async () => {
  if (untrackedBusinessColumns.value.length === 0) return
  isSyncingColumns.value = true
  try {
    const res = await axios.get('/api/fill/inspectTable', {
      params: { 
        tableName: formMeta.tableName,
        schemaName: formMeta.schemaName
      }
    })
    
    if (res.data && res.data.fields) {
      const allNewFields = res.data.fields
      const currentFields = [...fields.value]
      let addedCount = 0
      let updatedCount = 0

      for (const col of untrackedBusinessColumns.value) {
        const choice = untrackedMapping[col]
        const colDef = allNewFields.find(f => (f.columnName || '').toLowerCase() === col.toLowerCase())
        if (!colDef) continue

        if (choice === '__NEW__' || !choice) {
          // 作为新字段追加
          currentFields.push({
            _uid: _fieldUidCounter++,
            ...colDef,
            filterType: colDef.filterType || 'input',
            filterOptions: colDef.filterOptions || [],
            filterOptionsSql: colDef.filterOptionsSql || '',
            _filterSource: colDef.filterOptionsSql ? 'sql' : 'manual',
            originalColumnName: colDef.columnName || '',
            systemLocked: isSystemManagedField(colDef)
          })
          addedCount++
        } else {
          // 替换/绑定到现有字段 (处理重命名)
          const targetIdx = currentFields.findIndex(f => (f.columnName || '').toLowerCase() === choice.toLowerCase())
          if (targetIdx > -1) {
             const oldField = currentFields[targetIdx]
             currentFields[targetIdx] = {
               ...oldField, // 保留原有的中文名、校验逻辑等
               columnName: colDef.columnName, // 更新为库里真实的新物理列名
               dbType: colDef.dbType
             }
             updatedCount++
          }
        }
      }

      ensureSystemFields(currentFields)
      fields.value = currentFields
      ElMessage.success(`操作完成：新增 ${addedCount} 个，绑定重命名 ${updatedCount} 个。`)
      untrackedBusinessColumns.value = []
      missingBusinessColumns.value = []
    }
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.response?.data?.message || '网络异常'))
  } finally {
    isSyncingColumns.value = false
  }
}

const removeMissingBusinessColumns = () => {
  if (missingBusinessColumns.value.length === 0) return
  const cols = new Set(missingBusinessColumns.value.map(c => c.toLowerCase()))
  fields.value = fields.value.filter(f => {
    const lmn = (f.columnName || '').toLowerCase()
    return !cols.has(lmn)
  })
  missingBusinessColumns.value = []
  ElMessage.success('已从页面配置中移除并同步状态')
}

const repairMissingBusinessColumns = async () => {
  if (missingBusinessColumns.value.length === 0) return
  isRepairing.value = true
  try {
    const res = await axios.post(`/api/fill/forms/${route.params.id}/repairTable?userEmail=${currentUser.value}`, missingBusinessColumns.value)
    if (res.data.success && res.data.success.length > 0) {
      ElMessage.success(`成功在库中重建字段: ${res.data.success.join(', ')}`)
      checkTableConsistency() 
    }
  } catch (e) {
    ElMessage.error('重建物理字段失败: ' + (e.response?.data?.message || '网络异常'))
  } finally {
    isRepairing.value = false
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
    
    const res = await axios.post(url, targetCols.map(c => c.split(' ')[0]))
    if (res.data.success && res.data.success.length > 0) {
      ElMessage.success(`成功补齐字段: ${res.data.success.join(', ')}`)
      // 核心：补齐成功后重新识别，使系统字段出现在列表中。此处使用 silent=true 避开冲突弹窗
      await inspectExistingTable(formMeta.schemaName, formMeta.tableName, true)
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
  
  // 1. 处理拒绝 of 配对：将被排除的原始列还原成标准字段
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
          filterable: false,
          filterType: 'input',
          filterOptions: [],
          filterOptionsSql: '',
          _filterSource: 'manual'
        })
      }
    })
  })

  // 2. 处理确认 of 配对：添加虚拟 JSON 字段
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
        filterType: 'input',
        filterOptions: [],
        filterOptionsSql: '',
        _filterSource: 'manual',
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
  const reserved = [
    'id', 'load_user', 'extra_data',
    'w_insert_dt', 'w_update_dt', 'delete_flag',
    'create_time', 'update_time', 'created_at', 'updated_at',
    'is_delete', 'deleted', 'del_flag', 'insert_time'
  ]
  return reserved.includes((field?.columnName || '').toLowerCase())
}

const ensureSystemFields = (fieldsArray) => {
  if (!fieldsArray) return
  const systemFields = [
    { name: 'ID', columnName: 'id', dbType: 'INTEGER', type: 'int', systemLocked: true, required: true, hideInForm: true, hideInList: true },
    { name: '创建时间', columnName: 'w_insert_dt', dbType: 'TIMESTAMP', type: 'datetime', systemLocked: true, required: false, hideInForm: true, hideInList: true },
    { name: '更新时间', columnName: 'w_update_dt', dbType: 'TIMESTAMP', type: 'datetime', systemLocked: true, required: false, hideInForm: true, hideInList: true },
    { name: '导入用户', columnName: 'load_user', dbType: 'VARCHAR(100)', type: 'input', systemLocked: true, required: false, hideInForm: true, hideInList: true },
    { name: '删除标记', columnName: 'delete_flag', dbType: 'BOOLEAN', type: 'boolean', systemLocked: true, required: true, hideInForm: true, hideInList: true }
  ]
  systemFields.forEach(sf => {
    if (!fieldsArray.some(f => (f.columnName || '').toLowerCase() === sf.columnName.toLowerCase())) {
      fieldsArray.push({
        _uid: _fieldUidCounter++,
        ...sf,
        originalColumnName: sf.columnName,
        filterType: 'input',
        filterOptions: [],
        filterOptionsSql: '',
        _filterSource: 'manual',
        visibleEmails: []
      })
    }
  })
  
  // 确保 ID 放在第一行
  const idIdx = fieldsArray.findIndex(f => (f.columnName || '').toLowerCase() === 'id')
  if (idIdx > 0) {
    const idField = fieldsArray.splice(idIdx, 1)[0]
    fieldsArray.unshift(idField)
  }
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
  
  let mappedFields = data.fields.map(f => {
    const row = {
      _uid: _fieldUidCounter++,
      ...f,
      columnName: f.columnName || '',
      originalColumnName: f.originalColumnName || '',
      type: f.type || 'input',
      dbType: f.dbType || 'VARCHAR(255)',
      required: f.required || false,
      filterable: f.filterable || false,
      filterType: f.filterType || 'input',
      filterOptions: f.filterOptions || [],
      filterOptionsSql: f.filterOptionsSql || '',
      _filterSource: f.filterOptionsSql ? 'sql' : 'manual',
      hideInForm: f.hideInForm || false,
      hideInList: f.hideInList || false,
      visibleEmails: f.visibleEmails || [],
      visibleEmailsStr: f.visibleEmails ? f.visibleEmails.join(', ') : '',
      systemLocked: isSystemManagedField(f)
    }
    if (!row.columnName) {
        row.columnName = generateColumnName(f.name)
    }
    handleDbTypeChange(row.dbType, row)
    return row
  })
  
  // 排序：将 id 放在第一行
  mappedFields.sort((a, b) => {
    const aName = (a.columnName || '').toLowerCase()
    const bName = (b.columnName || '').toLowerCase()
    if (aName === 'id') return -1
    if (bName === 'id') return 1
    return 0
  })
  
  ensureSystemFields(mappedFields)
  fields.value = mappedFields
    
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
        
        const needsId = currentMissing.includes('id') && (!formMeta.pkColumn || formMeta.pkColumn === 'id')
        const missingDeleteFlag = currentMissing.includes('delete_flag') && !formMeta.hardDelete
        
        if (needsId || missingDeleteFlag) {
            const msg = needsId ? '物理表缺少标准主键 "id"' : '物理表缺少 "delete_flag" 状态位'
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
    if (e === 'cancel' || e === 'close') {
      // 用户主动取消发布，直接返回，不继续往下跑
      return
    }
    console.warn('校验物理表状态失败', e)
    // 接口本身报错（如 500）时不阻断后续发布，交由后端最终校验兜底
  }

  const formattedFields = fields.value.map(f => ({
    name: f.name,
    columnName: f.columnName,
    type: f.type,
    dbType: f.dbType,
    required: f.required,
    filterable: f.filterable,
    filterType: f.filterType || 'input',
    filterOptions: f.filterOptions || [],
    filterOptionsSql: f.filterOptionsSql || '',
    _filterSource: f.filterOptionsSql ? 'sql' : 'manual',
    hideInForm: f.hideInForm,
    hideInList: f.hideInList,
    options: f.options || null,
    visibleEmails: f.visibleEmails || []
  }))

  const payload = {
    ...formMeta,
    deadline: formMeta.reminderMode === 'DEADLINE' ? (formMeta.deadline || null) : null,
    recipientEmails: recipientList.value.length > 0 ? JSON.stringify(recipientList.value) : null,
    fillUserEmails: fillUserList.value.length > 0 ? JSON.stringify(fillUserList.value) : null,
    fillDepartments: fillDepartmentList.value.length > 0 ? JSON.stringify(fillDepartmentList.value) : null,
    forms: JSON.stringify(formattedFields),
    folderId: formMeta.folderId || null,
    kvConfig: formMeta.kvConfig,
    pkColumn: formMeta.pkColumn || 'id',
    referenceTemplateConfig: formMeta.referenceTemplateConfig || null,
    allowAdd: formMeta.allowAdd !== false,
    allowEdit: formMeta.allowEdit !== false,
    allowDelete: formMeta.allowDelete !== false,
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
    if (res.data.fillDepartments) {
        try { fillDepartmentList.value = JSON.parse(res.data.fillDepartments) } catch (e) { fillDepartmentList.value = [] }
    }
    if (res.data.forms) {
        const parsed = JSON.parse(res.data.forms)
        fields.value = parsed.map(f => ({
          _uid: _fieldUidCounter++,
          ...f,
          originalColumnName: f.columnName || '',
          filterType: f.filterType || 'input',
          filterOptions: f.filterOptions || [],
          filterOptionsSql: f.filterOptionsSql || '',
          _filterSource: f.filterOptionsSql ? 'sql' : 'manual',
          visibleEmails: f.visibleEmails || [],
          visibleEmailsStr: f.visibleEmails ? f.visibleEmails.join(', ') : '',
          systemLocked: isSystemManagedField(f)
        }))
        ensureSystemFields(fields.value)
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
    
    // 加载完成后执行一致性检查
    checkTableConsistency()
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
    fillDepartments: fillDepartmentList.value.length > 0 ? JSON.stringify(fillDepartmentList.value) : null,
    folderId: formMeta.folderId || null,
    forms: JSON.stringify(fields.value.map(f => {
      const { id_mark, systemLocked, ...rest } = f
      rest.visibleEmails = f.visibleEmails || []
      return rest
    })),
    kvConfig: formMeta.kvConfig,
    pkColumn: formMeta.pkColumn || 'id',
    referenceTemplateConfig: formMeta.referenceTemplateConfig || null,
    allowAdd: formMeta.allowAdd !== false,
    allowEdit: formMeta.allowEdit !== false,
    allowDelete: formMeta.allowDelete !== false,
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
  loadDepartmentList()
  loadFolderTree()
  if (isEditMode.value) {
    loadFormForEdit().then(() => {
       setTimeout(initDragSort, 500)
    })
  } else {
    ensureSystemFields(fields.value)
    setTimeout(initDragSort, 500)
  }
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
  margin-bottom: 24px;
  padding: 16px 0;
  background: white;
  z-index: 100;
  transition: all 0.3s;
}

.sticky-header {
  position: sticky;
  top: 0;
  padding: 12px 0;
  border-bottom: 2px solid #f8fafc;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px) saturate(180%);
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
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 2px;
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
  font-size: 22px;
  font-weight: 800;
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
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.sidebar-card {
  border: 1px solid #f1f5f9;
  border-radius: 12px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.sidebar-card:hover {
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.05), 0 4px 6px -2px rgba(0, 0, 0, 0.02);
}

.fields-main {
  flex: 1;
  min-width: 0; /* 防止表格撑开容器 */
}

.fields-card {
  border: 1px solid #f1f5f9;
  border-radius: 12px;
}

.form-row {
  display: flex;
  gap: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 16px;
  letter-spacing: 0.01em;
}

.meta-form :deep(.el-form-item__label) {
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
  padding-bottom: 4px;
}

.meta-form :deep(.el-input__wrapper),
.meta-form :deep(.el-textarea__wrapper) {
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  transition: all 0.2s;
  padding: 4px 12px;
}

.meta-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--el-color-primary-light-5) inset !important;
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

.permission-settings {
  display: flex;
  background: #f8fafc;
  padding: 12px;
  border-radius: 12px;
  gap: 16px;
  margin-top: 8px;
  border: 1px solid #e2e8f0;
}

.permission-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.p-label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  white-space: nowrap;
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

.drag-handle {
  transition: color 0.2s;
}

.drag-handle:hover {
  color: var(--el-color-primary) !important;
}

.drag-ghost {
  background: #ecf5ff !important;
  opacity: 0.8;
}

:deep(.system-locked-row) {
  background-color: #fcfcfc;
}

:deep(.system-locked-row.el-table__row) {
  cursor: not-allowed !important;
}

:deep(.pk-row) {
  background-color: #fffbeb !important; /* Soft, modern amber/yellow highlight */
}

:deep(.pk-row td) {
  border-bottom: 1px dashed #fbd38d !important;
}
:deep(.pk-row .el-input__inner) {
  background-color: #fffdf5 !important;
}

.choice-cards-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.choice-card {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  background-color: #f8fafc;
}
.choice-card:hover {
  border-color: #cbd5e1;
  background-color: #f1f5f9;
  transform: translateY(-1px);
  box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.05);
}
.choice-card.active {
  border-color: #3b82f6;
  background-color: #eff6ff;
  box-shadow: 0 4px 12px -2px rgb(59 130 246 / 0.15);
}
.choice-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.choice-title {
  font-size: 14px;
  font-weight: bold;
  color: #1e293b;
}
.choice-card.active .choice-title {
  color: #1d4ed8;
}
.choice-description {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.choice-card.active .choice-description {
  color: #2563eb;
}
</style>
