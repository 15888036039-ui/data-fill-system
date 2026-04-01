package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;
import com.example.datafill.entity.DataFillForm;
import com.example.datafill.entity.UserFillLog;
import com.example.datafill.mapper.DataFillFormMapper;
import com.example.datafill.mapper.UserFillLogMapper;
import com.example.datafill.util.SqlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DynamicDataDmlService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DynamicDataDmlService.class);
    private static final java.util.regex.Pattern NUMBER_PATTERN = java.util.regex.Pattern.compile("^-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?$");

    private final DataFillFormMapper formMapper;

    @Autowired
    @Qualifier("dynamicJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    private final ApprovalService approvalService;
    private final UserFillLogMapper userFillLogMapper;
    private final ObjectMapper objectMapper;

    private static final java.util.Set<String> SYSTEM_FIELDS = new java.util.HashSet<>(Arrays.asList(
        "id", "load_user", "creator", "w_insert_dt", "w_update_dt", 
        "create_time", "update_time", "is_deleted", "extra_data", "job_instance",
        "applicantemail", "applicantname", "applicant_email", "applicant_name",
        "ctime", "mtime", "created_at", "updated_at"
    ));

    private static final java.util.Set<String> CREATION_FIELDS = new java.util.HashSet<>(Arrays.asList(
        "w_insert_dt", "create_time", "ctime", "created_at"
    ));

    private static final java.util.Set<String> UPDATE_FIELDS = new java.util.HashSet<>(Arrays.asList(
        "w_update_dt", "update_time", "mtime", "updated_at"
    ));

    private java.util.Map<String, String> loadPhysicalColumns(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            schema = SqlUtil.extractSchema(table);
            if (schema == null) {
                schema = "public";
            }
            table = SqlUtil.extractTable(table);
        }
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ?",
            schema, table
        );
        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("column_name");
            String type = (String) row.get("data_type");
            if (name != null) {
                result.put(name.toLowerCase(), type != null ? type.toLowerCase() : "text");
            }
        }
        return result;
    }

    private boolean hasColumn(java.util.Map<String, String> physicalColumns, String columnName) {
        return columnName != null && physicalColumns.containsKey(columnName.toLowerCase());
    }

    private String resolvePhysicalColumn(java.util.Map<String, String> physicalColumns, String configuredColumn) {
        if (configuredColumn == null) return null;
        String raw = configuredColumn.trim();
        if (raw.isEmpty()) return null;
        String lower = raw.toLowerCase();
        if (physicalColumns.containsKey(lower)) return lower;

        String normalized = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^a-zA-Z0-9_]", "")
                .toLowerCase();
        if (!normalized.trim().isEmpty() && physicalColumns.containsKey(normalized)) {
            return normalized;
        }
        return null;
    }

    private Object convertValueForDb(Object val, String dbType) {
        if (val == null) return null;
        if (dbType == null) return val;
        String type = dbType.toLowerCase();

        boolean isStringEmpty = val instanceof String && ((String) val).trim().isEmpty();
        if (isStringEmpty && !type.contains("text") && !type.contains("char") && !type.contains("json")) {
            return null; // Prevent casting empty strings to numeric, timestamp, uuid, etc.
        }
        
        if (type.contains("timestamp") || type.contains("date")) {
            if (val instanceof String && !isStringEmpty) {
                String s = ((String) val).trim();
                // Handle common date formats like 20260315 or 2026-03-15
                if (s.matches("\\d{8}")) {
                    s = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
                }
                try {
                    if (s.contains(" ") || s.contains("T")) {
                        return java.sql.Timestamp.valueOf(s.replace("T", " "));
                    } else {
                        // If it's just a date, add time or use Date.valueOf
                        if (type.contains("timestamp")) {
                            return java.sql.Timestamp.valueOf(s + " 00:00:00");
                        } else {
                            return java.sql.Date.valueOf(s);
                        }
                    }
                } catch (Exception e) {
                    return val; // Fallback to original
                }
            }
        } else if (type.contains("time") && !type.contains("timestamp")) {
            if (val instanceof String && !isStringEmpty) {
                String s = ((String) val).trim();
                try {
                    if (s.length() == 5) s += ":00"; // Handle HH:mm -> HH:mm:ss
                    return java.sql.Time.valueOf(s);
                } catch (Exception e) {
                    return val;
                }
            }
        } else if (type.contains("uuid")) {
            if (val instanceof String && !isStringEmpty) {
                try {
                    String s = ((String) val).trim();
                    if (s.length() != 36 && s.length() != 32) return val;
                    return java.util.UUID.fromString(s);
                } catch (Exception ignored) {}
            }
        } else if (type.contains("int") || type.contains("numeric") || type.contains("decimal") || type.contains("real") || type.contains("double") || type.contains("float") || type.contains("serial") || type.equals("smallint") || type.equals("bigint")) {
            if (val instanceof String && !isStringEmpty) {
                try {
                    String s = ((String) val).trim();
                    // Remove currency symbols, common thousand separators (comma, non-breaking space), percent signs, and all spaces
                    s = s.replace(",", "").replace("$", "").replace("¥", "").replace("%", "").replaceAll("\\s+", "");
                    
                    if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("N/A") || s.equalsIgnoreCase("NA")) return val;
                    return new java.math.BigDecimal(s);
                } catch (Exception ignored) {}
            }
        } else if (type.contains("boolean") || type.contains("bool")) {
            if (val instanceof String) {
                String s = ((String) val).toLowerCase().trim();
                return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "t".equals(s) || "on".equals(s);
            }
        }
        return val;
    }

    private void putFilterOptions(Map<String, List<String>> result, String configuredCol, String physicalCol, List<String> values) {
        List<String> safeValues = values == null ? new ArrayList<>() : values;
        if (configuredCol != null) {
            result.put(configuredCol, safeValues);
            String trimmed = configuredCol.trim();
            result.put(trimmed, safeValues);
            result.put(trimmed.toLowerCase(), safeValues);
        }
        if (physicalCol != null && !physicalCol.trim().isEmpty()) {
            result.put(physicalCol, safeValues);
            result.put(physicalCol.toLowerCase(), safeValues);
        }
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void insertRowData(String formId, Map<String, Object> rowData) {
        String userEmail = rowData.containsKey("load_user") ? rowData.get("load_user").toString() : (rowData.containsKey("creator") ? rowData.get("creator").toString() : null);
        checkFillLock(formId, userEmail, false);

        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");

        LocalDateTime now = LocalDateTime.now();
        if (form.getDeadline() != null && "ACTIVE".equalsIgnoreCase(form.getStatus())) {
            if (now.isAfter(form.getDeadline())) {
                Object applicantEmailObj = rowData.getOrDefault("applicantEmail", rowData.get("applicant_email"));
                String applicantEmail = applicantEmailObj != null ? applicantEmailObj.toString() : null;
                if (applicantEmail == null || applicantEmail.trim().isEmpty() || !approvalService.hasValidApproval(formId, applicantEmail)) {
                    throw new RuntimeException("该表单已超过截止时间，请申请批准。");
                }
            }
        }

        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String tableName = form.getTableName();
        String fullTableName = schema + "." + tableName;

        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, tableName);
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        String rowId = java.util.UUID.randomUUID().toString().replace("-", "");

        if (hasColumn(physicalColumns, "id")) {
            columns.add("\"id\"");
            placeholders.add("?");
            args.add(rowId);
        }

        String loadUser = rowData.containsKey("load_user") ? rowData.get("load_user").toString() : (rowData.containsKey("creator") ? rowData.get("creator").toString() : null);
        if (loadUser != null && hasColumn(physicalColumns, "load_user")) {
            columns.add("\"load_user\"");
            placeholders.add("?");
            args.add(loadUser);
        }

        for (String col : CREATION_FIELDS) {
            if (hasColumn(physicalColumns, col)) {
                columns.add("\"" + col + "\"");
                placeholders.add("?");
                args.add(now);
            }
        }
        for (String col : UPDATE_FIELDS) {
            if (hasColumn(physicalColumns, col)) {
                columns.add("\"" + col + "\"");
                placeholders.add("?");
                args.add(now);
            }
        }

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase()) || !hasColumn(physicalColumns, key)) continue;
            
            String colType = physicalColumns.get(key.toLowerCase());
            columns.add("\"" + key + "\"");
            Object val = entry.getValue();
            val = convertValueForDb(val, colType);
            
            // 使用 CAST(? AS ...) 系统化处理 PostgreSQL 类型
            if (colType != null && !colType.equals("text") && !colType.equals("character varying")) {
                placeholders.add(String.format("CAST(? AS %s)", colType));
            } else {
                placeholders.add("?");
            }
            
            if (val != null && (key.toLowerCase().endsWith("_json") || key.toLowerCase().equals("extra_data") || "jsonb".equalsIgnoreCase(colType) || "json".equalsIgnoreCase(colType))) {
                try {
                    val = val instanceof String ? (String)val : objectMapper.writeValueAsString(val);
                } catch (Exception ignored) {}
            }
            args.add(val);
        }

        String insertSql = String.format("INSERT INTO %s (%s) VALUES (%s)", SqlUtil.quoteTable(fullTableName), String.join(",", columns), String.join(",", placeholders));
        jdbcTemplate.update(insertSql, args.toArray());

        UserFillLog fillLog = new UserFillLog();
        fillLog.setFormId(formId);
        fillLog.setDataId(rowId);
        fillLog.setUserEmail(loadUser);
        fillLog.setSubmitTime(now);
        userFillLogMapper.insert(fillLog);
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void batchInsertRowData(String formId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");

        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String tableName = form.getTableName();
        String fullTableName = schema + "." + tableName;
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, tableName);
        LocalDateTime now = LocalDateTime.now();

        List<FieldDef> fields;
        try {
            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
        } catch (Exception e) { throw new RuntimeException(e); }

        List<String> columns = new ArrayList<>();
        if (hasColumn(physicalColumns, "id")) columns.add("\"id\"");
        for (String col : CREATION_FIELDS) {
            if (hasColumn(physicalColumns, col)) columns.add("\"" + col + "\"");
        }
        for (String col : UPDATE_FIELDS) {
            if (hasColumn(physicalColumns, col)) columns.add("\"" + col + "\"");
        }
        if (hasColumn(physicalColumns, "load_user")) columns.add("\"load_user\"");

        List<String> dataColumns = new ArrayList<>();
        List<String> dataColumnTypes = new ArrayList<>();
        for (FieldDef f : fields) {
            if (f.getColumnName() != null && !SYSTEM_FIELDS.contains(f.getColumnName().toLowerCase())) {
                dataColumns.add(f.getColumnName());
                columns.add("\"" + f.getColumnName() + "\"");
                dataColumnTypes.add(physicalColumns.get(f.getColumnName().toLowerCase()));
            }
        }
        if (hasColumn(physicalColumns, "extra_data")) columns.add("\"extra_data\"");

        String copySql = String.format("COPY %s (%s) FROM STDIN WITH (FORMAT text, DELIMITER '\t', NULL '\\N', ENCODING 'UTF8')", SqlUtil.quoteTable(fullTableName), String.join(",", columns));

        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try {
                org.postgresql.PGConnection pg = conn.unwrap(org.postgresql.PGConnection.class);
                org.postgresql.copy.CopyManager copyManager = pg.getCopyAPI();
                org.postgresql.copy.CopyIn copyIn = copyManager.copyIn(copySql);

                try {
                    StringBuilder tsv = new StringBuilder(1024 * 1024 * 2);
                    int batchCount = 0;
                    for (Map<String, Object> row : rows) {
                        if (hasColumn(physicalColumns, "id")) appendTsv(tsv, java.util.UUID.randomUUID().toString().replace("-", ""));
                        for (String col : CREATION_FIELDS) {
                            if (hasColumn(physicalColumns, col)) appendTsv(tsv, now);
                        }
                        for (String col : UPDATE_FIELDS) {
                            if (hasColumn(physicalColumns, col)) appendTsv(tsv, now);
                        }
                        if (hasColumn(physicalColumns, "load_user")) appendTsv(tsv, row.get("load_user") != null ? row.get("load_user") : row.get("creator"));

                        for (int i = 0; i < dataColumns.size(); i++) {
                            String col = dataColumns.get(i);
                            String colType = dataColumnTypes.get(i);
                            Object val = row.get(col);
                            val = convertValueForDb(val, colType);
                            
                            if (val != null && (col.toLowerCase().endsWith("_json") || col.toLowerCase().equals("extra_data") || "jsonb".equalsIgnoreCase(colType) || "json".equalsIgnoreCase(colType))) {
                                try { val = val instanceof String ? (String)val : objectMapper.writeValueAsString(val); } catch (Exception ignored) {}
                            }
                            appendTsv(tsv, val);
                        }
                        if (hasColumn(physicalColumns, "extra_data")) {
                            Object extra = row.get("extra_data");
                            try { appendTsv(tsv, extra != null ? (extra instanceof String ? (String)extra : objectMapper.writeValueAsString(extra)) : "{}"); } catch (Exception ignored) { appendTsv(tsv, "{}"); }
                        }
                        tsv.setCharAt(tsv.length() - 1, '\n');
                        
                        batchCount++;
                        if (batchCount % 500 == 0) {
                            byte[] bytes = tsv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            copyIn.writeToCopy(bytes, 0, bytes.length);
                            tsv.setLength(0);
                        }
                    }
                    if (tsv.length() > 0) {
                        byte[] bytes = tsv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        copyIn.writeToCopy(bytes, 0, bytes.length);
                    }
                    copyIn.endCopy();
                } catch (Exception e) {
                    if (copyIn.isActive()) {
                        copyIn.cancelCopy();
                    }
                    throw e;
                }
                return null;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private void appendTsv(StringBuilder sb, Object val) {
        if (val == null) sb.append("\\N\t");
        else {
            String s;
            if (val instanceof LocalDateTime) {
                s = ((LocalDateTime)val).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } else if (val instanceof java.sql.Time) {
                s = val.toString(); // HH:mm:ss
            } else if (val instanceof java.sql.Date) {
                s = val.toString(); // yyyy-MM-dd
            } else if (val instanceof java.sql.Timestamp) {
                s = val.toString(); // yyyy-MM-dd HH:mm:ss.xxx
            } else if (val instanceof Double || val instanceof Float || val instanceof java.math.BigDecimal) {
                // 使用 BigDecimal 转为 plain string，移除科学计数法 (如 2.0260315E7 -> 20260315)
                s = new java.math.BigDecimal(val.toString()).stripTrailingZeros().toPlainString();
            } else if (val instanceof java.util.Date) {
                s = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date)val);
            } else {
                s = val.toString().trim();
                // 彻底防御：如果是 .0 结尾或者科学计数法，二次平滑。兼容 POI formatCellValue 有时会带出的格式。
                if (s.contains(".") || s.contains("E") || s.contains("e")) {
                    if (NUMBER_PATTERN.matcher(s).matches()) {
                        try {
                            s = new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
                        } catch (Exception ignored) {}
                    }
                }
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') sb.append("\\\\");
                else if (c == '\t') sb.append("\\t");
                else if (c == '\n') sb.append("\\n");
                else if (c == '\r') sb.append("\\r");
                else sb.append(c);
            }
            sb.append('\t');
        }
    }

    public void updateRowData(String formId, String dataId, Map<String, Object> rowData, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String tableName = form.getTableName();
        String fullTableName = schema + "." + tableName;
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, tableName);

        StringJoiner sets = new StringJoiner(",");
        List<Object> args = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase()) || !hasColumn(physicalColumns, key)) continue;
            
            String colType = physicalColumns.get(key.toLowerCase());
            Object val = entry.getValue();
            val = convertValueForDb(val, colType);
            
            if (colType != null && !colType.equals("text") && !colType.equals("character varying")) {
                sets.add(String.format("\"%s\" = CAST(? AS %s)", key, colType));
            } else {
                sets.add("\"" + key + "\" = ?");
            }
            
            if (val != null && (key.toLowerCase().endsWith("_json") || key.toLowerCase().equals("extra_data") || "jsonb".equalsIgnoreCase(colType) || "json".equalsIgnoreCase(colType))) {
                try {
                    val = val instanceof String ? (String)val : objectMapper.writeValueAsString(val);
                } catch (Exception ignored) {}
            }
            args.add(val);
        }
        for (String col : UPDATE_FIELDS) {
            if (hasColumn(physicalColumns, col)) {
                sets.add("\"" + col + "\" = CURRENT_TIMESTAMP");
            }
        }

        if (sets.length() > 0) {
            args.add(dataId);
            jdbcTemplate.update(String.format("UPDATE %s SET %s WHERE \"id\" = ?", SqlUtil.quoteTable(fullTableName), sets.toString()), args.toArray());
        }
    }

    public Map<String, Object> getTableDataPage(String formId, int page, int size, Map<String, String> filters, String userEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (hasColumn(physicalColumns, "is_deleted")) where.append(" AND (is_deleted IS NULL OR is_deleted = 0) ");
        if (!isAdmin && userEmail != null && hasColumn(physicalColumns, "load_user")) {
            where.append(" AND (\"load_user\" = ? OR \"load_user\" IS NULL) ");
            args.add(userEmail);
        }

        if (filters != null) {
            for (Map.Entry<String, String> f : filters.entrySet()) {
                if (f.getValue() == null || f.getValue().trim().isEmpty()) continue;
                String physicalCol = resolvePhysicalColumn(physicalColumns, f.getKey());
                if (physicalCol != null) {
                    where.append(" AND CAST(\"").append(physicalCol).append("\" AS TEXT) LIKE ?");
                    args.add("%" + f.getValue() + "%");
                }
            }
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + SqlUtil.quoteTable(fullTableName) + where, Long.class, args.toArray());
        String order = hasColumn(physicalColumns, "w_insert_dt") ? " ORDER BY w_insert_dt DESC" : "";
        args.add(size); args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + SqlUtil.quoteTable(fullTableName) + where + order + " LIMIT ? OFFSET ?", args.toArray());

        Map<String, Object> res = new HashMap<>();
        res.put("total", total);
        res.put("records", rows);
        return res;
    }

    public Map<String, List<String>> getFilterOptions(String formId, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) return Collections.emptyMap();
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

        Map<String, List<String>> res = new LinkedHashMap<>();
        try {
            List<FieldDef> fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
            List<FieldDef> filterableFields = fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getFilterable()))
                .collect(java.util.stream.Collectors.toList());
            
            // 兼容前端 fallback 逻辑：如果没配置，默认取前三个
            if (filterableFields.isEmpty()) {
                filterableFields = fields.stream().limit(3).collect(java.util.stream.Collectors.toList());
            }

            for (FieldDef f : filterableFields) {
                String physicalCol = resolvePhysicalColumn(physicalColumns, f.getColumnName());
                if (physicalCol != null) {
                    StringBuilder sql = new StringBuilder("SELECT DISTINCT CAST(\"")
                        .append(physicalCol)
                        .append("\" AS TEXT) FROM ")
                        .append(SqlUtil.quoteTable(fullTableName))
                        .append(" WHERE \"")
                        .append(physicalCol)
                        .append("\" IS NOT NULL ");
                    
                    List<Object> args = new ArrayList<>();
                    if (hasColumn(physicalColumns, "is_deleted")) {
                        sql.append(" AND (is_deleted IS NULL OR is_deleted = 0) ");
                    }
                    if (!isAdmin && operatorEmail != null && hasColumn(physicalColumns, "load_user")) {
                        sql.append(" AND (\"load_user\" = ? OR \"load_user\" IS NULL) ");
                        args.add(operatorEmail);
                    }
                    sql.append(" LIMIT 100");
                    
                    List<String> options = jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
                    res.put(f.getColumnName(), options);
                }
            }
        } catch (Exception e) {
            log.error("Failed to get filter options for form {}: {}", formId, e.getMessage());
        }
        return res;
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void batchDeleteRowData(String formId, List<String> dataIds, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());
        
        boolean isHardDeleteMode = Boolean.TRUE.equals(form.getHardDelete());

        StringJoiner ps = new StringJoiner(",");
        for (int i = 0; i < dataIds.size(); i++) ps.add("?");
        
        if (hasColumn(physicalColumns, "is_deleted") && !isHardDeleteMode) {
            jdbcTemplate.update(String.format("UPDATE %s SET is_deleted=1 WHERE id IN (%s)", SqlUtil.quoteTable(fullTableName), ps), dataIds.toArray());
        } else {
            jdbcTemplate.update(String.format("DELETE FROM %s WHERE id IN (%s)", SqlUtil.quoteTable(fullTableName), ps), dataIds.toArray());
        }
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void deleteAllFilteredData(String formId, Map<String, String> filters, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Map<String, String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());
        
        boolean isHardDeleteMode = Boolean.TRUE.equals(form.getHardDelete());

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (!isAdmin && operatorEmail != null && hasColumn(physicalColumns, "load_user")) {
            where.append(" AND \"load_user\" = ? ");
            args.add(operatorEmail);
        }

        if (hasColumn(physicalColumns, "is_deleted") && !isHardDeleteMode) {
            jdbcTemplate.update(String.format("UPDATE %s SET is_deleted=1 %s", SqlUtil.quoteTable(fullTableName), where), args.toArray());
        } else {
            jdbcTemplate.update(String.format("DELETE FROM %s %s", SqlUtil.quoteTable(fullTableName), where), args.toArray());
        }
    }

    public void deleteRowData(String formId, String dataId, String operatorEmail, boolean isAdmin) {
        batchDeleteRowData(formId, Collections.singletonList(dataId), operatorEmail, isAdmin);
    }

    private void checkFillLock(String formId, String userEmail, boolean isAdmin) {}
}
