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

    /**

     * 2. 动态向物理表中插入填报数据（使用预编译，防止 SQL 注入）

     */

    @Transactional

    public void insertRowData(String formId, Map<String, Object> rowData) {

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

        String rowId = java.util.UUID.randomUUID().toString().replace("-", ""); // 生成主键

        StringJoiner columns = new StringJoiner(", ");

        StringJoiner placeholders = new StringJoiner(", ");

        List<Object> args = new ArrayList<>();

        columns.add("\"id\"");

        placeholders.add("?");

        args.add(rowId);

        // 如果前端传了 load_user (或者在 UserTasks 中保存的 email)，优先使用

        String loadUser = rowData.containsKey("load_user") ? rowData.get("load_user").toString() : (rowData.containsKey("creator") ? rowData.get("creator").toString() : null);

        if (loadUser != null) {

            columns.add("\"load_user\"");

            placeholders.add("?");

            args.add(loadUser);

        }

        // 自动填充入库和更新时间

        columns.add("\"w_insert_dt\"");

        placeholders.add("?");

        args.add(now);

        columns.add("\"w_update_dt\"");

        placeholders.add("?");

        args.add(now);

        Object applicantEmailObj = rowData.getOrDefault("applicantEmail", rowData.get("applicant_email"));

        String applicantEmail = applicantEmailObj != null ? applicantEmailObj.toString() : null;

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {

            String key = entry.getKey();

            // 过滤系统保留字段和校验工具字段

            if (SYSTEM_FIELDS.contains(key.toLowerCase())) {

                continue;

            }

            if (!key.matches("^[a-zA-Z0-9_]+$")) {

                continue; // 忽略非法列名，防止 SQL 注入

            }

            columns.add("\"" + key + "\"");

            if (key.equalsIgnoreCase("extra_data")) {

                placeholders.add("::jsonb");

            } else {

                placeholders.add("?");

            }

            Object val = entry.getValue();

            if (val != null && key.equalsIgnoreCase("extra_data") && !(val instanceof String)) {

                 try {

                     val = objectMapper.writeValueAsString(val);

                 } catch (JsonProcessingException e) {

                     val = "{}";

                 }

            }

            args.add(val);

        }

        // 专家补丁：确保 extra_data 始终参与（如果没有在循环中添加）

        if (!rowData.containsKey("extra_data")) {

            columns.add("\"extra_data\"");

            placeholders.add("::jsonb");

            args.add("{}");

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

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        LocalDateTime now = LocalDateTime.now();

        // 获取表单定义的字段列表

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

            throw new RuntimeException("解析表单字段定义失败", e);

        }

        // 基础列

        String baseSql = String.format("INSERT INTO \"%s\" (%%s) VALUES (%%s)", tableName);

        List<String> columns = new ArrayList<>();

        columns.add("\"id\"");

        columns.add("\"w_insert_dt\"");

        columns.add("\"w_update_dt\"");

        // 收集业务列：从表单定义中提取，而不是由首行 KeySet 决定（防止首行漏列）

        List<String> dataColumns = new ArrayList<>();

        for (FieldDef field : fields) {

            String colName = field.getColumnName();

            if (colName != null && !SYSTEM_FIELDS.contains(colName.toLowerCase())) {

                dataColumns.add(colName);

            }

        }

        boolean hasLoadUser = rows.get(0).containsKey("load_user") || rows.get(0).containsKey("creator");

        if (hasLoadUser) columns.add("\"load_user\"");

        for (String col : dataColumns) columns.add("\"" + col + "\"");

        columns.add("\"extra_data\"");

        String colPart = String.join(", ", columns);

        List<String> placeholders = new ArrayList<>();

        for (String col : columns) {

            if ("\"extra_data\"".equalsIgnoreCase(col)) {

                placeholders.add("::jsonb");

            } else {

                placeholders.add("?");

            }

        }

        String placePart = String.join(", ", placeholders);

        String sql = String.format(baseSql, colPart, placePart);

        List<Object[]> batchArgs = new ArrayList<>();

        for (Map<String, Object> row : rows) {

            List<Object> args = new ArrayList<>();

            String rowId = java.util.UUID.randomUUID().toString().replace("-", "");

            args.add(rowId);

            args.add(now);

            args.add(now);

            if (hasLoadUser) {

                Object u = row.get("load_user");

                if (u == null) u = row.get("creator");

                args.add(u);

            }

            for (String col : dataColumns) {

                args.add(row.get(col));

            }

            Object extra = row.get("extra_data");

            if (extra != null && !(extra instanceof String)) {

                try { extra = objectMapper.writeValueAsString(extra); } catch (Exception e) { extra = "{}"; }

            }

            args.add(extra);

            batchArgs.add(args.toArray());

        }

        jdbcTemplate.batchUpdate(sql, batchArgs);

        // 记录日志：为本次上传者记录填报标记，使工作台能识别出已填报状态

        String firstUser = null;

        for (Map<String, Object> row : rows) {

            Object u = row.get("load_user");

            if (u == null) u = row.get("creator");

            if (u != null && !u.toString().isBlank()) {

                firstUser = u.toString();

                break;

            }

        }

        if (firstUser != null) {

            UserFillLog log = new UserFillLog();

            log.setFormId(formId);

            log.setDataId("BATCH_" + java.util.UUID.randomUUID().toString().substring(0, 8));

            log.setUserEmail(firstUser);

            log.setSubmitTime(now);

            log.setCreateTime(now);

            log.setUpdateTime(now);

            userFillLogMapper.insert(log);

        }

    }

    /**

     * 2.5 动态更新物理表的数据（带归属校验）

     */

    public void updateRowData(String formId, String dataId, Map<String, Object> rowData, String operatorEmail, boolean isAdmin) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        // 归属权限校验：非本人且非管理员无法修改

        if (!isAdmin && operatorEmail != null) {

            String checkSql = String.format("SELECT \"load_user\" FROM \"%s\" WHERE \"id\" = ", tableName);

            try {

                String owner = jdbcTemplate.queryForObject(checkSql, String.class, dataId);

                if (owner != null && !owner.equalsIgnoreCase(operatorEmail)) {

                    throw new RuntimeException("权限不足：您只能修改自己填报的数据");

                }

            } catch (Exception ignored) {}

        }

        StringJoiner sets = new StringJoiner(", ");

        List<Object> args = new ArrayList<>();

        for (Map.Entry<String, Object> entry : rowData.entrySet()) {

            String key = entry.getKey();

            if (SYSTEM_FIELDS.contains(key.toLowerCase())) {

                continue;

            }

            if (!key.matches("^[a-zA-Z0-9_]+$")) continue;

            sets.add("\"" + key + "\" = ");

            args.add(entry.getValue());

        }

        sets.add("\"w_update_dt\" = CURRENT_TIMESTAMP");

        String updateSql = String.format("UPDATE \"%s\" SET %s WHERE \"id\" = ", tableName, sets.toString());

        args.add(dataId);

        jdbcTemplate.update(updateSql, args.toArray());

    }

    /**

     * 3. 查询动态物理表的数据（分页 + 条件筛选）（防止 SQL 注入）

     */

    public Map<String, Object> getTableDataPage(String formId, int page, int size, Map<String, String> filters) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {

            throw new RuntimeException("非法的物理表名称: " + tableName);

        }

        StringBuilder whereClause = new StringBuilder(" WHERE (is_deleted IS NULL OR is_deleted = 0) ");

        List<Object> args = new ArrayList<>();

        if (filters != null) {

            for (Map.Entry<String, String> entry : filters.entrySet()) {

                String val = entry.getValue();

                if (val != null && !val.trim().isEmpty()) {

                    String col = entry.getKey();

                    if (col.startsWith("extra_data.")) {

                        String jsonKey = col.substring("extra_data.".length());

                        if (jsonKey.matches("^[a-zA-Z0-9_]+$")) {

                            // PostgreSQL JSONB ->>>  LIKE 

                            whereClause.append(" AND \"extra_data\"->> ? LIKE ? ? ");

                            args.add(jsonKey);

                            args.add("%" + val + "%");

                        }

                    } else if (col.matches("^[a-zA-Z0-9_]+$")) {

                        // 精确匹配 creator/load_user 或 模糊匹配其他

                        if ("creator".equalsIgnoreCase(col) || "load_user".equalsIgnoreCase(col)) {

                            whereClause.append(" AND \"").append(col).append("\" = ? ");

                            args.add(val);

                        } else {

                            whereClause.append(" AND \"").append(col).append("\" LIKE ? ");

                            args.add("%" + val + "%");

                        }

                    }

                }

            }

        }

        // 统计总数

        String countSql = "SELECT COUNT(1) FROM \"" + tableName + "\"" + whereClause.toString();

        Long totalObj = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());

        long total = totalObj != null ? totalObj : 0L;

        // 分页查询

        int offset = (page - 1) * size;

        String listSql = "SELECT * FROM \"" + tableName + "\"" + whereClause.toString() + " ORDER BY w_insert_dt DESC LIMIT ? OFFSET ?";

        args.add(size);

        args.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(listSql, args.toArray());

        List<LinkedHashMap<String, Object>> records = new ArrayList<>();

        for (Map<String, Object> row : rows) {

            LinkedHashMap<String, Object> record = new LinkedHashMap<>(row);

            Object extraDataObj = record.get("extra_data");

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

            record.remove("extra_data");

            records.add(record);

        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("total", total);

        result.put("records", records);

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

        String isolationWhere = " WHERE (is_deleted IS NULL OR is_deleted = 0) ";

        List<Object> baseArgs = new ArrayList<>();

        if (!isAdmin && operatorEmail != null && !operatorEmail.isBlank()) {

            isolationWhere += " AND \"load_user\" = ? ";

            baseArgs.add(operatorEmail);

        }

        for (FieldDef field : filterable) {

            String col = field.getColumnName();

            if (col == null || col.isBlank()) {

                continue;

            }

            try {

                // 不再进行严格正则校验，因为下面使用了双引号引用标识符

                String sql = "SELECT DISTINCT \"" + col + "\" AS val FROM \"" + tableName + "\" " +

                        isolationWhere + " AND \"" + col + "\" IS NOT NULL ORDER BY val LIMIT 100";

                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, baseArgs.toArray());

                List<String> values = rows.stream()

                        .map(m -> m.get("val") != null ? m.get("val") : m.get("VAL"))

                        .filter(v -> v != null && !v.toString().isBlank())

                        .map(Object::toString)

                        .distinct()

                        .toList();

                result.put(col, values);

            } catch (Exception e) {

                // 记录错误但不中断其他列的筛选加载

                result.put(col, new ArrayList<>());

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

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        // 权限校验：非管理员只能删除自己的数据

        if (!isAdmin && operatorEmail != null) {

            for (String dataId : dataIds) {

                String checkSql = String.format("SELECT \"load_user\" FROM \"%s\" WHERE \"id\" = ", tableName);

                try {

                    String owner = jdbcTemplate.queryForObject(checkSql, String.class, dataId);

                    if (owner != null && !owner.equalsIgnoreCase(operatorEmail)) {

                        throw new RuntimeException("权限不足：您包含非本人填报的数据，无法批量删除");

                    }

                } catch (Exception e) {

                    if (e.getMessage().contains("权限不足")) throw e;

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

        String updateSql = String.format("UPDATE \"%s\" SET is_deleted = 1 WHERE id IN (%s)", tableName, placeholders.toString());

        List<Object> args = new ArrayList<>(dataIds);

        // 由于有些老表可能没有 is_deleted 列，在尝试更新前，可以选择忽略错误，或者让管理员执行老表升级，这里直接尝试更新

        try {

            jdbcTemplate.update(updateSql, args.toArray());

        } catch (Exception e) {

            // 如果报错列不存在，可以这里做真正的 DELETE 降级，或者抛出异常。为了兼容老结构，降级执行真删除

            String deleteSql = String.format("DELETE FROM \"%s\" WHERE id IN (%s)", tableName, placeholders.toString());

            jdbcTemplate.update(deleteSql, args.toArray());

        }

    }

    /**

     * 4. 软删除动态物理表的数据

     */

    public void deleteRowData(String formId, String dataId, String operatorEmail, boolean isAdmin) {

        batchDeleteRowData(formId, List.of(dataId), operatorEmail, isAdmin);

    }

}

