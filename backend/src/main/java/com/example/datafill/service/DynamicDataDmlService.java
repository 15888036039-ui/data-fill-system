package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.entity.UserFillLog;

import com.example.datafill.mapper.DataFillFormMapper;

import com.example.datafill.mapper.UserFillLogMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.StringJoiner;

@Service

@RequiredArgsConstructor

public class DynamicDataDmlService {
    private static final Logger log = LoggerFactory.getLogger(DynamicDataDmlService.class);

    private final DataFillFormMapper formMapper;

    private final JdbcTemplate jdbcTemplate;

    private final ApprovalService approvalService;

    private final UserFillLogMapper userFillLogMapper;

    private final ObjectMapper objectMapper;

    private static final java.util.Set<String> SYSTEM_FIELDS = java.util.Set.of(

        "id", "load_user", "creator", "w_insert_dt", "w_update_dt", 

        "create_time", "update_time", "is_deleted", "extra_data", "job_instance",

        "applicantemail", "applicantname", "applicant_email", "applicant_name"

    );

    private java.util.Set<String> loadPhysicalColumns(String tableName) {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                """, String.class, tableName);
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String column : columns) {
            if (column != null) {
                result.add(column.toLowerCase());
            }
        }
        return result;
    }

    private boolean hasColumn(java.util.Set<String> physicalColumns, String columnName) {
        return physicalColumns.contains(columnName.toLowerCase());
    }

    /**
     * 尝试把表单里配置的列名映射到物理表真实列名：
     * 1) 直接命中；2) 驼峰/空格等转 snake_case 后命中。
     */
    private String resolvePhysicalColumn(java.util.Set<String> physicalColumns, String configuredColumn) {
        if (configuredColumn == null) {
            return null;
        }
        String raw = configuredColumn.trim();
        if (raw.isEmpty()) {
            return null;
        }
        String lower = raw.toLowerCase();
        if (physicalColumns.contains(lower)) {
            return lower;
        }

        String normalized = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^a-zA-Z0-9_]", "")
                .toLowerCase();
        if (!normalized.isBlank() && physicalColumns.contains(normalized)) {
            return normalized;
        }
        return null;
    }

    /**
     * 兼容前端不同写法（原值/trim/lower/物理列名）返回同一组选项，避免 key 细微差异导致下拉“无数据”。
     */
    private void putFilterOptions(Map<String, List<String>> result, String configuredCol, String physicalCol, List<String> values) {
        List<String> safeValues = values == null ? new ArrayList<>() : values;
        if (configuredCol != null) {
            result.put(configuredCol, safeValues);
            String trimmed = configuredCol.trim();
            result.put(trimmed, safeValues);
            result.put(trimmed.toLowerCase(), safeValues);
        }
        if (physicalCol != null && !physicalCol.isBlank()) {
            result.put(physicalCol, safeValues);
            result.put(physicalCol.toLowerCase(), safeValues);
        }
    }

    /**

     * 2. 动态向物理表中插入填报数据（使用预编译，防止 SQL 注入）

     */

    @Transactional
    public void insertRowData(String formId, Map<String, Object> rowData) {
        // 注入提取 email 逻辑
        String userEmail = rowData.containsKey("load_user") ? rowData.get("load_user").toString() : (rowData.containsKey("creator") ? rowData.get("creator").toString() : null);
        
        // 1. 填报锁定校验
        checkFillLock(formId, userEmail, false); // 插入操作通常由普通用户发起

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        // 逾期校验：如果配置了截止时间并且已过期，则必须有管理员批准才允许继续填报

        LocalDateTime now = LocalDateTime.now();

        if (form.getDeadline() != null && "ACTIVE".equalsIgnoreCase(form.getStatus())) {

            if (now.isAfter(form.getDeadline())) {

                Object applicantEmailObj = rowData.getOrDefault("applicantEmail", rowData.get("applicant_email"));

                String applicantEmail = applicantEmailObj != null ? applicantEmailObj.toString() : null;

                if (applicantEmail == null || applicantEmail.isBlank() ||

                        !approvalService.hasValidApproval(formId, applicantEmail)) {

                    throw new RuntimeException("该表单已超过截止时间，如需继续填报，请先向管理员申请并获得批准。");

                }

            }

        }

        String tableName = form.getTableName();

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {

            throw new RuntimeException("非法的物理表名称: " + tableName);

        }

        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);
        if (hasColumn(physicalColumns, "ctime") && !rowData.containsKey("ctime")) {
            rowData.put("ctime", now);
        }
        if (hasColumn(physicalColumns, "mtime") && !rowData.containsKey("mtime")) {
            rowData.put("mtime", now);
        }
        String rowId = java.util.UUID.randomUUID().toString().replace("-", ""); // 生成主键

        StringJoiner columns = new StringJoiner(", ");

        StringJoiner placeholders = new StringJoiner(", ");

        List<Object> args = new ArrayList<>();

        if (hasColumn(physicalColumns, "id")) {
            columns.add("\"id\"");
            placeholders.add("?");
            args.add(rowId);
        }

        // 如果前端传了 load_user (或者在 UserTasks 中保存的 email)，优先使用

        String loadUser = rowData.containsKey("load_user") ? rowData.get("load_user").toString() : (rowData.containsKey("creator") ? rowData.get("creator").toString() : null);

        if (loadUser != null && hasColumn(physicalColumns, "load_user")) {

            columns.add("\"load_user\"");

            placeholders.add("?");

            args.add(loadUser);

        }

        // 自动填充入库和更新时间

        if (hasColumn(physicalColumns, "w_insert_dt")) {
            columns.add("\"w_insert_dt\"");
            placeholders.add("?");
            args.add(now);
        }
        if (hasColumn(physicalColumns, "w_update_dt")) {
            columns.add("\"w_update_dt\"");
            placeholders.add("?");
            args.add(now);
        }

        Object applicantEmailObj = rowData.getOrDefault("applicantEmail", rowData.get("applicant_email"));

        String applicantEmail = applicantEmailObj != null ? applicantEmailObj.toString() : null;

        // 解析表单定义以识别 JSONB 字段
        java.util.Set<String> jsonbCols = new java.util.HashSet<>();
        if (hasColumn(physicalColumns, "extra_data")) {
            jsonbCols.add("extra_data");
        }
        try {
            List<FieldDef> fields = objectMapper.readValue(form.getForms(), new com.fasterxml.jackson.core.type.TypeReference<List<FieldDef>>() {});
            for (FieldDef f : fields) {
                if ("JSONB".equalsIgnoreCase(f.getDbType()) || (f.getColumnName() != null && f.getColumnName().endsWith("_json"))) {
                    jsonbCols.add(f.getColumnName().toLowerCase());
                }
            }
        } catch (Exception ignored) {}

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase())) continue;
            if (!key.matches("^[a-zA-Z0-9_]+$")) continue;

            columns.add("\"" + key + "\"");
            
            Object val = entry.getValue();
            if (jsonbCols.contains(key.toLowerCase())) {
                placeholders.add("?");
                if (val != null && !(val instanceof String)) {
                    try { val = objectMapper.writeValueAsString(val); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { val = "{}"; }
                }
                if (val != null) {
                    try {
                        org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                        pgObj.setType("jsonb");
                        pgObj.setValue(val.toString());
                        val = pgObj;
                    } catch (Exception e) {}
                }
            } else {
                placeholders.add("?");
            }
            args.add(val);
        }

        // 专家补丁：确保 extra_data 始终参与（如果没有在循环中添加）

        if (hasColumn(physicalColumns, "extra_data") && !rowData.containsKey("extra_data")) {

            columns.add("\"extra_data\"");

            placeholders.add("?");

            try {
                org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                pgObj.setType("jsonb");
                pgObj.setValue("{}");
                args.add(pgObj);
            } catch (Exception e) {
                args.add("{}");
            }

        }

        String insertSql = String.format("INSERT INTO \"%s\" (%s) VALUES (%s)", tableName, columns.toString(), placeholders.toString());

        jdbcTemplate.update(insertSql, args.toArray());

        // 记录用户填报日志（用于“已填报”统计与下次填报时间计算）

        UserFillLog log = new UserFillLog();

        log.setFormId(formId);

        log.setDataId(rowId);

        log.setUserEmail(loadUser != null ? loadUser : applicantEmail);

        log.setSubmitTime(now);

        log.setCreateTime(now);

        log.setUpdateTime(now);

        userFillLogMapper.insert(log);

    }

    /**

     * 批量向物理表中插入数据（优化性能）

     */

    @Transactional
    public void batchInsertRowData(String formId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;

        String firstUser = null;
        for (Map<String, Object> row : rows) {
            Object u = row.get("load_user");
            if (u == null) u = row.get("creator");
            if (u != null && !u.toString().isBlank()) {
                firstUser = u.toString();
                break;
            }
        }
        
        // 1. 填报锁定校验
        checkFillLock(formId, firstUser, false); 

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);

        LocalDateTime now = LocalDateTime.now();

        // 获取表单定义的字段列表

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

            throw new RuntimeException("解析表单字段定义失败", e);

        }

        // 基础列



        List<String> columns = new ArrayList<>();

        boolean hasId = hasColumn(physicalColumns, "id");
        boolean hasInsertDt = hasColumn(physicalColumns, "w_insert_dt");
        boolean hasUpdateDt = hasColumn(physicalColumns, "w_update_dt");
        boolean hasLoadUserColumn = hasColumn(physicalColumns, "load_user");
        boolean hasExtraDataColumn = hasColumn(physicalColumns, "extra_data");

        if (hasId) columns.add("\"id\"");
        if (hasInsertDt) columns.add("\"w_insert_dt\"");
        if (hasUpdateDt) columns.add("\"w_update_dt\"");

        // 收集业务列：从表单定义中提取，而不是由首行 KeySet 决定（防止首行漏列）

        List<String> dataColumns = new ArrayList<>();

        for (FieldDef field : fields) {

            String colName = field.getColumnName();

            if (colName != null && !SYSTEM_FIELDS.contains(colName.toLowerCase())) {

                dataColumns.add(colName);

            }

        }

        boolean hasLoadUser = hasLoadUserColumn && (rows.get(0).containsKey("load_user") || rows.get(0).containsKey("creator"));

        if (hasLoadUser) columns.add("\"load_user\"");

        for (String col : dataColumns) columns.add("\"" + col + "\"");

        if (hasExtraDataColumn) columns.add("\"extra_data\"");

        String colPart = String.join(", ", columns);

        java.util.Set<String> jsonbCols = new java.util.HashSet<>();
        if (hasExtraDataColumn) jsonbCols.add("extra_data");
        for (FieldDef f : fields) {
            if ("JSONB".equalsIgnoreCase(f.getDbType()) || (f.getColumnName() != null && f.getColumnName().endsWith("_json"))) {
                jsonbCols.add(f.getColumnName().toLowerCase());
            }
        }

        String copySql = String.format("COPY \"%s\" (%s) FROM STDIN WITH (FORMAT text, DELIMITER '\t', NULL '\\N', ENCODING 'UTF8')", tableName, colPart);

        StringBuilder tsvBuilder = new StringBuilder();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (hasColumn(physicalColumns, "ctime") && !row.containsKey("ctime")) {
                row.put("ctime", now);
            }
            if (hasColumn(physicalColumns, "mtime") && !row.containsKey("mtime")) {
                row.put("mtime", now);
            }

            String rowId = java.util.UUID.randomUUID().toString().replace("-", "");
            
            // Build the TSV row
            if (hasId) appendTsv(tsvBuilder, rowId);
            if (hasInsertDt) appendTsv(tsvBuilder, now);
            if (hasUpdateDt) appendTsv(tsvBuilder, now);

            if (hasLoadUser) {
                Object u = row.get("load_user");
                if (u == null) u = row.get("creator");
                appendTsv(tsvBuilder, u);
            }

            for (String col : dataColumns) {
                Object val = row.get(col);
                if (val != null && !(val instanceof String)) {
                    if (jsonbCols.contains(col.toLowerCase())) {
                        try { val = objectMapper.writeValueAsString(val); } catch (Exception e) { val = "{}"; }
                    }
                }
                appendTsv(tsvBuilder, val);
            }

            if (hasExtraDataColumn) {
                Object extra = row.get("extra_data");
                if (extra != null && !(extra instanceof String)) {
                    try { extra = objectMapper.writeValueAsString(extra); } catch (Exception e) { extra = "{}"; }
                }
                if (extra == null) extra = "{}";
                appendTsv(tsvBuilder, extra);
            }

            // Replace last tab with newline
            tsvBuilder.setCharAt(tsvBuilder.length() - 1, '\n');
        }

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try {
                org.postgresql.PGConnection pgConn = conn.unwrap(org.postgresql.PGConnection.class);
                org.postgresql.copy.CopyManager copyManager = pgConn.getCopyAPI();
                java.io.StringReader reader = new java.io.StringReader(tsvBuilder.toString());
                copyManager.copyIn(copySql, reader);
                return null;
            } catch (Exception e) {
                throw new RuntimeException("COPY failed: " + e.getMessage(), e);
            }
        });
    }

    private void appendTsv(StringBuilder sb, Object val) {
        if (val == null) {
            sb.append("\\N\t");
        } else {
            String str;
            if (val instanceof Double) {
                str = java.math.BigDecimal.valueOf((Double) val).stripTrailingZeros().toPlainString();
            } else if (val instanceof Float) {
                str = java.math.BigDecimal.valueOf((Float) val).stripTrailingZeros().toPlainString();
            } else if (val instanceof java.util.Date) {
                str = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
            } else if (val instanceof java.time.LocalDateTime) {
                str = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((java.time.LocalDateTime) val);
            } else {
                str = val.toString();
                // 自动嗅探并清洗带千位分隔符的纯数字格式（例如 1,750 或 -1,234.56），满足 PG COPY 对 NUMERIC 的强格式要求
                if (str.matches("^[-+]?[0-9]{1,3}(,[0-9]{3})*(\\.[0-9]+)?$")) {
                    str = str.replace(",", "");
                }
            }
            // Escape backslashes, tabs, newlines, and carriage returns
            str = str.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
            sb.append(str).append('\t');
        }
    }

    /**

     * 2.5 动态更新物理表的数据（带归属校验）

     */

    public void updateRowData(String formId, String dataId, Map<String, Object> rowData, String operatorEmail, boolean isAdmin) {
        // 1. 填报锁定校验
        checkFillLock(formId, operatorEmail, isAdmin);

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);
        if (hasColumn(physicalColumns, "mtime") && !rowData.containsKey("mtime")) {
            rowData.put("mtime", LocalDateTime.now());
        }

        // 归属权限校验 & 行级宽限期校验
        if (!isAdmin && operatorEmail != null && hasColumn(physicalColumns, "id") && hasColumn(physicalColumns, "load_user")) {
            String checkSql = String.format("SELECT \"load_user\" FROM \"%s\" WHERE \"id\" = ?", tableName);
            try {
                Map<String, Object> record = jdbcTemplate.queryForMap(checkSql, dataId);
                String owner = (String) record.get("load_user");
                // LocalDateTime insertDt = (LocalDateTime) record.get("w_insert_dt");

                // 1. 归属校验 (保留数据隔离，但删掉宽限期锁定)
                if (owner != null && !owner.equalsIgnoreCase(operatorEmail)) {
                    throw new RuntimeException("权限不足：您只能修改自己填报的数据");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception ignored) {}
        }

        StringJoiner sets = new StringJoiner(", ");

        List<Object> args = new ArrayList<>();

        // 解析表单定义以识别 JSONB 字段和物理类型 (用于显式 CAST)
        java.util.Set<String> jsonbCols = new java.util.HashSet<>();
        java.util.Map<String, String> colToDbType = new java.util.HashMap<>();
        if (hasColumn(physicalColumns, "extra_data")) {
            jsonbCols.add("extra_data");
        }
        try {
            List<FieldDef> fields = objectMapper.readValue(form.getForms(), new com.fasterxml.jackson.core.type.TypeReference<List<FieldDef>>() {});
            for (FieldDef f : fields) {
                String col = f.getColumnName() != null ? f.getColumnName().toLowerCase() : null;
                if (col == null) continue;

                if ("JSONB".equalsIgnoreCase(f.getDbType()) || col.endsWith("_json")) {
                    jsonbCols.add(col);
                }
                if (f.getDbType() != null && !f.getDbType().isBlank()) {
                    // 提取基础类型用于 CAST (例如 "VARCHAR(255)" -> "VARCHAR", "INT(10)" -> "INT")
                    String baseType = f.getDbType().trim().split("\\(")[0].split(" ")[0].toLowerCase();
                    colToDbType.put(col, baseType);
                }
            }
        } catch (Exception ignored) {}

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase())) continue;
            if (!key.matches("^[a-zA-Z0-9_]+$")) continue;

            String lowerKey = key.toLowerCase();
            Object val = entry.getValue();

            // 构建带有显式类型转换的 SET 子句
            String castSuffix = "";
            String dbType = colToDbType.get(lowerKey);
            if (dbType != null) {
                // 针对 PG 容易报错的类型添加显式 CAST
                if (dbType.contains("time") || dbType.contains("date") || dbType.contains("timestamp") 
                    || dbType.contains("bool") || dbType.contains("numeric") || dbType.contains("int") || dbType.contains("integer")) {
                    castSuffix = "::" + dbType;
                }
            }

            if (jsonbCols.contains(lowerKey)) {
                sets.add("\"" + key + "\" = ?" + castSuffix);
                if (val != null && !(val instanceof String)) {
                    try { val = objectMapper.writeValueAsString(val); } catch (Exception e) { val = "{}"; }
                }
                if (val != null) {
                    try {
                        org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                        pgObj.setType("jsonb");
                        pgObj.setValue(val.toString());
                        val = pgObj;
                    } catch (Exception e) {}
                }
            } else {
                sets.add("\"" + key + "\" = ?" + castSuffix);
            }
            args.add(val);
        }

        if (hasColumn(physicalColumns, "w_update_dt")) {
            sets.add("\"w_update_dt\" = CURRENT_TIMESTAMP");
        }

        if (sets.length() == 0) {
            return;
        }

        if (!hasColumn(physicalColumns, "id")) {
            throw new RuntimeException("当前绑定表缺少 id 字段，暂不支持按行修改");
        }

        String updateSql = String.format("UPDATE \"%s\" SET %s WHERE \"id\" = ?", tableName, sets.toString());

        args.add(dataId);

        jdbcTemplate.update(updateSql, args.toArray());

    }

    /**
    /**
     * 3. 查询动态物理表的数据（分页 + 条件筛选）（防止 SQL 注入）
     */
    public Map<String, Object> getTableDataPage(String formId, int page, int size, Map<String, String> filters, String userEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String tableName = form.getTableName();
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("非法的物理表名称: " + tableName);
        }
        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);

        StringBuilder whereClause = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhereClause(whereClause, args, filters, userEmail, isAdmin, physicalColumns);


        // 统计总数

        String countSql = "SELECT COUNT(1) FROM \"" + tableName + "\"" + whereClause.toString();

        Long totalObj = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());

        long total = totalObj != null ? totalObj : 0L;

        // 分页查询

        int offset = (page - 1) * size;

        String orderBy = hasColumn(physicalColumns, "w_insert_dt")
                ? " ORDER BY \"w_insert_dt\" DESC"
                : (hasColumn(physicalColumns, "id") ? " ORDER BY \"id\" DESC" : "");
        String listSql = "SELECT * FROM \"" + tableName + "\"" + whereClause.toString() + orderBy + " LIMIT ? OFFSET ?";

        args.add(size);

        args.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(listSql, args.toArray());

        List<LinkedHashMap<String, Object>> records = new ArrayList<>();

        for (Map<String, Object> row : rows) {

            LinkedHashMap<String, Object> record = new LinkedHashMap<>(row);

            Object extraDataObj = hasColumn(physicalColumns, "extra_data") ? record.get("extra_data") : null;

            if (extraDataObj != null) {

                try {

                    Map<String, Object> extraMap = objectMapper.readValue(extraDataObj.toString(), new TypeReference<Map<String, Object>>() {});

                    if (extraMap != null) {

                        for (Map.Entry<String, Object> entry : extraMap.entrySet()) {

                            record.putIfAbsent(entry.getKey(), entry.getValue());

                        }

                    }

                } catch (Exception e) {

                    // ignore

                }

            }

            if (hasColumn(physicalColumns, "extra_data")) {
                record.remove("extra_data");
            }

            records.add(record);

        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("records", records);

        // 注入锁定状态信息
        if (userEmail != null && !userEmail.isBlank()) {
            try {
                UserFillLog lastLog = userFillLogMapper.selectLastByFormAndUser(formId, userEmail);
                LocalDateTime firstSubmitTime = (lastLog != null) ? lastLog.getSubmitTime() : null;
                
                // 兼容逻辑：如果没有 log，查物理表
                if (firstSubmitTime == null) {
                    if (hasColumn(physicalColumns, "w_insert_dt") && hasColumn(physicalColumns, "load_user")) {
                        StringBuilder checkSql = new StringBuilder(String.format("SELECT MIN(\"w_insert_dt\") FROM \"%s\" WHERE \"load_user\" = ?", tableName));
                        if (hasColumn(physicalColumns, "is_deleted")) {
                            checkSql.append(" AND (\"is_deleted\" IS NULL OR \"is_deleted\" = 0)");
                        }
                        firstSubmitTime = jdbcTemplate.queryForObject(checkSql.toString(), LocalDateTime.class, userEmail);
                    }
                }

                if (firstSubmitTime != null) {
                    Map<String, Object> lockStatus = new java.util.HashMap<>();
                    lockStatus.put("isLocked", false); // 永久开放：不再根据时间锁定
                    lockStatus.put("graceEndTime", null);
                    lockStatus.put("hasSubmitted", true);
                    result.put("lockStatus", lockStatus);
                } else {
                    result.put("lockStatus", Map.of("isLocked", false, "hasSubmitted", false));
                }
            } catch (Exception e) {
                result.put("lockStatus", Map.of("isLocked", false, "hasSubmitted", false));
            }
        }

        return result;
    }

    /**

     * 3.2 获取筛选项下拉数据：根据当前表中已有数据，按字段返回去重后的值列表

     */

    public Map<String, List<String>> getFilterOptions(String formId, String operatorEmail, boolean isAdmin) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        String tableName = form.getTableName();

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {

            throw new RuntimeException("非法的物理表名称");

        }

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

            throw new RuntimeException("表单字段解析异常", e);

        }

        List<FieldDef> filterable = fields.stream()

                .filter(f -> Boolean.TRUE.equals(f.getFilterable()))

                .toList();

        if (filterable.isEmpty()) {

            filterable = fields.stream().limit(3).toList();

        }

        Map<String, List<String>> result = new LinkedHashMap<>();

        // 构建隔离过滤条件

        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);

        String isolationWhere = " WHERE 1 = 1 ";

        List<Object> baseArgs = new ArrayList<>();

        if (hasColumn(physicalColumns, "is_deleted")) {
            isolationWhere += " AND (\"is_deleted\" IS NULL OR \"is_deleted\" = 0) ";
        }

        if (!isAdmin && operatorEmail != null && !operatorEmail.isBlank() && hasColumn(physicalColumns, "load_user")) {
            isolationWhere += " AND (\"load_user\" = ? OR \"load_user\" IS NULL) ";
            baseArgs.add(operatorEmail);
        }

        for (FieldDef field : filterable) {

            String configuredCol = field.getColumnName();

            if (configuredCol == null || configuredCol.isBlank()) {

                continue;

            }

            String physicalCol = resolvePhysicalColumn(physicalColumns, configuredCol);
            if (physicalCol == null) {
                // 前端仍使用 configuredCol 作为 key，这里返回空数组避免 undefined
                putFilterOptions(result, configuredCol, null, new ArrayList<>());
                continue;
            }

            try {

                // 不再进行严格正则校验，因为下面使用了双引号引用标识符

                String sql = "SELECT DISTINCT CAST(\"" + physicalCol + "\" AS TEXT) AS val FROM \"" + tableName + "\" " +

                        isolationWhere + " AND \"" + physicalCol + "\" IS NOT NULL ORDER BY val LIMIT 100";

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, baseArgs.toArray());

                List<String> values = rows.stream()

                        .map(m -> m.get("val") != null ? m.get("val") : m.get("VAL"))

                        .filter(v -> v != null && !v.toString().isBlank())

                        .map(Object::toString)

                        .distinct()

                        .toList();

                putFilterOptions(result, configuredCol, physicalCol, values);

            } catch (Exception e) {

                // 记录错误但不中断其他列的筛选加载
                log.warn("加载筛选项失败, formId={}, table={}, configuredCol={}, physicalCol={}",
                        formId, tableName, configuredCol, physicalCol, e);

                putFilterOptions(result, configuredCol, physicalCol, new ArrayList<>());

            }

        }

        return result;

    }

    /**

     * 4. 批量软删除动态物理表的数据

     */

    @Transactional
    public void batchDeleteRowData(String formId, List<String> dataIds, String operatorEmail, boolean isAdmin) {
        if (dataIds == null || dataIds.isEmpty()) return;

        // 1. 填报锁定校验
        checkFillLock(formId, operatorEmail, isAdmin);

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        // 权限校验 & 行级宽限期校验：非管理员只能删除自己 24h 内的数据
        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);

        if (!isAdmin && operatorEmail != null && hasColumn(physicalColumns, "id") && hasColumn(physicalColumns, "load_user")) {
            for (String dataId : dataIds) {
                String checkSql = String.format("SELECT \"load_user\" FROM \"%s\" WHERE \"id\" = ?", tableName);
                try {
                    Map<String, Object> record = jdbcTemplate.queryForMap(checkSql, dataId);
                    String owner = (String) record.get("load_user");
                    // LocalDateTime insertDt = (LocalDateTime) record.get("w_insert_dt");

                    if (owner != null && !owner.equalsIgnoreCase(operatorEmail)) {
                        throw new RuntimeException("权限不足：您包含非本人填报的数据，无法批量删除");
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    // 记录不存在或查询失败，跳过或记录日志
                }
            }
        }

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {

            throw new RuntimeException("非法的物理表名称: " + tableName);

        }

        StringJoiner placeholders = new StringJoiner(",");

        for (int i = 0; i < dataIds.size(); i++) {

            placeholders.add("?");

        }

        List<Object> args = new ArrayList<>(dataIds);
        if (hasColumn(physicalColumns, "is_deleted")) {
            String updateSql = String.format("UPDATE \"%s\" SET is_deleted = 1 WHERE \"id\" IN (%s)", tableName, placeholders.toString());
            try {
                jdbcTemplate.update(updateSql, args.toArray());
                return;
            } catch (Exception ignored) {}
        }
        String deleteSql = String.format("DELETE FROM \"%s\" WHERE \"id\" IN (%s)", tableName, placeholders.toString());
        jdbcTemplate.update(deleteSql, args.toArray());

    }

    @Transactional
    public void deleteAllFilteredData(String formId, Map<String, String> filters, String operatorEmail, boolean isAdmin) {
        // 1. 填报锁定校验
        checkFillLock(formId, operatorEmail, isAdmin);

        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String tableName = form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(tableName);

        StringBuilder whereClause = new StringBuilder();
        List<Object> args = new ArrayList<>();
        buildWhereClause(whereClause, args, filters, operatorEmail, isAdmin, physicalColumns);

        if (hasColumn(physicalColumns, "is_deleted")) {
            String updateSql = String.format("UPDATE \"%s\" SET is_deleted = 1 %s", tableName, whereClause.toString());
            try {
                jdbcTemplate.update(updateSql, args.toArray());
                return;
            } catch (Exception ignored) {
                // ignore and fallback to hard delete
            }
        }
        String deleteSql = String.format("DELETE FROM \"%s\" %s", tableName, whereClause.toString());
        jdbcTemplate.update(deleteSql, args.toArray());
    }

    /**
     * 4. 软删除动态物理表的数据
     */
    public void deleteRowData(String formId, String dataId, String operatorEmail, boolean isAdmin) {
        batchDeleteRowData(formId, List.of(dataId), operatorEmail, isAdmin);
    }

    /**
     * 构建通用的 WHERE 子句（支持数据隔离和条件筛选）
     */
    private void buildWhereClause(StringBuilder whereClause, List<Object> args, Map<String, String> filters, String userEmail, boolean isAdmin, java.util.Set<String> physicalColumns) {
        whereClause.append(" WHERE 1 = 1 ");

        if (hasColumn(physicalColumns, "is_deleted")) {
            whereClause.append(" AND (\"is_deleted\" IS NULL OR \"is_deleted\" = 0) ");
        }
        
        // 核心隔离逻辑：非管理员只能看自己的数据 (兼容旧数据：允许 load_user 为空)
        if (!isAdmin && userEmail != null && !userEmail.isBlank() && hasColumn(physicalColumns, "load_user")) {
            whereClause.append(" AND (\"load_user\" = ? OR \"load_user\" IS NULL) ");
            args.add(userEmail);
        }

        if (filters != null) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String val = entry.getValue();
                if (val == null || val.trim().isEmpty()) continue;

                String col = entry.getKey();
                if (col.startsWith("extra_data.") && hasColumn(physicalColumns, "extra_data")) {
                    String jsonKey = col.substring("extra_data.".length());
                    if (jsonKey.matches("^[a-zA-Z0-9_]+$")) {
                        whereClause.append(" AND \"extra_data\"->> ? LIKE ? ");
                        args.add(jsonKey);
                        args.add("%" + val + "%");
                    }
                } else if (col.matches("^[a-zA-Z0-9_]+$") && hasColumn(physicalColumns, col)) {
                    if (("creator".equalsIgnoreCase(col) || "load_user".equalsIgnoreCase(col)) && hasColumn(physicalColumns, col)) {
                        whereClause.append(" AND \"").append(col).append("\" = ? ");
                        args.add(val);
                    } else {
                        // 强制转为文本以支持数字等其他类型的模糊查询
                        whereClause.append(" AND CAST(\"").append(col).append("\" AS TEXT) LIKE ? ");
                        args.add("%" + val + "%");
                    }
                }
            }
        }
    }

    /**
     * 校验当前用户是否已被锁定（本期已填报）
     */
    private void checkFillLock(String formId, String userEmail, boolean isAdmin) {
        // 根据需求变更：填报锁定逻辑已移除，用户可以一直操作自己填报过的数据
    }
}

