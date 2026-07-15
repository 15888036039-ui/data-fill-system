package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.dto.FieldDef;
import com.example.datafill.entity.DataFillFolder;

import com.example.datafill.entity.DataFillForm;
import com.example.datafill.entity.UserCompletionSnapshot;
import com.example.datafill.entity.UserFillLog;
import com.example.datafill.mapper.DataFillFormMapper;
import com.example.datafill.mapper.UserCompletionSnapshotMapper;
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
    private final UserCompletionSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final UserService userService;

    private final SchedulerService schedulerService;

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("dynamicJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    private final DataFillFolderService folderService;

    private static final java.util.Set<String> RESERVED_COLUMN_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "id", "delete_flag", "load_user", "w_insert_dt", "w_update_dt", "extra_data"
    ));

    private boolean isReservedColumnName(String columnName) {
        return columnName != null && RESERVED_COLUMN_NAMES.contains(columnName.toLowerCase());
    }

    private boolean isSystemManagedColumn(String columnName) {
        return isReservedColumnName(columnName);
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
        return java.util.Arrays.asList("ods", "dim");
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
                schema = "ods"; // 默认 ods
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
        schema = (schema == null || schema.trim().isEmpty()) ? "ods" : schema.trim();

        QueryWrapper<DataFillForm> qw = new QueryWrapper<DataFillForm>().eq("table_name", table.trim());
        if ("ods".equalsIgnoreCase(schema)) {
            // 兼容旧记录中 schema_name 可能为 null 或为空的情况
            qw.and(i -> i.eq("schema_name", "ods").or().isNull("schema_name").or().eq("schema_name", ""));
        } else {
            qw.eq("schema_name", schema);
        }
        DataFillForm exist = formMapper.selectOne(qw.last("limit 1"));
        return (exist != null) ? exist.getName() : null;
    }

    private static final String[] INSERT_AUDIT_LEXICON = { "w_insert_dt", "create_time", "created_at",
            "insert_time" };
    private static final String[] UPDATE_AUDIT_LEXICON = { "w_update_dt", "update_time", "updated_at" };
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
        // 统一默认 schema 为 public，与前端和 PG 习惯保持一致
        schema = (schema == null || schema.trim().isEmpty()) ? "public" : schema.trim();
        table = table == null ? "" : table.trim();

        // 1. 检查元数据是否已注册
        DataFillForm exist = null;
        String conflictName = null;
        if (!table.isEmpty()) {
            QueryWrapper<DataFillForm> qwForm = new QueryWrapper<DataFillForm>().eq("table_name", table);
            // 如果是 public，兼容 null 或空字符串的 schema 记录
            if ("public".equalsIgnoreCase(schema)) {
                qwForm.and(i -> i.eq("schema_name", "public").or().isNull("schema_name").or().eq("schema_name", ""));
            } else {
                qwForm.eq("schema_name", schema);
            }
            exist = formMapper.selectOne(qwForm.last("limit 1"));
        }
        conflictName = (exist != null) ? exist.getName() : null;

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
            List<String> missingSystemCols = new ArrayList<>();

            // 1. 角色识别与核心审计列校验
            String detectedInsert = detectRole(physicalColumns, INSERT_AUDIT_LEXICON);
            String detectedUpdate = detectRole(physicalColumns, UPDATE_AUDIT_LEXICON);
            String detectedDelete = detectRole(physicalColumns, DELETE_FLAG_LEXICON);

            if (!physicalColumns.contains("id")) missingSystemCols.add("id");
            if (detectedInsert == null) missingSystemCols.add("w_insert_dt");
            if (detectedUpdate == null) missingSystemCols.add("w_update_dt");
            if (detectedDelete == null) missingSystemCols.add("delete_flag");
            if (!physicalColumns.contains("load_user")) missingSystemCols.add("load_user");

            res.put("missingColumns", missingSystemCols);
            res.put("detectedInsertDt", detectedInsert);
            res.put("detectedUpdateDt", detectedUpdate);
            res.put("detectedDeleteFlag", detectedDelete);

            // 2. 检测业务列差异 (仅针对已注册的表单进行元数据对比)
            if (exist != null && exist.getForms() != null) {
                try {
                    List<FieldDef> metaFields = objectMapper.readValue(exist.getForms(), new TypeReference<List<FieldDef>>() {});
                    java.util.Set<String> metaColumnNames = new java.util.HashSet<>();
                    for (FieldDef f : metaFields) {
                        if (f.getColumnName() != null) metaColumnNames.add(f.getColumnName().toLowerCase());
                    }

                    List<String> untrackedCols = new ArrayList<>();
                    for (String pc : physicalColumns) {
                        String lpc = pc.toLowerCase();
                        if (!isSystemManagedColumn(lpc) && !metaColumnNames.contains(lpc)) {
                            untrackedCols.add(pc);
                        }
                    }
                    res.put("untrackedBusinessColumns", untrackedCols);

                    List<String> missingFromPk = new ArrayList<>();
                    for (FieldDef f : metaFields) {
                        String lmn = f.getColumnName() == null ? "" : f.getColumnName().toLowerCase();
                        if (!lmn.isEmpty() && !isSystemManagedColumn(lmn) && !physicalColumns.contains(lmn)) {
                            missingFromPk.add(f.getColumnName());
                        }
                    }
                    res.put("missingBusinessColumns", missingFromPk);
                } catch (Exception e) {
                    log.warn("对比元数据列失败: {}", e.getMessage());
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

    private boolean checkIndexExists(String schema, String table, String columnName) {
        try {
            String sql = "SELECT count(1) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexdef LIKE ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, schema, table, "%(\"" + columnName + "\")%");
            if (count == null || count == 0) {
                 // 兼容不带引号的情况
                 count = jdbcTemplate.queryForObject(sql, Integer.class, schema, table, "%(" + columnName + ")%");
            }
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private java.util.Set<String> loadPhysicalColumns(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            schema = SqlUtil.extractSchema(table);
            if (schema == null) {
                schema = "ods";
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
                : "ods";
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
        ddl.append("\"id\" INT4 GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, ");

        java.util.Set<String> seenNames = new java.util.HashSet<>();

        for (FieldDef field : fields) {

            String colName = normalizeColumnName(field.getColumnName());
            field.setColumnName(colName);
            if (colName == null || !colName.matches("^[a-zA-Z0-9_]+$")) {
                throw new RuntimeException("列名只能包含字母、数字和下划线: " + colName);
            }
            if (isReservedColumnName(colName)) {
                // 允许保留字段出现在字段列表中（用于视觉提示或查阅配置），但不需要为其生成额外的物理列 DDL，
                // 因为下文会统一手动追加这些标准审计列。
                continue;
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
        ddl.append("\"delete_flag\" BOOLEAN DEFAULT FALSE NOT NULL");

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
            
            // 为 load_user 增加索引，优化状态探测查询性能
            try {
                jdbcTemplate.execute(String.format("CREATE INDEX ON %s (\"load_user\")", SqlUtil.quoteTable(fullTableName)));
            } catch (Exception e) {
                log.warn("创建 load_user 索引失败（可能已存在或不支持）: {}", e.getMessage());
            }
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
                : "ods";
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
        
        // [后端主键绑定] 优先使用前端传入的主键列，无则默认使用 id
        if (form.getPkColumn() == null || form.getPkColumn().trim().isEmpty()) {
            form.setPkColumn("id");
        } else {
            form.setPkColumn(form.getPkColumn().trim());
        }
        if (!physicalColumns.contains(form.getPkColumn().toLowerCase())) {
            // 注意：这里不再报错提示缺少主键，而是交由 checkTableStatus 展示警告横幅
            log.warn("绑定物理表 {} 成功，但检测到缺少标准主键 {}，部分 DML 功能可能受限", form.getTableName(), form.getPkColumn());
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
                    : "ods";
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

        exist.setMonthlyDay(incoming.getMonthlyDay());
        exist.setWeeklyDayOfWeek(incoming.getWeeklyDayOfWeek());
        exist.setRecipientEmails(incoming.getRecipientEmails());
        exist.setFillUserEmails(incoming.getFillUserEmails());
        exist.setCycleDays(incoming.getCycleDays());
        exist.setFolderId(incoming.getFolderId());
        exist.setTableComment(incoming.getTableComment());
        // 提醒时间允许前端传 null 表示使用默认值
        exist.setReminderTime(incoming.getReminderTime());

        LocalDateTime now = LocalDateTime.now();

        exist.setUpdateTime(now);

        if (incoming.getSchemaName() != null) {
            exist.setSchemaName(incoming.getSchemaName());
        }

        if (incoming.getHardDelete() != null) {
            exist.setHardDelete(incoming.getHardDelete());
        }

        exist.setGroupTag(incoming.getGroupTag());
        exist.setDescription(incoming.getDescription());
        exist.setDefaultFilterPolicy(incoming.getDefaultFilterPolicy());

        if (incoming.getAllowAdd() != null) {
            exist.setAllowAdd(incoming.getAllowAdd());
        }
        if (incoming.getAllowEdit() != null) {
            exist.setAllowEdit(incoming.getAllowEdit());
        }
        if (incoming.getAllowDelete() != null) {
            exist.setAllowDelete(incoming.getAllowDelete());
        }
        if (incoming.getAllowExport() != null) {
            exist.setAllowExport(incoming.getAllowExport());
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

                java.util.Set<String> physicalCols = loadPhysicalColumns(schema, exist.getTableName());

                Map<String, String> dbNullableMap = new HashMap<>();
                try {
                    String nullableSql = "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = ? AND table_name = ?";
                    List<Map<String, Object>> colRows = jdbcTemplate.queryForList(nullableSql, schema, exist.getTableName());
                    for (Map<String, Object> colRow : colRows) {
                        String cn = (String) colRow.get("column_name");
                        String isNullable = (String) colRow.get("is_nullable");
                        if (cn != null && isNullable != null) {
                            dbNullableMap.put(cn.toLowerCase(), isNullable);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("无法加载物理表 {} 的列非空属性: {}", exist.getTableName(), ex.getMessage());
                }

                for (FieldDef nf : newFields) {
                    String colName = nf.getColumnName();
                    if (colName == null)
                        continue;

                    String originalColName = nf.getOriginalColumnName();
                    FieldDef of = originalColName == null ? null : oldFieldMap.get(originalColName.toLowerCase());
                    if (of == null) {
                        // A: 发现新字段 -> 先判断物理库是否已经存在同名列（防止重复加列导致语法错误）
                        if (isReservedColumnName(colName)) {
                            // 系统列物理表通常已存在，此处仅同步元数据，跳过物理加列
                            continue;
                        }

                        if (physicalCols.contains(colName.toLowerCase())) {
                            log.info("表单 {} 字段 {} 已存在于物理表中，无需执行加列，仅同步元数据与注释", exist.getName(), colName);
                        } else {
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
                            physicalCols.add(colName.toLowerCase()); // 更新本地副本，防止后续冲突
                        }
                        
                        // 同步加列后或已存在同名列时，均执行注释同步
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
                            // 核心增强：防止外部先改了数据库导致此处 RENAME 报错
                            // 如果物理库里【已经】是新名字了，且【不存在】旧名字，说明库里已经同步过了，跳过 DDL 报错
                            boolean oldExists = physicalCols.contains(physicalColName.toLowerCase());
                            boolean newExists = physicalCols.contains(colName.toLowerCase());

                            if (!oldExists && newExists) {
                                log.info("表单 {} 字段 {} 似乎已在外部重命名为 {}, 跳过 DDL", exist.getName(), physicalColName, colName);
                            } else {
                                log.info("表单 {} 字段 {} 重命名为 {}", exist.getName(), physicalColName, colName);
                                jdbcTemplate.execute("ALTER TABLE " + SqlUtil.quoteTable(fullTableName)
                                        + " RENAME COLUMN \"" + physicalColName + "\" TO \"" + colName + "\"");
                            }
                            
                            // 同步更新参考模板配置中的映射关系，防止下载模板时列丢失或表头变化
                            if (exist.getReferenceTemplateConfig() != null && !exist.getReferenceTemplateConfig().trim().isEmpty()) {
                                try {
                                    Map<String, Object> refConfig = objectMapper.readValue(exist.getReferenceTemplateConfig(), new TypeReference<Map<String, Object>>() {});
                                    Object mappingsObj = refConfig.get("headerMappings");
                                    if (mappingsObj instanceof List<?>) {
                                        List<Map<String, Object>> mappings = (List<Map<String, Object>>) mappingsObj;
                                        boolean changed = false;
                                        for (Map<String, Object> m : mappings) {
                                            if (physicalColName.equalsIgnoreCase(String.valueOf(m.get("columnName")))) {
                                                m.put("columnName", colName);
                                                changed = true;
                                            }
                                        }
                                        if (changed) {
                                            exist.setReferenceTemplateConfig(objectMapper.writeValueAsString(refConfig));
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("同步更新 referenceTemplateConfig 失败: {}", e.getMessage());
                                }
                            }
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

                        // C: 检查并更新必填属性 (required) 到数据库实际的非空约束
                        boolean newRequired = nf.getRequired() != null && nf.getRequired();
                        String dbIsNullable = dbNullableMap.get(physicalColName.toLowerCase());
                        if (dbIsNullable != null) {
                            boolean dbNotNull = "NO".equalsIgnoreCase(dbIsNullable);
                            if (dbNotNull != newRequired) {
                                log.info("表单 {} 字段 {} 数据库实际非空约束为 {}, 期望约束为 {}", exist.getName(), physicalColName, dbNotNull, newRequired);
                                String alterRequiredSql = "ALTER TABLE " + SqlUtil.quoteTable(fullTableName) 
                                        + " ALTER COLUMN \"" + physicalColName + "\" " 
                                        + (newRequired ? "SET NOT NULL" : "DROP NOT NULL");
                                try {
                                    jdbcTemplate.execute(alterRequiredSql);
                                } catch (Exception reqEx) {
                                    log.error("更新字段 {} 必填约束失败", physicalColName, reqEx);
                                    if (newRequired) {
                                        throw new RuntimeException("修改必填失败: 无法将该字段设为必填，可能由于表中已有历史记录包含空值，请先清理历史数据后再试！");
                                    } else {
                                        throw new RuntimeException("取消必填失败: " + reqEx.getMessage());
                                    }
                                }
                            }
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

                // C: 处理物理删列 -> 找出 oldFieldMap 中存在但 newFields 中不再引用的字段
                // 我们现在允许对所有表（含外部绑定表）执行删列，只要该字段不是系统保留列且已被从元数据中移除
                java.util.Set<String> referencedOldNames = new java.util.HashSet<>();
                for (FieldDef nf : newFields) {
                    String ocn = nf.getOriginalColumnName();
                    if (ocn != null && !ocn.trim().isEmpty()) {
                        referencedOldNames.add(ocn.trim().toLowerCase());
                    }
                }

                // 定义受保护的列集合（静态保留列 + 当前表单正在使用的审计列/状态位）
                java.util.Set<String> protectedCols = new java.util.HashSet<>(RESERVED_COLUMN_NAMES);
                if (exist.getInsertDtColumn() != null) protectedCols.add(exist.getInsertDtColumn().toLowerCase());
                if (exist.getUpdateDtColumn() != null) protectedCols.add(exist.getUpdateDtColumn().toLowerCase());
                if (exist.getDeleteFlagColumn() != null) protectedCols.add(exist.getDeleteFlagColumn().toLowerCase());
                if (exist.getPkColumn() != null) protectedCols.add(exist.getPkColumn().toLowerCase());

                for (String oldColName : oldFieldMap.keySet()) {
                    String lowerOldName = oldColName.toLowerCase();
                    // 如果旧字段名不再被任何新字段引用，且不是核心系统保护列，则执行物理删除
                    if (!referencedOldNames.contains(lowerOldName) && !protectedCols.contains(lowerOldName)) {
                        log.info("表单 {} 检测到字段 {} 被移除，准备执行物理删列", exist.getName(), oldColName);
                        try {
                            jdbcTemplate.execute("ALTER TABLE " + SqlUtil.quoteTable(fullTableName)
                                    + " DROP COLUMN IF EXISTS \"" + oldColName + "\"");
                            
                            // 同步删除参考模板配置中的映射关系，防止下载后出现死字段
                            if (exist.getReferenceTemplateConfig() != null && !exist.getReferenceTemplateConfig().trim().isEmpty()) {
                                try {
                                    Map<String, Object> refConfig = objectMapper.readValue(exist.getReferenceTemplateConfig(), new TypeReference<Map<String, Object>>() {});
                                    Object mappingsObj = refConfig.get("headerMappings");
                                    if (mappingsObj instanceof List<?>) {
                                        List<Map<String, Object>> mappings = (List<Map<String, Object>>) mappingsObj;
                                        boolean changed = mappings.removeIf(m -> oldColName.equalsIgnoreCase(String.valueOf(m.get("columnName"))));
                                        if (changed) {
                                            exist.setReferenceTemplateConfig(objectMapper.writeValueAsString(refConfig));
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("同步删除 referenceTemplateConfig 映射失败: {}", e.getMessage());
                                }
                            }
                        } catch (Exception dropEx) {
                            // 删列可能因为存在依赖（如视图、外键）而失败，此处记录警告但不中断保存流程
                            log.warn("物理删列 {} 失败（可能存在依赖或已手动删除）: {}", oldColName, dropEx.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("更新表单字段元数据失败", e);
                throw new RuntimeException("更新表单物理结构失败: " + e.getMessage());
            }
        }

        exist.setKvConfig(incoming.getKvConfig());
        exist.setReferenceTemplateConfig(incoming.getReferenceTemplateConfig());

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

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void syncFormMetaWithPhysicalTable(DataFillForm form) {
        if (form == null || form.getTableName() == null || form.getTableName().trim().isEmpty()) {
            return;
        }
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty())
                ? form.getSchemaName().trim()
                : "public";
        String table = form.getTableName().trim();

        if (!physicalTableExists(schema, table)) {
            return;
        }

        try {
            // 1. 获取物理表的所有字段信息
            String sql = "SELECT c.ordinal_position, c.column_name, c.data_type, c.udt_name, " +
                    "c.character_maximum_length, c.numeric_precision, c.numeric_scale, c.is_nullable, " +
                    "c.column_default, c.is_identity, " +
                    "COALESCE(pgd.description, '') AS column_comment " +
                    "FROM information_schema.columns c " +
                    "INNER JOIN pg_catalog.pg_namespace ns ON ns.nspname = c.table_schema " +
                    "INNER JOIN pg_catalog.pg_class cls ON cls.relname = c.table_name AND cls.relnamespace = ns.oid " +
                    "LEFT JOIN pg_catalog.pg_description pgd ON pgd.objoid = cls.oid AND pgd.objsubid = c.ordinal_position " +
                    "WHERE c.table_schema = ? AND c.table_name = ?";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, schema, table);
            if (rows.isEmpty()) {
                return;
            }

            // 2. 解析当前表单元数据中的字段定义
            List<FieldDef> currentFields = parseFields(form.getForms());
            if (currentFields == null || currentFields.isEmpty()) {
                return;
            }

            // 3. 构建物理库列名 -> 列详细信息的映射
            Map<String, Map<String, Object>> dbColumnsMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String colName = (String) row.get("column_name");
                if (colName != null) {
                    dbColumnsMap.put(colName.toLowerCase(), row);
                }
            }

            boolean changed = false;

            // 4. 只针对“页面已存在的字段”进行属性（必填、数据类型、注释名称）同步
            for (FieldDef existingField : currentFields) {
                if (existingField.getColumnName() == null) continue;
                String lowerColName = existingField.getColumnName().toLowerCase();

                Map<String, Object> row = dbColumnsMap.get(lowerColName);
                if (row != null) {
                    boolean isNullable = "YES".equalsIgnoreCase((String) row.get("is_nullable"));
                    String comment = (String) row.get("column_comment");
                    String dbType = buildPgTypeLocal(row);

                    // 4.1 同步必填约束 (如果数据库为 NOT NULL，必须必填)
                    boolean requiredInDb = !isNullable;
                    if (existingField.getRequired() == null || existingField.getRequired() != requiredInDb) {
                        existingField.setRequired(requiredInDb);
                        changed = true;
                    }

                    // 4.2 同步物理数据库类型 (dbType)
                    if (existingField.getDbType() == null || !existingField.getDbType().equalsIgnoreCase(dbType)) {
                        String oldTypeCategory = getGeneralTypeCategoryLocal(existingField.getDbType());
                        String newTypeCategory = getGeneralTypeCategoryLocal(dbType);

                        existingField.setDbType(dbType);
                        // 如果物理分类变了 (比如从 VARCHAR 改为 INT)，才更新前端展示的逻辑类型，避免误杀用户的 select/textarea 等定制类型
                        if (!oldTypeCategory.equals(newTypeCategory)) {
                            applyFieldTypeByDbTypeLocal(existingField, dbType);
                        }
                        changed = true;
                    }

                    // 4.3 同步物理表中的字段注释为“中文显示名”（如果数据库注释不为空且与页面不同）
                    if (comment != null && !comment.trim().isEmpty() && !comment.trim().equalsIgnoreCase(existingField.getName())) {
                        existingField.setName(comment.trim());
                        changed = true;
                    }
                }
            }

            if (changed) {
                form.setForms(writeFieldsJson(currentFields));
                formMapper.updateById(form); // 必须持久化保存同步后的元数据！
                log.info("表单 {} 元数据与物理表属性同步成功（已同步必填、类型及注释）", form.getTableName());
            }
        } catch (Exception e) {
            log.warn("无法同步表单 {} 与物理表的元数据: {}", form.getTableName(), e.getMessage(), e);
        }
    }

    private String buildPgTypeLocal(Map<String, Object> row) {
        String dataType = (String) row.get("data_type");
        String udtName = (String) row.get("udt_name");
        dataType = dataType != null ? dataType.toLowerCase() : "";
        udtName = udtName != null ? udtName.toLowerCase() : "";

        Integer charLength = asIntegerLocal(row.get("character_maximum_length"));
        Integer precision = asIntegerLocal(row.get("numeric_precision"));
        Integer scale = asIntegerLocal(row.get("numeric_scale"));

        if ("character varying".equals(dataType) || "varchar".equals(dataType)) {
            return charLength != null && charLength > 0 ? "varchar(" + charLength + ")" : "varchar(255)";
        }
        if ("character".equals(dataType) || "bpchar".equals(udtName)) {
            return charLength != null && charLength > 0 ? "char(" + charLength + ")" : "char(1)";
        }
        if ("text".equals(dataType)) {
            return "text";
        }
        if ("integer".equals(dataType) || "int4".equals(dataType) || "int4".equals(udtName)) {
            return "int4";
        }
        if ("bigint".equals(dataType) || "int8".equals(dataType) || "int8".equals(udtName)) {
            return "int8";
        }
        if ("smallint".equals(dataType) || "int2".equals(dataType) || "int2".equals(udtName)) {
            return "int2";
        }
        if ("boolean".equals(dataType) || "bool".equals(dataType) || "bool".equals(udtName)) {
            return "bool";
        }
        if ("date".equals(dataType)) {
            return "date";
        }
        if ("timestamp without time zone".equals(dataType) || "timestamp with time zone".equals(dataType)
                || "timestamp".equals(dataType) || "timestamptz".equals(udtName)) {
            return "timestamp";
        }
        if ("jsonb".equals(dataType) || "jsonb".equals(udtName)) {
            return "jsonb";
        }
        if ("json".equals(dataType) || "json".equals(udtName)) {
            return "json";
        }
        if ("numeric".equals(dataType) || "decimal".equals(dataType) || "numeric".equals(udtName)) {
            if (precision != null && scale != null) {
                return "numeric(" + precision + ", " + scale + ")";
            }
            return "numeric";
        }
        if ("double precision".equals(dataType) || "float8".equals(udtName)) {
            return "float8";
        }
        if ("real".equals(dataType) || "float4".equals(udtName)) {
            return "float4";
        }
        return !udtName.isEmpty() ? udtName : (!dataType.isEmpty() ? dataType : "varchar(255)");
    }

    private String getGeneralTypeCategoryLocal(String dbType) {
        if (dbType == null) return "text";
        String t = dbType.toUpperCase();
        if (t.contains("INT") || t.contains("NUMERIC") || t.contains("DECIMAL") || t.contains("DOUBLE") || t.contains("REAL") || t.contains("FLOAT")) {
            return "number";
        }
        if (t.contains("TIMESTAMP") || t.contains("DATE") || t.contains("TIME")) {
            return "date";
        }
        if (t.contains("BOOL")) {
            return "boolean";
        }
        return "text";
    }

    private void applyFieldTypeByDbTypeLocal(FieldDef field, String dbType) {
        String typeStr = dbType == null ? "" : dbType.toUpperCase();
        if (typeStr.contains("TIMESTAMP") || "DATE".equals(typeStr) || typeStr.contains("TIME")) {
            field.setType("datetime");
        } else if (typeStr.contains("INT") || typeStr.contains("NUMERIC") || typeStr.contains("DECIMAL")
                || typeStr.contains("DOUBLE") || typeStr.contains("REAL")) {
            field.setType("number");
        } else if (typeStr.contains("TEXT") || typeStr.contains("JSON")) {
            field.setType("textarea");
        } else if (typeStr.contains("BOOL")) {
            field.setType("switch");
        } else {
            field.setType("input");
        }
    }

    private Integer asIntegerLocal(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
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

        // 批量获取该用户的所有填报日志
        Map<String, UserFillLog> lastLogMap = new HashMap<>();
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            List<UserFillLog> logs = userFillLogMapper.selectLastUploadsByUser(userEmail);
            for (UserFillLog logEntry : logs) {
                lastLogMap.put(logEntry.getFormId(), logEntry);
            }
        }

        // 核心优化：批量获取该用户的所有填报状态快照 (Mirror Table)
        Map<String, UserCompletionSnapshot> snapshotMap = new HashMap<>();
        if (userEmail != null && !userEmail.trim().isEmpty()) {
            List<UserCompletionSnapshot> snapshots = snapshotMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserCompletionSnapshot>()
                            .eq("user_email", userEmail));
            for (UserCompletionSnapshot s : snapshots) {
                snapshotMap.put(s.getFormId(), s);
            }
        }

        Map<String, String> folderPathMap = buildFolderPathMap();
        List<Map<String, Object>> pending = new ArrayList<>();
        List<Map<String, Object>> expired = new ArrayList<>();
        List<Map<String, Object>> completed = new ArrayList<>();

        // 结果组装
        for (DataFillForm form : allForms) {
            String statusField = form.getStatus();
            if (!"ACTIVE".equalsIgnoreCase(statusField) && !"EXPIRED".equalsIgnoreCase(statusField)) {
                continue;
            }

            // 权限校验逻辑
            if (!isAdmin) {
                boolean hasUserAccess = false;
                boolean hasDeptAccess = false;

                String fillUserEmails = form.getFillUserEmails();
                if (fillUserEmails != null && !fillUserEmails.trim().isEmpty() && !"[]".equals(fillUserEmails.trim())) {
                    try {
                        List<String> allowed = objectMapper.readValue(fillUserEmails, new TypeReference<List<String>>() {});
                        if (allowed != null && !allowed.isEmpty() && allowed.stream().anyMatch(e -> e != null && e.trim().equalsIgnoreCase(userEmail.trim()))) {
                            hasUserAccess = true;
                        }
                    } catch (Exception ignored) {}
                }

                String fillDepartments = form.getFillDepartments();
                if (!hasUserAccess && fillDepartments != null && !fillDepartments.trim().isEmpty() && !"[]".equals(fillDepartments.trim())) {
                    try {
                        List<String> allowedDepts = objectMapper.readValue(fillDepartments, new TypeReference<List<String>>() {});
                        if (allowedDepts != null && !allowedDepts.isEmpty()) {
                            String userDept = userService.getUserDepartment(userEmail);
                            if (userDept != null && allowedDepts.contains(userDept)) {
                                hasDeptAccess = true;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 如果配置了任何一种限制，但用户都不满足，则跳过
                boolean hasAnyConfig = (fillUserEmails != null && !fillUserEmails.trim().isEmpty() && !"[]".equals(fillUserEmails.trim()))
                                    || (fillDepartments != null && !fillDepartments.trim().isEmpty() && !"[]".equals(fillDepartments.trim()));

                if (!hasAnyConfig) {
                    // 两者都为空，代表只有管理员可见，普通用户无权访问
                    continue;
                }

                if (hasAnyConfig && !hasUserAccess && !hasDeptAccess) {
                    continue;
                }
            }

            // 获取最后填报时间：先搜快照，再搜日志，最后物理探测(仅冷启动一次)
            LocalDateTime lastSubmitTime = null;
            UserCompletionSnapshot snap = snapshotMap.get(form.getId());

            if (snap != null) {
                // 1. 命中快照
                lastSubmitTime = snap.getLastSubmitTime();
            } else {
                // 2. 快照缺失，尝试冷启动物理探测（仅执行一次并回填快照）
                if (userEmail != null && form.getTableName() != null) {
                    try {
                        String col = (form.getInsertDtColumn() != null && !form.getInsertDtColumn().trim().isEmpty())
                                ? form.getInsertDtColumn().trim()
                                : "w_insert_dt";
                        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName().trim() : "public";
                        String fullTable = schema + "." + form.getTableName();
                        String checkSql = String.format("SELECT MAX(\"%s\") FROM %s WHERE \"load_user\" = ? AND \"delete_flag\" = FALSE",
                                col, SqlUtil.quoteTable(fullTable));
                        lastSubmitTime = jdbcTemplate.queryForObject(checkSql, LocalDateTime.class, userEmail);

                        // 异步/后台刷新快照
                        final LocalDateTime finalTime = lastSubmitTime;
                        new Thread(() -> {
                            try {
                                UserCompletionSnapshot newSnap = new UserCompletionSnapshot();
                                newSnap.setUserEmail(userEmail);
                                newSnap.setFormId(form.getId());
                                newSnap.setLastSubmitTime(finalTime);
                                newSnap.setUpdateTime(LocalDateTime.now());
                                snapshotMapper.upsert(newSnap);
                            } catch (Exception ignored) {
                            }
                        }).start();
                    } catch (Exception e) {
                        log.warn("物理探测冷启动失败: {}", e.getMessage());
                    }
                }

                // 3. 兜底逻辑：如果日志比物理探测还新（或者物理探测没搜到数据）
                UserFillLog logEntry = lastLogMap.get(form.getId());
                if (logEntry != null) {
                    if (lastSubmitTime == null || logEntry.getSubmitTime().isAfter(lastSubmitTime)) {
                        lastSubmitTime = logEntry.getSubmitTime();
                    }
                }
            }

            LocalDateTime deadline = form.getDeadline();
            boolean isExpired = "EXPIRED".equalsIgnoreCase(form.getStatus()) || (deadline != null && !now.isBefore(deadline));

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
            } else if (deadline != null && ("WEEKLY".equalsIgnoreCase(mode) || "MONTHLY".equalsIgnoreCase(mode) || "DEADLINE".equalsIgnoreCase(mode))) {
                // 优先使用元数据中已精准计算好的提醒时间 (由 SchedulerService 维护)
                if (form.getReminderDateTime() != null) {
                    startTimeOfCycle = form.getReminderDateTime().withNano(0);
                } else {
                    // 兜底逻辑：如果不匹配或未计算，按天数回溯
                    startTimeOfCycle = deadline.minusHours((long) (remDays * 24)).with(rt).withNano(0);
                }

                if (lastSubmitTime != null && lastSubmitTime.isAfter(startTimeOfCycle)) {
                    completedCurrentCycle = true;
                    if ("WEEKLY".equalsIgnoreCase(mode)) {
                        nextFillTime = startTimeOfCycle.plusDays(7);
                    } else if ("MONTHLY".equalsIgnoreCase(mode)) {
                        nextFillTime = startTimeOfCycle.plusMonths(1);
                    }
                }
            } else if (lastSubmitTime != null && deadline != null) {
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
            String lowerCol = (col == null) ? "" : col.toLowerCase();
            if (lowerCol.contains("idx_load_user")) continue;
            String fullTable = SqlUtil.quoteTable(fullTableName);

            if (physicalColumns.contains(lowerCol)) continue;
            
            try {
                StringBuilder sql = new StringBuilder("ALTER TABLE " + fullTable + " ADD COLUMN \"" + lowerCol + "\" ");
                if ("id".equals(lowerCol)) {
                    boolean hasPk = false;
                    try {
                        String pkQuery = "SELECT count(1) FROM pg_index i " +
                                         "WHERE i.indrelid = ?::regclass AND i.indisprimary";
                        Integer count = jdbcTemplate.queryForObject(pkQuery, Integer.class, fullTableName);
                        hasPk = (count != null && count > 0);
                    } catch (Exception ex) {
                        // 降级兜底
                    }

                    if (hasPk) {
                        // 使用标准 IDENTITY 模式，如果已有主键，作为唯一键加入
                        sql.append("INT4 GENERATED BY DEFAULT AS IDENTITY UNIQUE");
                    } else {
                        // 否则作为主键加入
                        sql.append("INT4 GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY");
                    }
                    jdbcTemplate.execute(sql.toString());
                    success.add(col);
                    continue;
                } else if ("delete_flag".equals(lowerCol)) {
                    sql.append("BOOLEAN DEFAULT FALSE NOT NULL");
                } else if ("w_insert_dt".equals(lowerCol) || "w_update_dt".equals(lowerCol)) {
                    sql.append("TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                } else if ("load_user".equals(lowerCol)) {
                    sql.append("VARCHAR(100)");
                } else if ("extra_data".equals(lowerCol)) {
                    sql.append("JSONB DEFAULT '{}'");
                } else {
                    sql.append("VARCHAR(255)");
                }
                jdbcTemplate.execute(sql.toString());
                // 如果是补齐 delete_flag，不仅要加默认值，由于可能已有存量数据，强制刷一遍 FALSE 避免 null
                if ("delete_flag".equals(lowerCol)) {
                    jdbcTemplate.execute("UPDATE " + fullTable + " SET \"delete_flag\" = FALSE WHERE \"delete_flag\" IS NULL");
                }
                // 如果是补齐 load_user，同步创建索引
                if ("load_user".equals(lowerCol)) {
                    jdbcTemplate.execute("CREATE INDEX ON " + fullTable + " (\"load_user\")");
                }
                success.add(col);
            } catch (Exception e) {
                log.error("补齐列 {} 失败", col, e);
                failed.add(col + ": " + e.getMessage());
            }
        }
        
        // 后验：如果物理表存在 load_user 但没有索引，则静默补齐索引（不再作为缺失列提示）
        try {
            java.util.Set<String> updatedCols = loadPhysicalColumns(schema, tableName);
            if (updatedCols.contains("load_user")) {
                if (!checkIndexExists(schema, tableName, "load_user")) {
                    jdbcTemplate.execute("CREATE INDEX ON " + SqlUtil.quoteTable(fullTableName) + " (\"load_user\")");
                    log.info("物理表 {} 补齐 load_user 性能索引成功", fullTableName);
                }
            }
        } catch (Exception e) {
            log.warn("静默补齐 load_user 索引失败 (可能已存在): {}", e.getMessage());
        }
        
        Map<String, Object> res = new HashMap<>();
        res.put("success", success);
        res.put("failed", failed);
        return res;
    }
}
