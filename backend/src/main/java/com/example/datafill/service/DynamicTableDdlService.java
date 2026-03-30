package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.dto.FieldDef;
import com.example.datafill.entity.DataFillFolder;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.entity.UserFillLog;

import com.example.datafill.mapper.DataFillFormMapper;

import com.example.datafill.mapper.DynamicSqlMapper;

import com.example.datafill.mapper.UserFillLogMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTableDdlService {

    private final DataFillFormMapper formMapper;

    private final DynamicSqlMapper dynamicSqlMapper;

    private final UserFillLogMapper userFillLogMapper;

    private final ObjectMapper objectMapper;

    private final SchedulerService schedulerService;

    private final JdbcTemplate jdbcTemplate;

    private final DataFillFolderService folderService;

    private static final java.util.Set<String> RESERVED_COLUMN_NAMES = java.util.Set.of(
            "id", "is_deleted", "w_insert_dt", "w_update_dt", "load_user", "job_instance", "extra_data"
    );

    private boolean isReservedColumnName(String columnName) {
        return columnName != null && RESERVED_COLUMN_NAMES.contains(columnName.toLowerCase());
    }

    private boolean isSystemManagedColumn(String columnName) {
        return "extra_data".equalsIgnoreCase(columnName);
    }

    private String normalizeColumnName(String columnName) {
        return columnName == null ? null : columnName.trim();
    }

    private String escapeSqlLiteral(String text) {
        return text == null ? "" : text.replace("'", "''");
    }

    private String normalizeDbTypeForPostgres(String dbType) {
        if (dbType == null) {
            return null;
        }
        String trimmed = dbType.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        // 页面可保留 INT(10) / int10 这类展示值，但 PostgreSQL 不支持整型带长度，执行 DDL 时归一化。
        if (trimmed.matches("(?i)^int\\s*\\(\\s*\\d+\\s*\\)$")) {
            return "INT";
        }
        if (trimmed.matches("(?i)^int\\s*\\d+$")) {
            return "INT";
        }
        if (trimmed.matches("(?i)^integer\\s*\\(\\s*\\d+\\s*\\)$")) {
            return "INTEGER";
        }
        if (trimmed.matches("(?i)^integer\\s*\\d+$")) {
            return "INTEGER";
        }
        return trimmed;
    }

    private String buildPgErrorPositionText(org.postgresql.util.PSQLException e) {
        try {
            org.postgresql.util.ServerErrorMessage serverError = e.getServerErrorMessage();
            if (serverError != null && serverError.getPosition() >= 0) {
                return " Position: " + serverError.getPosition();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "";
    }

    private String resolveTableComment(DataFillForm form) {
        if (form.getTableComment() != null && !form.getTableComment().isBlank()) {
            return form.getTableComment().trim();
        }
        return form.getName();
    }

    private void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("物理表名只能包含字母、数字和下划线");
        }
    }

    private List<FieldDef> parseFields(String formsJson) {
        try {
            return objectMapper.readValue(formsJson, new TypeReference<List<FieldDef>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("字段 JSON 解析失败", e);
        }
    }

    private boolean physicalTableExists(String tableName) {
        String sql = """
                SELECT COUNT(1)
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = ?
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName);
        return count != null && count > 0;
    }

    private java.util.Set<String> loadPhysicalColumns(String tableName) {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                """;
        List<String> columns = jdbcTemplate.queryForList(sql, String.class, tableName);
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String column : columns) {
            if (column != null) {
                result.add(column.toLowerCase());
            }
        }
        return result;
    }

    private void applyFormDefaultsForMetadata(DataFillForm form) {
        if (form.getStatus() == null || form.getStatus().trim().isEmpty()) {
            form.setStatus("ACTIVE");
        }
        if (form.getReminderDays() == null) {
            form.setReminderDays(3);
        }
        if (form.getReminderMode() == null || form.getReminderMode().isBlank()) {
            form.setReminderMode("DEADLINE");
        }
        if (form.getReminderTime() == null || form.getReminderTime().isBlank()) {
            form.setReminderTime("09:00");
        }
        form.setCreateTime(LocalDateTime.now());
        form.setUpdateTime(LocalDateTime.now());
        schedulerService.initOrRefreshDeadline(form, LocalDateTime.now());
    }

    private Map<String, String> buildFolderPathMap() {
        List<DataFillFolder> folders = folderService.listAllFolders();
        Map<String, DataFillFolder> folderMap = new LinkedHashMap<>();
        for (DataFillFolder folder : folders) {
            folderMap.put(folder.getId(), folder);
        }

        Map<String, String> pathMap = new LinkedHashMap<>();
        for (DataFillFolder folder : folders) {
            List<String> segments = new ArrayList<>();
            DataFillFolder current = folder;
            while (current != null) {
                if (current.getName() != null && !current.getName().isBlank()) {
                    segments.add(0, current.getName());
                }
                String parentId = current.getParentId();
                current = parentId == null ? null : folderMap.get(parentId);
            }
            pathMap.put(folder.getId(), String.join(" / ", segments));
        }
        return pathMap;
    }

    /**

     * 1. 保存表单配置并动态物理建表

     */

    @Transactional(rollbackFor = Exception.class)

    public String createFormAndTable(DataFillForm form) {

        // 1. 检查物理表名是否重复

        if (formMapper.selectCount(new QueryWrapper<DataFillForm>().eq("table_name", form.getTableName())) > 0) {

            throw new RuntimeException("物理表名已存在！");

        }

        // 2. 解析前端传来的字段 JSON

        List<FieldDef> fields = parseFields(form.getForms());

        // 简单校验一下表名，必须是英文字母数字下划线

        validateTableName(form.getTableName());

        // 3. 拼接 PostgreSQL 建表 DDL 语句

        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE TABLE \"").append(form.getTableName()).append("\" ( ");

        // 强制带上主键ID字段

        ddl.append("\"id\" VARCHAR(50) PRIMARY KEY, ");

        java.util.Set<String> seenNames = new java.util.HashSet<>();

        for (FieldDef field : fields) {

            String colName = normalizeColumnName(field.getColumnName());
            field.setColumnName(colName);
            if (colName == null || !colName.matches("^[a-zA-Z0-9_]+$")) {
                throw new RuntimeException("列名只能包含字母、数字和下划线: " + colName);
            }
            if (isReservedColumnName(colName)) {
                if ("extra_data".equalsIgnoreCase(colName)) {
                    // 允许 extra_data 出现在字段列表中（用于视觉提示），但不需要为其生成额外的物理列 DDL
                    continue; 
                }
                throw new RuntimeException("列名 '" + colName + "' 是系统保留字段，不允许用户创建，请修改英文字段名");
            }
            if (!seenNames.add(colName.toLowerCase())) {
                throw new RuntimeException("检测到重复的英文字段名: " + colName);
            }

            ddl.append("\"").append(colName).append("\" ");
            
            // 优先使用用户自定义的物理类型 (dbType)
            if (field.getDbType() != null && !field.getDbType().trim().isEmpty()) {
                ddl.append(normalizeDbTypeForPostgres(field.getDbType()));
            } else {
                // 简单的类型映射 (PGSQL 语法)
                switch (field.getType().toLowerCase()) {
                    case "varchar":
                    case "text":
                    case "input":
                    case "select":
                    case "textarea":
                        ddl.append("VARCHAR(").append(field.getLength() != null ? field.getLength() : 255).append(")");
                        break;
                    case "int":
                    case "number":
                        ddl.append("INTEGER");
                        break;
                    case "decimal":
                        ddl.append("NUMERIC(15, 4)");
                        break;
                    case "datetime":
                    case "date":
                        ddl.append("TIMESTAMP");
                        break;
                    case "boolean":
                        ddl.append("BOOLEAN");
                        break;
                    default:
                        ddl.append("VARCHAR(255)");
                }
            }

            if (field.getRequired() != null && field.getRequired()) {

                ddl.append(" NOT NULL");

            }

            ddl.append(", ");

        }

        // 增加审计字段, 按照用户数仓标准 (DW Standard)

        ddl.append("\"w_insert_dt\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP, ");

        ddl.append("\"w_update_dt\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP, ");

        ddl.append("\"load_user\" VARCHAR(100), ");

        ddl.append("\"job_instance\" VARCHAR(80), ");

        ddl.append("\"is_deleted\" SMALLINT DEFAULT 0");

        ddl.append(" );");

        // 4. 执行建表原生 SQL

        try {
            dynamicSqlMapper.executeDdl(ddl.toString());
        } catch (Exception e) {
            // 把关键的 SQL 错误信息透传到前端，便于管理员直接定位（例如 [42704] 类型不存在）
            if (e instanceof org.postgresql.util.PSQLException) {
                org.postgresql.util.PSQLException pe = (org.postgresql.util.PSQLException) e;
                String posText = buildPgErrorPositionText(pe);
                throw new RuntimeException("建表失败: [" + pe.getSQLState() + "] " + pe.getMessage() + posText);
            }
            throw new RuntimeException("建表失败: " + e.getMessage());
        }

        // 添加表注释和字段注释

        // 注释里的单引号要转义防注入

            try {
                dynamicSqlMapper.executeDdl("COMMENT ON TABLE \"" + form.getTableName() + "\" IS '" + escapeSqlLiteral(resolveTableComment(form)) + "';");
            } catch (Exception e) {
                if (e instanceof org.postgresql.util.PSQLException) {
                    org.postgresql.util.PSQLException pe = (org.postgresql.util.PSQLException) e;
                    String posText = buildPgErrorPositionText(pe);
                    throw new RuntimeException("建表注释失败: [" + pe.getSQLState() + "] " + pe.getMessage() + posText);
                }
                throw new RuntimeException("建表注释失败: " + e.getMessage());
            }

        for (FieldDef field : fields) {

            try {
                dynamicSqlMapper.executeDdl("COMMENT ON COLUMN \"" + form.getTableName() + "\".\"" + field.getColumnName() + "\" IS '" + escapeSqlLiteral(field.getName()) + "';");
            } catch (Exception e) {
                if (e instanceof org.postgresql.util.PSQLException) {
                    org.postgresql.util.PSQLException pe = (org.postgresql.util.PSQLException) e;
                    String posText = buildPgErrorPositionText(pe);
                    throw new RuntimeException("建表字段注释失败: [" + pe.getSQLState() + "] " + pe.getMessage() + posText);
                }
                throw new RuntimeException("建表字段注释失败: " + e.getMessage());
            }

        }

        // 5. 将表单元数据存入元数据表

        applyFormDefaultsForMetadata(form);

        formMapper.insert(form);

        return form.getId();

    }

    @Transactional(rollbackFor = Exception.class)
    public String bindExistingTable(DataFillForm form) {
        if (formMapper.selectCount(new QueryWrapper<DataFillForm>().eq("table_name", form.getTableName())) > 0) {
            throw new RuntimeException("物理表名已存在！");
        }

        validateTableName(form.getTableName());
        if (!physicalTableExists(form.getTableName())) {
            throw new RuntimeException("指定的物理表不存在: " + form.getTableName());
        }

        List<FieldDef> fields = parseFields(form.getForms());
        if (fields == null || fields.isEmpty()) {
            throw new RuntimeException("请先从已有表识别字段结构");
        }

        java.util.Set<String> physicalColumns = loadPhysicalColumns(form.getTableName());

        java.util.Set<String> seenColumns = new java.util.HashSet<>();
        for (FieldDef field : fields) {
            String columnName = normalizeColumnName(field.getColumnName());
            field.setColumnName(columnName);
            if (columnName == null || !columnName.matches("^[a-zA-Z0-9_]+$")) {
                throw new RuntimeException("列名只能包含字母、数字和下划线: " + columnName);
            }
            if (!seenColumns.add(columnName.toLowerCase())) {
                throw new RuntimeException("检测到重复的英文字段名: " + columnName);
            }
            if (!physicalColumns.contains(columnName.toLowerCase())) {
                throw new RuntimeException("已有表中不存在字段: " + columnName);
            }
        }

        form.setForms(writeFieldsJson(fields));
        applyFormDefaultsForMetadata(form);
        formMapper.insert(form);
        return form.getId();
    }

    private String writeFieldsJson(List<FieldDef> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("字段 JSON 生成失败", e);
        }
    }

    /**

     * 1.5 删除表单及其物理表（改为软删除重命名方式）

     */

    @Transactional(rollbackFor = Exception.class)

    public void deleteFormAndTable(String formId) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) return;

        // 删除物理表变更为重命名
        try {
            if (physicalTableExists(form.getTableName())) {
                long timestamp = System.currentTimeMillis();
                String newTableName = form.getTableName() + "_del_" + timestamp;
                dynamicSqlMapper.executeDdl("ALTER TABLE \"" + form.getTableName() + "\" RENAME TO \"" + newTableName + "\"");
            } else {
                log.warn("物理表 {} 不存在，跳过重命名步骤", form.getTableName());
            }
        } catch (Exception e) {
            // 兜底异常捕获，即使判断后执行依然报错也不应阻断元数据删除
            log.error("物理表重命名失败: {}", form.getTableName(), e);
        }

        // 删除元数据记录
        formMapper.deleteById(formId);

    }

    /**

     * 1.6 更新表单元数据（不修改物理表结构）

     */

    @Transactional(rollbackFor = Exception.class)

    public void updateFormMeta(String formId, DataFillForm incoming) {

        DataFillForm exist = formMapper.selectById(formId);

        if (exist == null) {

            throw new RuntimeException("表单不存在");

        }

        // 仅更新元数据相关字段，不改物理表名和字段定义

        if (incoming.getName() != null) {

            exist.setName(incoming.getName());

        }

        if (incoming.getStatus() != null) {

            exist.setStatus(incoming.getStatus());

        }

        if (incoming.getReminderMode() != null) {

            exist.setReminderMode(incoming.getReminderMode());

        }

        if ("DEADLINE".equalsIgnoreCase(exist.getReminderMode())) {

            exist.setDeadline(incoming.getDeadline());

        } else {

            // 循环模式下清空旧截止时间，强制重新推演计算

            exist.setDeadline(null); 

        }

        if (incoming.getReminderDays() != null) {

            exist.setReminderDays(incoming.getReminderDays());

        }

        if (incoming.getMonthlyDay() != null || incoming.getMonthlyDay() == null) {

            exist.setMonthlyDay(incoming.getMonthlyDay());

        }

        if (incoming.getWeeklyDayOfWeek() != null || incoming.getWeeklyDayOfWeek() == null) {

            exist.setWeeklyDayOfWeek(incoming.getWeeklyDayOfWeek());

        }

        if (incoming.getRecipientEmails() != null || incoming.getRecipientEmails() == null) {

            exist.setRecipientEmails(incoming.getRecipientEmails());

        }

        if (incoming.getFillUserEmails() != null || incoming.getFillUserEmails() == null) {

            exist.setFillUserEmails(incoming.getFillUserEmails());

        }

        if (incoming.getCycleDays() != null || incoming.getCycleDays() == null) {

            exist.setCycleDays(incoming.getCycleDays());

        }

        if (incoming.getFolderId() != null || incoming.getFolderId() == null) {

            exist.setFolderId(incoming.getFolderId());

        }

        if (incoming.getTableComment() != null || incoming.getTableComment() == null) {

            exist.setTableComment(incoming.getTableComment());

        }

        // 提醒时间允许前端传 null 表示使用默认值

        if (incoming.getReminderTime() != null || incoming.getReminderTime() == null) {

            exist.setReminderTime(incoming.getReminderTime());

        }

        LocalDateTime now = LocalDateTime.now();

        exist.setUpdateTime(now);

        // 1.7 [新增/修改逻辑]: 处理字段变更（支持新增列、修改中文名、重命名列、修改物理类型）
        if (incoming.getForms() != null) {
            try {
                List<FieldDef> newFields = objectMapper.readValue(incoming.getForms(), new TypeReference<List<FieldDef>>() {});
                List<FieldDef> oldFields = objectMapper.readValue(exist.getForms(), new TypeReference<List<FieldDef>>() {});
                
                String tableName = exist.getTableName();
                Map<String, FieldDef> oldFieldMap = new LinkedHashMap<>();
                for (FieldDef f : oldFields) {
                    String oldColName = normalizeColumnName(f.getColumnName());
                    if (oldColName != null) {
                        f.setColumnName(oldColName);
                        oldFieldMap.put(oldColName.toLowerCase(), f);
                    }
                }

                java.util.Set<String> seenNewNames = new java.util.HashSet<>();
                for (FieldDef nf : newFields) {
                    String colName = normalizeColumnName(nf.getColumnName());
                    nf.setColumnName(colName);
                    String originalColName = normalizeColumnName(
                            (nf.getOriginalColumnName() == null || nf.getOriginalColumnName().isBlank())
                                    ? colName
                                    : nf.getOriginalColumnName()
                    );
                    nf.setOriginalColumnName(originalColName);

                    if (colName == null || !colName.matches("^[a-zA-Z0-9_]+$")) {
                        throw new RuntimeException("列名只能包含字母、数字和下划线: " + colName);
                    }
                    if (!seenNewNames.add(colName.toLowerCase())) {
                        throw new RuntimeException("检测到重复的英文字段名: " + colName);
                    }
                    if (isReservedColumnName(colName) && !colName.equalsIgnoreCase(originalColName)) {
                        throw new RuntimeException("列名 '" + colName + "' 是系统保留字段，不允许修改为该名称");
                    }
                }

                for (FieldDef nf : newFields) {
                    String colName = nf.getColumnName();
                    if (colName == null) continue;

                    String originalColName = nf.getOriginalColumnName();
                    FieldDef of = originalColName == null ? null : oldFieldMap.get(originalColName.toLowerCase());
                    if (of == null) {
                        // A: 发现新字段 -> 执行 ALTER TABLE ADD COLUMN
                        log.info("表单 {} 检测到新字段 {}, 准备执行物理加列", exist.getName(), colName);
                        StringBuilder addColSql = new StringBuilder();
                        addColSql.append("ALTER TABLE \"").append(tableName).append("\" ADD COLUMN \"").append(colName).append("\" ");
                        
                        if (nf.getDbType() != null && !nf.getDbType().isBlank()) {
                            addColSql.append(normalizeDbTypeForPostgres(nf.getDbType()));
                        } else {
                            addColSql.append("VARCHAR(255)");
                        }
                        
                        if (nf.getRequired() != null && nf.getRequired()) {
                            addColSql.append(" DEFAULT ''"); // 生产环境 ADD COLUMN NOT NULL 建议带 DEFAULT
                        }
                        
                        dynamicSqlMapper.executeDdl(addColSql.toString());
                        // 同步添加注释
                        dynamicSqlMapper.executeDdl("COMMENT ON COLUMN \"" + tableName + "\".\"" + colName + "\" IS '" + escapeSqlLiteral(nf.getName()) + "';");
                    } else {
                        String physicalColName = of.getColumnName();

                        if (isSystemManagedColumn(physicalColName)) {
                            if (!colName.equalsIgnoreCase(physicalColName)) {
                                throw new RuntimeException("系统保留列 " + physicalColName + " 不允许修改列名");
                            }
                            if (nf.getDbType() != null && of.getDbType() != null
                                    && !nf.getDbType().trim().equalsIgnoreCase(of.getDbType().trim())) {
                                throw new RuntimeException("系统保留列 " + physicalColName + " 不允许修改字段类型");
                            }
                        } else if (!colName.equalsIgnoreCase(physicalColName)) {
                            log.info("表单 {} 字段 {} 重命名为 {}", exist.getName(), physicalColName, colName);
                            dynamicSqlMapper.executeDdl("ALTER TABLE \"" + tableName + "\" RENAME COLUMN \"" + physicalColName + "\" TO \"" + colName + "\"");
                            physicalColName = colName;
                        }

                        String oldDbType = of.getDbType() == null ? "" : normalizeDbTypeForPostgres(of.getDbType());
                        String newDbType = nf.getDbType() == null ? "" : normalizeDbTypeForPostgres(nf.getDbType());
                        if (!newDbType.isBlank() && !newDbType.equalsIgnoreCase(oldDbType)) {
                            log.info("表单 {} 字段 {} 类型由 {} 改为 {}", exist.getName(), physicalColName, oldDbType, newDbType);
                            dynamicSqlMapper.executeDdl(
                                    "ALTER TABLE \"" + tableName + "\" ALTER COLUMN \"" + physicalColName + "\" TYPE " + newDbType
                                            + " USING \"" + physicalColName + "\"::" + newDbType
                            );
                        }

                        // B: 现有字段 -> 检查名称是否改变，更新备注
                        if (!java.util.Objects.equals(nf.getName(), of.getName())) {
                            log.info("表单 {} 字段 {} 名称由 {} 改为 {}, 更新备注", exist.getName(), colName, of.getName(), nf.getName());
                        }
                        if (!java.util.Objects.equals(nf.getName(), of.getName()) || !colName.equalsIgnoreCase(of.getColumnName())) {
                            dynamicSqlMapper.executeDdl("COMMENT ON COLUMN \"" + tableName + "\".\"" + physicalColName + "\" IS '" + escapeSqlLiteral(nf.getName()) + "';");
                        }
                    }
                }
                // 更新元数据 JSON
                exist.setForms(incoming.getForms());
            } catch (Exception e) {
                log.error("更新表单字段元数据失败", e);
                throw new RuntimeException("更新表单物理结构失败: " + e.getMessage());
            }
        }

        if (incoming.getKvConfig() != null || incoming.getKvConfig() == null) {
            exist.setKvConfig(incoming.getKvConfig());
        }

        if (incoming.getReferenceTemplateConfig() != null || incoming.getReferenceTemplateConfig() == null) {
            exist.setReferenceTemplateConfig(incoming.getReferenceTemplateConfig());
        }

        if ((incoming.getName() != null || incoming.getTableComment() != null || incoming.getTableComment() == null)
                && exist.getTableName() != null
                && physicalTableExists(exist.getTableName())) {
            try {
                dynamicSqlMapper.executeDdl(
                        "COMMENT ON TABLE \"" + exist.getTableName() + "\" IS '" + escapeSqlLiteral(resolveTableComment(exist)) + "';"
                );
            } catch (Exception e) {
                log.warn("更新表注释失败: {}", exist.getTableName(), e);
            }
        }

        // 刷新截止时间逻辑：如果是循环模式且截止时间被清空了，立刻算出第一期
        if (!"DEADLINE".equalsIgnoreCase(exist.getReminderMode()) && exist.getDeadline() == null) {
            schedulerService.initOrRefreshDeadline(exist, now);
        }

        // 1.8 更新元数据
        formMapper.updateById(exist);

    }

    /**

     * 3.5 按用户汇总任务列表（待填报 / 已过期）

     * （这个方法只读不涉及 DML 和大数据量，放在这里也可由原 Service 转移而来，或者 Controller 直接调用）

     */

    public Map<String, Object> getUserTasks(String userEmail) {

        LocalDateTime now = LocalDateTime.now();

        List<DataFillForm> allForms = formMapper.selectList(null);

        Map<String, String> folderPathMap = buildFolderPathMap();

        List<Map<String, Object>> pending = new ArrayList<>();

        List<Map<String, Object>> expired = new ArrayList<>();

        List<Map<String, Object>> completed = new ArrayList<>();

        for (DataFillForm form : allForms) {

            if (!"ACTIVE".equalsIgnoreCase(form.getStatus()) && !"EXPIRED".equalsIgnoreCase(form.getStatus())) {

                continue;

            }

            // 如果表单配置了允许填报用户列表，则根据 userEmail 过滤

            if (userEmail != null && form.getFillUserEmails() != null && !form.getFillUserEmails().isBlank()) {

                try {

                    List<String> allowed = objectMapper.readValue(form.getFillUserEmails(), new TypeReference<List<String>>() {});

                    if (allowed != null && !allowed.isEmpty()) {

                        boolean match = allowed.stream().anyMatch(e -> e != null && e.equalsIgnoreCase(userEmail));

                        if (!match) {

                            continue;

                        }

                    }

                } catch (Exception ignored) {

                }

            }

            LocalDateTime deadline = form.getDeadline();

            boolean isExpired = "EXPIRED".equalsIgnoreCase(form.getStatus())

                    || (deadline != null && !now.isBefore(deadline));

            // 查询该用户最近一次填报时间

            UserFillLog lastLog = (userEmail != null && !userEmail.isBlank())
                     ? userFillLogMapper.selectLastByFormAndUser(form.getId(), userEmail)
                    : null;

            LocalDateTime lastSubmitTime = (lastLog != null && lastLog.getSubmitTime() != null) ? lastLog.getSubmitTime() : null;

            // 增强逻辑：如果日志不存在，尝试从物理表直接探测数据（解决存量导入数据不同步问题）

            if (lastSubmitTime == null && userEmail != null && !userEmail.isBlank() && form.getTableName() != null) {

                try {

                    String checkSql = String.format("SELECT MAX(w_insert_dt) FROM \"%s\" WHERE load_user = ?", form.getTableName());

                    lastSubmitTime = jdbcTemplate.queryForObject(checkSql, LocalDateTime.class, userEmail);

                } catch (Exception ignored) {

                    // 表不存在或字段缺失等异常，忽略

                }

            }

            Integer cycleDays = form.getCycleDays();
            String mode = form.getReminderMode();
            int remDays = form.getReminderDays() != null ? form.getReminderDays() : 3;
            
            // 解析提醒时间 (HH:mm)
            java.time.LocalTime rt = java.time.LocalTime.of(9, 0);
            try {
                if (form.getReminderTime() != null && !form.getReminderTime().isBlank()) {
                    String[] parts = form.getReminderTime().split(":");
                    int h = Integer.parseInt(parts[0]);
                    int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                    rt = java.time.LocalTime.of(h, m);
                }
            } catch (Exception ignored) {}

            LocalDateTime nextFillTime = null;
            LocalDateTime startTimeOfCycle = null;
            boolean completedCurrentCycle = false;

            if (cycleDays != null && cycleDays > 0 && lastSubmitTime != null) {
                nextFillTime = lastSubmitTime.plusDays(cycleDays);
                completedCurrentCycle = now.isBefore(nextFillTime);
            } else if (deadline != null && ("WEEKLY".equalsIgnoreCase(mode) || "MONTHLY".equalsIgnoreCase(mode))) {
                // 周期性任务（按周/按月）核心逻辑
                // 1. 本期开始时间 = 截止日期 - 填报窗口天数，且对齐到提醒时点
                startTimeOfCycle = deadline.minusDays(remDays).with(rt).withNano(0);
                
                // 2. 判定本期是否已完成：只要在【本期开始】之后填报过就算
                if (lastSubmitTime != null && lastSubmitTime.isAfter(startTimeOfCycle)) {
                    completedCurrentCycle = true;
                    // 3. 算出“下一期”的触发时间 (用于显示下次填报)
                    if ("WEEKLY".equalsIgnoreCase(mode)) {
                        nextFillTime = startTimeOfCycle.plusDays(7);
                    } else {
                        nextFillTime = startTimeOfCycle.plusMonths(1);
                    }
                }
            } else if (lastSubmitTime != null) {
                completedCurrentCycle = true;
            }

            long secondsLeft = 0;
            long secondsUntilStart = 0;

            if (deadline != null) {
                if (now.isBefore(deadline)) {
                    secondsLeft = java.time.Duration.between(now, deadline).getSeconds();
                }
                if (startTimeOfCycle != null && now.isBefore(startTimeOfCycle)) {
                    secondsUntilStart = java.time.Duration.between(now, startTimeOfCycle).getSeconds();
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("formId", form.getId());
            item.put("name", form.getName());
            item.put("folderId", form.getFolderId());
            item.put("folderPath", form.getFolderId() == null || form.getFolderId().isBlank()
                    ? "未分类"
                    : folderPathMap.getOrDefault(form.getFolderId(), "未分类"));
            item.put("deadline", deadline);
            item.put("status", form.getStatus());
            item.put("secondsLeft", secondsLeft);
            item.put("secondsUntilStart", secondsUntilStart); // 距开始还剩秒数
            item.put("startTimeOfCycle", startTimeOfCycle);
            item.put("nextFillTime", nextFillTime);
            item.put("lastSubmitTime", lastSubmitTime);

            if (isExpired) {
                expired.add(item);
            } else if (completedCurrentCycle) {
                completed.add(item);
            } else {
                // 细分 Pending：如果是还没到开始时间的周期任务，记为 "upcoming"
                if (startTimeOfCycle != null && now.isBefore(startTimeOfCycle)) {
                    item.put("taskStatus", "upcoming");
                } else {
                    item.put("taskStatus", "pending");
                }
                pending.add(item);
            }

        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("pending", pending);

        result.put("expired", expired);

        result.put("completed", completed);

        return result;

    }

}

