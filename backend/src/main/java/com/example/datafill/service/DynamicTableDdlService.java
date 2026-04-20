package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.dto.FieldDef;
import com.example.datafill.entity.DataFillFolder;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.entity.UserFillLog;

import com.example.datafill.mapper.DataFillFormMapper;

import com.example.datafill.mapper.UserFillLogMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import javax.sql.DataSource;
import com.example.datafill.util.SqlUtil;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;
import java.util.HashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTableDdlService {

    private final DataFillFormMapper formMapper;

    private final UserFillLogMapper userFillLogMapper;

    private final ObjectMapper objectMapper;

    private final SchedulerService schedulerService;

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("dynamicJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    private final DataFillFolderService folderService;

    private static final java.util.Set<String> RESERVED_COLUMN_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "id", "delete_flag"));

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
        String trimmed = dbType.trim().toUpperCase();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // --- 核心安全性拦截：禁止分号、注释等常见注入字符 ---
        if (trimmed.contains(";") || trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            throw new RuntimeException("非法的数据类型定义，检测到异常字符");
        }

        // 页面可用展示值：INT(10) / int10 这类值；PostgreSQL 不支持整型带长度，执行 DDL 时归一化。
        if (trimmed.matches("^INT\\s*\\(\\s*\\d+\\s*\\)$") || trimmed.matches("^INT\\s*\\d+$")) {
            return "INT";
        }
        if (trimmed.matches("^INTEGER\\s*\\(\\s*\\d+\\s*\\)$") || trimmed.matches("^INTEGER\\s*\\d+$")) {
            return "INTEGER";
        }

        // 允许标准的 PG 类型关键词及其带长度的定义 (如 VARCHAR(50), NUMERIC(10,2))
        // 使用正则确保它是一个符合词法规律的类型定义
        if (trimmed.matches("^[A-Z0-9_\\s\\(\\),]+$")) {
            return trimmed;
        }

        throw new RuntimeException("不支持的数据类型定义: " + trimmed);
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
        if (form.getTableComment() != null && !form.getTableComment().trim().isEmpty()) {
            return form.getTableComment().trim();
        }
        return form.getName();
    }

    private void validateTableName(String tableName) {
        if (tableName == null || tableName.trim().isEmpty() || !tableName.matches("^[a-zA-Z0-9_\\.]+$")) {
            throw new RuntimeException("物理表名只能包含字母、数字、下划线和点号");
        }
    }

    public List<String> getAvailableSchemas() {
        String sql = "SELECT nspname FROM pg_catalog.pg_namespace " +
                "WHERE nspname NOT LIKE 'pg_%' AND nspname != 'information_schema' " +
                "ORDER BY nspname";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private List<FieldDef> parseFields(String formsJson) {
        try {
            return objectMapper.readValue(formsJson, new TypeReference<List<FieldDef>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("字段 JSON 解析失败", e);
        }
    }

    public boolean physicalTableExists(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            schema = SqlUtil.extractSchema(table);
            if (schema == null) {
                schema = "public"; // 默认 public
            }
            table = SqlUtil.extractTable(table);
        }

        String sql = "SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, schema, table);
        return count != null && count > 0;
    }

    private String getConflictTemplateName(String schema, String table) {
        if (table == null || table.trim().isEmpty())
            return null;
        schema = (schema == null || schema.trim().isEmpty()) ? "public" : schema.trim();

        QueryWrapper<DataFillForm> qw = new QueryWrapper<DataFillForm>().eq("table_name", table.trim());
        if ("public".equalsIgnoreCase(schema)) {
            // 兼容旧记录中 schema_name 可能为 null 或为空的情况
            qw.and(i -> i.eq("schema_name", "public").or().isNull("schema_name").or().eq("schema_name", ""));
        } else {
            qw.eq("schema_name", schema);
        }
        DataFillForm exist = formMapper.selectOne(qw.last("limit 1"));
        return (exist != null) ? exist.getName() : null;
    }

    private static final String[] INSERT_AUDIT_LEXICON = { "w_insert_dt", "ctime", "create_time", "created_at",
            "insert_time" };
    private static final String[] UPDATE_AUDIT_LEXICON = { "w_update_dt", "mtime", "update_time", "updated_at" };
    private static final String[] DELETE_FLAG_LEXICON = { "delete_flag", "is_delete", "deleted", "del_flag" };

    private String detectRole(java.util.Set<String> columns, String[] lexicon) {
        for (String candidate : lexicon) {
            if (columns.contains(candidate.toLowerCase())) {
                return candidate.toLowerCase();
            }
        }
        return null;
    }

    public Map<String, Object> checkTableStatus(String schema, String table) {
        schema = (schema == null || schema.trim().isEmpty()) ? "public" : schema.trim();
        table = table == null ? "" : table.trim();

        // 1. 检查元数据是否已注册，通过冲突名称识别
        String conflictName = getConflictTemplateName(schema, table);

        // 2. 检查物理表是否存在
        boolean physicalExists = physicalTableExists(schema, table);

        Map<String, Object> res = new java.util.HashMap<>();
        res.put("metaExists", conflictName != null);
        res.put("conflictTemplateName", conflictName);
        res.put("physicalExists", physicalExists);
        res.put("schemaName", schema);
        res.put("tableName", table);

        if (physicalExists) {
            java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, table);
            List<String> missing = new ArrayList<>();

            // 1. 强制锁死主键 id
            if (!physicalColumns.contains("id")) {
                missing.add("id");
            }

            // 2. 角色识别：如果已有“长得像”的审计列，则不列入 missing
            String detectedInsert = detectRole(physicalColumns, INSERT_AUDIT_LEXICON);
            String detectedUpdate = detectRole(physicalColumns, UPDATE_AUDIT_LEXICON);
            String detectedDelete = detectRole(physicalColumns, DELETE_FLAG_LEXICON);

            if (detectedInsert == null) {
                missing.add("w_insert_dt");
            }
            if (detectedUpdate == null) {
                missing.add("w_update_dt");
            }
            if (detectedDelete == null) {
                missing.add("delete_flag");
            }

            res.put("detectedInsertDt", detectedInsert);
            res.put("detectedUpdateDt", detectedUpdate);
            res.put("detectedDeleteFlag", detectedDelete);

            // 如果 id 存在但类型是 int8/bigint，也列入 missing 触发修复
            if (physicalColumns.contains("id")) {
                String idType = getIdType(schema, table);
                if ("int8".equalsIgnoreCase(idType) || "bigint".equalsIgnoreCase(idType)) {
                    if (!missing.contains("id")) {
                        missing.add("id");
                    }
                }
            }
        }
        return res;
    }

    private String getIdType(String schema, String table) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT udt_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = 'id'",
                    String.class, schema, table);
        } catch (Exception e) {
            return null;
        }
    }

    private java.util.Set<String> loadPhysicalColumns(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            schema = SqlUtil.extractSchema(table);
            if (schema == null) {
                schema = "public";
            }
            table = SqlUtil.extractTable(table);
        }

        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?";
        List<String> columns = jdbcTemplate.queryForList(sql, String.class, schema, table);
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
            form.setReminderDays(3.0);
        }
        if (form.getReminderMode() == null || form.getReminderMode().trim().isEmpty()) {
            form.setReminderMode("DEADLINE");
        }
        if (form.getReminderTime() == null || form.getReminderTime().trim().isEmpty()) {
            form.setReminderTime("09:00");
        }
        if (form.getHardDelete() == null) {
            form.setHardDelete(false);
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
                if (current.getName() != null && !current.getName().trim().isEmpty()) {
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
     * 
     * 1. 保存表单配置并动态物理建表
     * 
     */

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)

    public String createFormAndTable(DataFillForm form) {

        // 1. 检查物理表名是否重复 (Schema 粒度, 含公共模式兼容)
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName()
                : "public";
        form.setSchemaName(schema); // 确保元数据中 schema_name 不为 null
        String conflictName = getConflictTemplateName(schema, form.getTableName());
        if (conflictName != null) {
            throw new RuntimeException("物理表 [" + schema + "." + form.getTableName() + "] 已被模板「" + conflictName
                    + "」占用，请重命名表名或更换模式(Schema)！");
        }

        // 2. 解析前端传来的字段 JSON

        List<FieldDef> fields = parseFields(form.getForms());

        // 简单校验一下表名，必须是英文字母数字下划线

        validateTableName(form.getTableName());

        // 3. 拼接 PostgreSQL 建表 DDL 语句
        String fullTableName = schema + "." + form.getTableName();

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(SqlUtil.quoteTable(fullTableName)).append(" ( ");

        // 强制带上主键ID字段 (使用标准 IDENTITY 模式，确保工具显示为 int4/integer)
        ddl.append("\"id\" INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, ");

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

        ddl.append("\"delete_flag\" BOOLEAN DEFAULT FALSE");

        ddl.append(" );");

        // 4. 执行建表原生 SQL
        try {
            // 调试日志：确认当前使用的数据库到底是什么
            try (java.sql.Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                String dbUrl = conn.getMetaData().getURL();
                log.info("======== [DEBUG] 正在执行建表，目标物理数据库连为: {} ========", dbUrl);

                // 探测冲突的关系到底是什么（表、视图、还是索引？）
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT relkind, nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE relname = ? AND nspname = ?")) {
                    ps.setString(1, form.getTableName());
                    ps.setString(2, schema);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            log.warn("!!!! 发现重名关系存在 !!!! 名称: {}, 类型(relkind): {}, 所在模式(schema): {}",
                                    form.getTableName(), rs.getString("relkind"), rs.getString("relnamespace"));
                        }
                    }
                }
            } catch (Exception ex) {
                log.error("尝试获取数据库元数据失败", ex);
            }

            jdbcTemplate.execute(ddl.toString());
        } catch (Exception e) {
            // 把关键的 SQL 错误信息透传到前端，便于管理员直接定位（例如 [42704] 类型不存在）
            Throwable cause = e.getCause();
            if (cause instanceof org.postgresql.util.PSQLException) {
                org.postgresql.util.PSQLException pe = (org.postgresql.util.PSQLException) cause;
                String posText = buildPgErrorPositionText(pe);
                throw new RuntimeException("建表失败: [" + pe.getSQLState() + "] " + pe.getMessage() + posText);
            }
            throw new RuntimeException("建表失败: " + e.getMessage());
        }

        // 添加表注释和字段注释
        // 注释里的单引号要转义防注入
        try {
            jdbcTemplate.execute("COMMENT ON TABLE " + SqlUtil.quoteTable(fullTableName) + " IS '"
                    + escapeSqlLiteral(resolveTableComment(form)) + "';");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof org.postgresql.util.PSQLException) {
                org.postgresql.util.PSQLException pe = (org.postgresql.util.PSQLException) cause;
                String posText = buildPgErrorPositionText(pe);
                throw new RuntimeException("建表注释失败: [" + pe.getSQLState() + "] " + pe.getMessage() + posText);
            }
            throw new RuntimeException("建表注释失败: " + e.getMessage());
        }

        for (FieldDef field : fields) {

            try {
                jdbcTemplate.execute("COMMENT ON COLUMN " + SqlUtil.quoteTable(fullTableName) + ".\""
                        + field.getColumnName() + "\" IS '" + escapeSqlLiteral(field.getName()) + "';");
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
        form.setIsExternal(false); // 系统创建的表，非外部绑定
        
        // 设置标准审计列名
        form.setInsertDtColumn("w_insert_dt");
        form.setUpdateDtColumn("w_update_dt");
        form.setDeleteFlagColumn("delete_flag");
        
        formMapper.insert(form);

        return form.getId();

    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public String bindExistingTable(DataFillForm form) {
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName()
                : "public";
        form.setSchemaName(schema); // 确保元数据中 schema_name 不为 null
        String conflictName = getConflictTemplateName(schema, form.getTableName());
        if (conflictName != null) {
            throw new RuntimeException("物理表 [" + schema + "." + form.getTableName() + "] 已被模板「" + conflictName
                    + "」占用，请重命名表名或更换模式(Schema)！");
        }

        validateTableName(form.getTableName());
        if (!physicalTableExists(schema, form.getTableName())) {
            throw new RuntimeException("指定的物理表不存在: " + schema + "." + form.getTableName());
        }

        List<FieldDef> fields = parseFields(form.getForms());
        if (fields == null || fields.isEmpty()) {
            throw new RuntimeException("请先从已有表识别字段结构");
        }

        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

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
        
        // [后端强强制规范] 锁定主键为 id
        form.setPkColumn("id");
        if (!physicalColumns.contains("id")) {
            // 注意：这里不再报错提示缺少 id，而是交由 checkTableStatus 展示警告横幅，引导用户点击“一键补齐”
            log.warn("绑定物理表 {} 成功，但检测到缺少标准主键 id，部分 DML 功能可能受限", form.getTableName());
        }

        // 角色识别与保存
        String detectedInsert = detectRole(physicalColumns, INSERT_AUDIT_LEXICON);
        String detectedUpdate = detectRole(physicalColumns, UPDATE_AUDIT_LEXICON);
        String detectedDelete = detectRole(physicalColumns, DELETE_FLAG_LEXICON);

        form.setInsertDtColumn(detectedInsert);
        form.setUpdateDtColumn(detectedUpdate);
        form.setDeleteFlagColumn(detectedDelete);

        if (detectedDelete == null && !Boolean.TRUE.equals(form.getHardDelete())) {
            log.warn("绑定物理表 {} 成功，但检测到缺少 delete_flag，软删除功能将受限", form.getTableName());
        }

        applyFormDefaultsForMetadata(form);
        form.setIsExternal(true); // 外部绑定表
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
     * 
     * 1.5 删除表单及其物理表（改为软删除重命名方式）
     * 
     */

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void deleteFormAndTable(String formId, boolean dropTable) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null)
            return;

        // 仅当“非外部绑定表”时，才尝试对物理表执行相关删除/备份操作
        boolean isExt = (form.getIsExternal() != null && form.getIsExternal());

        if (!isExt) {
            String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty())
                    ? form.getSchemaName()
                    : "public";
            String fullTable = schema + "." + form.getTableName();

            try {
                if (physicalTableExists(schema, form.getTableName())) {
                    if (dropTable) {
                        log.info("检测到表单 {} 为系统创建，且管理员要求彻底销毁，执行 DROP TABLE {}", form.getName(), fullTable);
                        jdbcTemplate.execute("DROP TABLE " + SqlUtil.quoteTable(fullTable));
                    } else {
                        long timestamp = System.currentTimeMillis();
                        String newTableName = SqlUtil.extractTable(form.getTableName()) + "_del_" + timestamp;
                        log.info("检测到表单 {} 为系统创建，执行表重命名备份 {} -> {}", form.getName(), fullTable, newTableName);
                        jdbcTemplate.execute(
                                "ALTER TABLE " + SqlUtil.quoteTable(fullTable) + " RENAME TO \"" + newTableName + "\"");
                    }
                } else {
                    log.warn("物理表 {} 不存在，跳过物理删除/备份步骤", fullTable);
                }
            } catch (Exception e) {
                log.error("操作物理表 {} 失败", fullTable, e);
            }
        } else {
            log.info("检测到表单 {} 为外部绑定表，仅删除元数据，不触碰物理表", form.getName());
        }

        // 删除元数据记录
        formMapper.deleteById(formId);
    }

    /**
     * 
     * 1.6 更新表单元数据（不修改物理表结构）
     * 
     */

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)

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
            exist.setReminderDateTime(incoming.getReminderDateTime());
        } else {
            // 循环模式下清空旧截止时间，强制重新推演计算
            exist.setDeadline(null);
            exist.setDeadlineMonthlyDay(incoming.getDeadlineMonthlyDay());
            exist.setDeadlineWeeklyDayOfWeek(incoming.getDeadlineWeeklyDayOfWeek());
            exist.setDeadlineTime(incoming.getDeadlineTime());
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

        if (incoming.getSchemaName() != null) {
            exist.setSchemaName(incoming.getSchemaName());
        }

        if (incoming.getHardDelete() != null) {
            exist.setHardDelete(incoming.getHardDelete());
        }

        if (incoming.getGroupTag() != null || incoming.getGroupTag() == null) {
            exist.setGroupTag(incoming.getGroupTag());
        }

        if (incoming.getDescription() != null || incoming.getDescription() == null) {
            exist.setDescription(incoming.getDescription());
        }

        if (incoming.getAllowAdd() != null) {
            exist.setAllowAdd(incoming.getAllowAdd());
        }
        if (incoming.getAllowEdit() != null) {
            exist.setAllowEdit(incoming.getAllowEdit());
        }
        if (incoming.getAllowDelete() != null) {
            exist.setAllowDelete(incoming.getAllowDelete());
        }

        // 重新计算截止时间（如果是循环模式）
        schedulerService.initOrRefreshDeadline(exist, now);

        // 1.7 [新增/修改逻辑]: 处理字段变更（支持新增列、修改中文名、重命名列、修改物理类型）
        if (incoming.getForms() != null) {
            try {
                List<FieldDef> newFields = objectMapper.readValue(incoming.getForms(),
                        new TypeReference<List<FieldDef>>() {
                        });
                List<FieldDef> oldFields = objectMapper.readValue(exist.getForms(),
                        new TypeReference<List<FieldDef>>() {
                        });

                String schema = (exist.getSchemaName() != null && !exist.getSchemaName().trim().isEmpty())
                        ? exist.getSchemaName()
                        : "public";
                String fullTableName = schema + "." + exist.getTableName();

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
                            (nf.getOriginalColumnName() == null || nf.getOriginalColumnName().trim().isEmpty())
                                    ? colName
                                    : nf.getOriginalColumnName());
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
                    if (colName == null)
                        continue;

                    String originalColName = nf.getOriginalColumnName();
                    FieldDef of = originalColName == null ? null : oldFieldMap.get(originalColName.toLowerCase());
                    if (of == null) {
                        // A: 发现新字段 -> 执行 ALTER TABLE ADD COLUMN
                        log.info("表单 {} 检测到新字段 {}, 准备执行物理加列", exist.getName(), colName);
                        StringBuilder addColSql = new StringBuilder();
                        addColSql.append("ALTER TABLE ").append(SqlUtil.quoteTable(fullTableName))
                                .append(" ADD COLUMN \"").append(colName).append("\" ");

                        String dbTypeArg = nf.getDbType();
                        if (dbTypeArg != null && !dbTypeArg.trim().isEmpty()) {
                            addColSql.append(normalizeDbTypeForPostgres(dbTypeArg));
                        } else {
                            addColSql.append("VARCHAR(255)");
                        }

                        if (nf.getRequired() != null && nf.getRequired()) {
                            addColSql.append(" DEFAULT ''"); // 生产环境 ADD COLUMN NOT NULL 建议带 DEFAULT
                        }

                        jdbcTemplate.execute(addColSql.toString());
                        // 同步添加注释
                        jdbcTemplate.execute("COMMENT ON COLUMN " + SqlUtil.quoteTable(fullTableName) + ".\"" + colName
                                + "\" IS '" + escapeSqlLiteral(nf.getName()) + "';");
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
                            jdbcTemplate.execute("ALTER TABLE " + SqlUtil.quoteTable(fullTableName)
                                    + " RENAME COLUMN \"" + physicalColName + "\" TO \"" + colName + "\"");
                            physicalColName = colName;
                        }

                        String oldDbType = of.getDbType() == null ? "" : normalizeDbTypeForPostgres(of.getDbType());
                        String newDbType = nf.getDbType() == null ? "" : normalizeDbTypeForPostgres(nf.getDbType());
                        if (!newDbType.trim().isEmpty() && !newDbType.equalsIgnoreCase(oldDbType)) {
                            log.info("表单 {} 字段 {} 类型由 {} 改为 {}", exist.getName(), physicalColName, oldDbType,
                                    newDbType);
                            jdbcTemplate.execute(
                                    "ALTER TABLE " + SqlUtil.quoteTable(fullTableName) + " ALTER COLUMN \""
                                            + physicalColName + "\" TYPE " + newDbType
                                            + " USING \"" + physicalColName + "\"::" + newDbType);
                        }

                        // B: 现有字段 -> 检查名称是否改变，更新备注
                        if (!java.util.Objects.equals(nf.getName(), of.getName())) {
                            log.info("表单 {} 字段 {} 名称由 {} 改为 {}, 更新备注", exist.getName(), colName, of.getName(),
                                    nf.getName());
                        }
                        if (!java.util.Objects.equals(nf.getName(), of.getName())
                                || !colName.equalsIgnoreCase(of.getColumnName())) {
                            jdbcTemplate.execute("COMMENT ON COLUMN " + SqlUtil.quoteTable(fullTableName) + ".\""
                                    + physicalColName + "\" IS '" + escapeSqlLiteral(nf.getName()) + "';");
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
                && exist.getTableName() != null) {
            String schema = (exist.getSchemaName() != null && !exist.getSchemaName().trim().isEmpty())
                    ? exist.getSchemaName()
                    : "public";
            if (physicalTableExists(schema, exist.getTableName())) {
                try {
                    jdbcTemplate.execute(
                            "COMMENT ON TABLE " + SqlUtil.quoteTable(schema + "." + exist.getTableName()) + " IS '"
                                    + escapeSqlLiteral(resolveTableComment(exist)) + "';");
                } catch (Exception e) {
                    log.warn("更新表注释失败: {}", exist.getTableName(), e);
                }
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
     * 
     * 3.5 按用户汇总任务列表（待填报 / 已过期）
     * 
     * （这个方法只读不涉及 DML 和大数据量，放在这里也可由原 Service 转移而来，或者 Controller 直接调用）
     * 
     */

    public Map<String, Object> getUserTasks(String userEmail, String groupTag, boolean isAdmin) {
        LocalDateTime now = LocalDateTime.now();
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFillForm> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        // 性能优化：列表查询排除大字段
        qw.select(DataFillForm.class, i -> !i.getColumn().equals("forms") 
                && !i.getColumn().equals("kv_config") 
                && !i.getColumn().equals("reference_template_config"));
        if (groupTag != null && !groupTag.trim().isEmpty()) {
            qw.eq("group_tag", groupTag.trim());
        }
        List<DataFillForm> allForms = formMapper.selectList(qw);

        // 批量获取该用户的所有填报日志，解决 N+1 问题
        Map<String, UserFillLog> lastLogMap = new HashMap<>();
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            List<UserFillLog> logs = userFillLogMapper.selectLastUploadsByUser(userEmail);
            for (UserFillLog log : logs) {
                lastLogMap.put(log.getFormId(), log);
            }
        }

        Map<String, String> folderPathMap = buildFolderPathMap();
        List<Map<String, Object>> pending = new ArrayList<>();
        List<Map<String, Object>> expired = new ArrayList<>();
        List<Map<String, Object>> completed = new ArrayList<>();

        // 收集需要动态探测物理表的表单（即没有填报日志记录的表单）
        List<DataFillForm> formsToProbe = new ArrayList<>();
        
        // 第一遍循环：过滤权限和分类，并确定哪些需要探测
        List<DataFillForm> filteredForms = new ArrayList<>();
        for (DataFillForm form : allForms) {
            String status = form.getStatus();
            if (!"ACTIVE".equalsIgnoreCase(status) && !"EXPIRED".equalsIgnoreCase(status)) {
                continue;
            }

            // 权限校验逻辑
            if (!isAdmin) {
                String fillUserEmails = form.getFillUserEmails();
                if (fillUserEmails == null || fillUserEmails.trim().isEmpty() || "[]".equals(fillUserEmails.trim())) {
                    continue;
                }
                try {
                    List<String> allowed = objectMapper.readValue(fillUserEmails, new TypeReference<List<String>>() {});
                    if (allowed == null || allowed.isEmpty() || allowed.stream().noneMatch(e -> e != null && e.trim().equalsIgnoreCase(userEmail.trim()))) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
            }
            filteredForms.add(form);
            
            if (!lastLogMap.containsKey(form.getId()) && userEmail != null && !userEmail.trim().isEmpty() && form.getTableName() != null) {
                formsToProbe.add(form);
            }
        }

        // 批量探测物理表中的 MAX(...)，适配外部识别表的自定义时间字段
        Map<String, LocalDateTime> probeMap = new HashMap<>();
        if (!formsToProbe.isEmpty()) {
            for (DataFillForm pf : formsToProbe) {
                try {
                    // 获取模板定义的插入时间字段名，若无则默认为 w_insert_dt
                    String col = (pf.getInsertDtColumn() != null && !pf.getInsertDtColumn().trim().isEmpty()) 
                                 ? pf.getInsertDtColumn().trim() : "w_insert_dt";
                    
                    String checkSql = String.format("SELECT MAX(\"%s\") FROM \"%s\" WHERE load_user = ?", col, pf.getTableName());
                    LocalDateTime ldt = jdbcTemplate.queryForObject(checkSql, LocalDateTime.class, userEmail);
                    if (ldt != null) {
                        probeMap.put(pf.getId(), ldt);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 第二遍循环：组装结果
        for (DataFillForm form : filteredForms) {
            LocalDateTime deadline = form.getDeadline();
            boolean isExpired = "EXPIRED".equalsIgnoreCase(form.getStatus()) || (deadline != null && !now.isBefore(deadline));

            // 获取最后填报时间：优先查日志 Map，其次查探测 Map
            UserFillLog lastLog = lastLogMap.get(form.getId());
            LocalDateTime lastSubmitTime = (lastLog != null) ? lastLog.getSubmitTime() : probeMap.get(form.getId());

            Integer cycleDays = form.getCycleDays();
            String mode = form.getReminderMode();
            double remDays = form.getReminderDays() != null ? form.getReminderDays() : 3.0;

            java.time.LocalTime rt = java.time.LocalTime.of(9, 0);
            try {
                if (form.getReminderTime() != null && !form.getReminderTime().trim().isEmpty()) {
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
                startTimeOfCycle = deadline.minusHours((long) (remDays * 24)).with(rt).withNano(0);
                if (lastSubmitTime != null && lastSubmitTime.isAfter(startTimeOfCycle)) {
                    completedCurrentCycle = true;
                    if ("WEEKLY".equalsIgnoreCase(mode)) {
                        nextFillTime = startTimeOfCycle.plusDays(7);
                    } else {
                        nextFillTime = startTimeOfCycle.plusMonths(1);
                    }
                }
            } else if (lastSubmitTime != null) {
                completedCurrentCycle = true;
            }

            Long secondsLeft = null;
            Long secondsUntilStart = null;
            if (deadline != null) {
                if (now.isBefore(deadline)) {
                    secondsLeft = java.time.Duration.between(now, deadline).getSeconds();
                } else {
                    secondsLeft = -1L; // 明确表示已过期
                }
            }
            if (startTimeOfCycle != null && now.isBefore(startTimeOfCycle)) {
                secondsUntilStart = java.time.Duration.between(now, startTimeOfCycle).getSeconds();
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("formId", form.getId());
            item.put("name", form.getName());
            item.put("folderId", form.getFolderId());
            item.put("folderPath", form.getFolderId() == null || form.getFolderId().isBlank() ? "默认" : folderPathMap.getOrDefault(form.getFolderId(), "默认"));
            item.put("deadline", deadline);
            item.put("status", form.getStatus());
            item.put("secondsLeft", secondsLeft);
            item.put("secondsUntilStart", secondsUntilStart);
            item.put("startTimeOfCycle", startTimeOfCycle);
            item.put("nextFillTime", nextFillTime);
            item.put("lastSubmitTime", lastSubmitTime);

            if (isExpired) {
                // 如果已过期但用户在过期前（或周期内）完成了填报，则进“已完成”列表；否则进“已过期”
                if (completedCurrentCycle) {
                    completed.add(item);
                } else {
                    expired.add(item);
                }
            } else if (completedCurrentCycle) {
                completed.add(item);
            } else {
                item.put("taskStatus", (startTimeOfCycle != null && now.isBefore(startTimeOfCycle)) ? "upcoming" : "pending");
                pending.add(item);
            }
        }


        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("expired", expired);
        result.put("completed", completed);
        return result;
    }


    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public Map<String, Object> repairTable(String formId, List<String> columnsToAdd) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        return repairTableByName(schema, form.getTableName(), columnsToAdd);
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public Map<String, Object> repairTableByName(String schema, String tableName, List<String> columnsToAdd) {
        String fullTableName = (schema == null || schema.isEmpty() ? "public" : schema) + "." + tableName;
        
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, tableName);
        List<String> success = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String col : columnsToAdd) {
            String lowerCol = col.toLowerCase();
            String fullTable = SqlUtil.quoteTable(fullTableName);

            if ("id".equals(lowerCol) && physicalColumns.contains("id")) {
                // 如果 id 已经存在，检查是否需要从 bigint/int8 降级为 int4/serial
                String idType = getIdType(schema, tableName);
                if ("int8".equalsIgnoreCase(idType) || "bigint".equalsIgnoreCase(idType)) {
                    try {
                        jdbcTemplate.execute("ALTER TABLE " + fullTable + " ALTER COLUMN \"id\" TYPE int4");
                        success.add(col + " (类型修复)");
                        continue;
                    } catch (Exception e) {
                        log.error("修复 id 类型失败", e);
                        failed.add(col + " (类型修复失败): " + e.getMessage());
                        continue;
                    }
                }
            }

            if (physicalColumns.contains(lowerCol)) continue;
            
            try {
                StringBuilder sql = new StringBuilder("ALTER TABLE " + fullTable + " ADD COLUMN \"" + lowerCol + "\" ");
                if ("id".equals(lowerCol)) {
                    // 使用标准 IDENTITY 模式，确保工具显示为 int4/integer
                    sql.append("INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY");
                    jdbcTemplate.execute(sql.toString());
                    success.add(col);
                    continue;
                } else if ("delete_flag".equals(lowerCol)) {
                    sql.append("BOOLEAN DEFAULT FALSE");
                } else if ("w_insert_dt".equals(lowerCol) || "w_update_dt".equals(lowerCol)) {
                    sql.append("TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                } else if ("load_user".equals(lowerCol)) {
                    sql.append("VARCHAR(100)");
                } else if ("job_instance".equals(lowerCol)) {
                    sql.append("VARCHAR(80)");
                } else if ("extra_data".equals(lowerCol)) {
                    sql.append("JSONB DEFAULT '{}'");
                } else {
                    sql.append("VARCHAR(255)");
                }
                jdbcTemplate.execute(sql.toString());
                success.add(col);
            } catch (Exception e) {
                log.error("补齐列 {} 失败", col, e);
                failed.add(col + ": " + e.getMessage());
            }
        }
        
        Map<String, Object> res = new HashMap<>();
        res.put("success", success);
        res.put("failed", failed);
        return res;
    }
}
