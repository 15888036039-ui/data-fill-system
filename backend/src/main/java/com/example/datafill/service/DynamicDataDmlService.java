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
        "applicantemail", "applicantname", "applicant_email", "applicant_name"
    ));

    private java.util.Set<String> loadPhysicalColumns(String schema, String table) {
        if (schema == null || schema.trim().isEmpty()) {
            schema = SqlUtil.extractSchema(table);
            if (schema == null) {
                schema = "public";
            }
            table = SqlUtil.extractTable(table);
        }
        
        List<String> columns = jdbcTemplate.queryForList("SELECT column_name FROM " + "information_schema.columns" + " WHERE table_schema = ? AND table_name = ?", String.class, schema, table);
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

    private String resolvePhysicalColumn(java.util.Set<String> physicalColumns, String configuredColumn) {
        if (configuredColumn == null) return null;
        String raw = configuredColumn.trim();
        if (raw.isEmpty()) return null;
        String lower = raw.toLowerCase();
        if (physicalColumns.contains(lower)) return lower;

        String normalized = raw
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^a-zA-Z0-9_]", "")
                .toLowerCase();
        if (!normalized.trim().isEmpty() && physicalColumns.contains(normalized)) {
            return normalized;
        }
        return null;
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

        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, tableName);
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

        java.util.Set<String> jsonbCols = new java.util.HashSet<>();
        if (hasColumn(physicalColumns, "extra_data")) jsonbCols.add("extra_data");
        try {
            List<FieldDef> fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
            for (FieldDef f : fields) {
                if ("JSONB".equalsIgnoreCase(f.getDbType()) || (f.getColumnName() != null && f.getColumnName().toLowerCase().endsWith("_json"))) {
                    jsonbCols.add(f.getColumnName().toLowerCase());
                }
            }
        } catch (Exception ignored) {}

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase())) continue;
            columns.add("\"" + key + "\"");
            Object val = entry.getValue();
            placeholders.add("?");
            if (jsonbCols.contains(key.toLowerCase()) && val != null) {
                try {
                    org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                    pgObj.setType("jsonb");
                    pgObj.setValue(val instanceof String ? (String)val : objectMapper.writeValueAsString(val));
                    val = pgObj;
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
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, tableName);
        LocalDateTime now = LocalDateTime.now();

        List<FieldDef> fields;
        try {
            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
        } catch (Exception e) { throw new RuntimeException(e); }

        List<String> columns = new ArrayList<>();
        if (hasColumn(physicalColumns, "id")) columns.add("\"id\"");
        if (hasColumn(physicalColumns, "w_insert_dt")) columns.add("\"w_insert_dt\"");
        if (hasColumn(physicalColumns, "w_update_dt")) columns.add("\"w_update_dt\"");
        if (hasColumn(physicalColumns, "load_user")) columns.add("\"load_user\"");

        List<String> dataColumns = new ArrayList<>();
        for (FieldDef f : fields) {
            if (f.getColumnName() != null && !SYSTEM_FIELDS.contains(f.getColumnName().toLowerCase())) {
                dataColumns.add(f.getColumnName());
                columns.add("\"" + f.getColumnName() + "\"");
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
                    for (Map<String, Object> row : rows) {
                        StringBuilder tsv = new StringBuilder();
                        if (hasColumn(physicalColumns, "id")) appendTsv(tsv, java.util.UUID.randomUUID().toString().replace("-", ""));
                        if (hasColumn(physicalColumns, "w_insert_dt")) appendTsv(tsv, now);
                        if (hasColumn(physicalColumns, "w_update_dt")) appendTsv(tsv, now);
                        if (hasColumn(physicalColumns, "load_user")) appendTsv(tsv, row.get("load_user") != null ? row.get("load_user") : row.get("creator"));

                        for (String col : dataColumns) {
                            Object val = row.get(col);
                            if (val != null && (col.toLowerCase().endsWith("_json") || col.toLowerCase().equals("extra_data"))) {
                                try { val = val instanceof String ? (String)val : objectMapper.writeValueAsString(val); } catch (Exception ignored) {}
                            }
                            appendTsv(tsv, val);
                        }
                        if (hasColumn(physicalColumns, "extra_data")) {
                            Object extra = row.get("extra_data");
                            try { appendTsv(tsv, extra != null ? (extra instanceof String ? (String)extra : objectMapper.writeValueAsString(extra)) : "{}"); } catch (Exception ignored) { appendTsv(tsv, "{}"); }
                        }
                        tsv.setCharAt(tsv.length() - 1, '\n');
                        
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
            } else if (val instanceof Double || val instanceof Float || val instanceof java.math.BigDecimal) {
                // 使用 BigDecimal 转为 plain string，移除科学计数法 (如 2.0260315E7 -> 20260315)
                s = new java.math.BigDecimal(val.toString()).stripTrailingZeros().toPlainString();
            } else if (val instanceof java.util.Date) {
                s = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date)val);
            } else {
                s = val.toString().trim();
                // 彻底防御：如果是 .0 结尾或者科学计数法，二次平滑。兼容 POI formatCellValue 有时会带出的格式。
                if (s.contains(".") || s.contains("E") || s.contains("e")) {
                    try {
                        s = new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
                    } catch (Exception ignored) {}
                }
            }
            sb.append(s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")).append('\t');
        }
    }

    public void updateRowData(String formId, String dataId, Map<String, Object> rowData, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String tableName = form.getTableName();
        String fullTableName = schema + "." + tableName;
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, tableName);

        StringJoiner sets = new StringJoiner(",");
        List<Object> args = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String key = entry.getKey();
            if (SYSTEM_FIELDS.contains(key.toLowerCase()) || !hasColumn(physicalColumns, key)) continue;
            sets.add("\"" + key + "\" = ?");
            Object val = entry.getValue();
            if ((key.toLowerCase().endsWith("_json") || key.toLowerCase().equals("extra_data")) && val != null) {
                try {
                    org.postgresql.util.PGobject pgObj = new org.postgresql.util.PGobject();
                    pgObj.setType("jsonb");
                    pgObj.setValue(val instanceof String ? (String)val : objectMapper.writeValueAsString(val));
                    val = pgObj;
                } catch (Exception ignored) {}
            }
            args.add(val);
        }
        if (hasColumn(physicalColumns, "w_update_dt")) sets.add("\"w_update_dt\" = CURRENT_TIMESTAMP");

        if (sets.length() > 0) {
            args.add(dataId);
            jdbcTemplate.update(String.format("UPDATE %s SET %s WHERE \"id\" = ?", SqlUtil.quoteTable(fullTableName), sets.toString()), args.toArray());
        }
    }

    public Map<String, Object> getTableDataPage(String formId, int page, int size, Map<String, String> filters, String userEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

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
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

        Map<String, List<String>> res = new LinkedHashMap<>();
        try {
            List<FieldDef> fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
            for (FieldDef f : fields) {
                if (Boolean.TRUE.equals(f.getFilterable()) && hasColumn(physicalColumns, f.getColumnName())) {
                    List<String> options = jdbcTemplate.queryForList("SELECT DISTINCT CAST(\"" + f.getColumnName() + "\" AS TEXT) FROM " + SqlUtil.quoteTable(fullTableName) + " WHERE \"" + f.getColumnName() + "\" IS NOT NULL LIMIT 100", String.class);
                    res.put(f.getColumnName(), options);
                }
            }
        } catch (Exception ignored) {}
        return res;
    }

    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public void batchDeleteRowData(String formId, List<String> dataIds, String operatorEmail, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String fullTableName = schema + "." + form.getTableName();
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

        StringJoiner ps = new StringJoiner(",");
        for (int i = 0; i < dataIds.size(); i++) ps.add("?");
        
        if (hasColumn(physicalColumns, "is_deleted")) {
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
        java.util.Set<String> physicalColumns = loadPhysicalColumns(schema, form.getTableName());

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (!isAdmin && operatorEmail != null && hasColumn(physicalColumns, "load_user")) {
            where.append(" AND \"load_user\" = ? ");
            args.add(operatorEmail);
        }

        if (hasColumn(physicalColumns, "is_deleted")) {
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
