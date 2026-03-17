package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.dto.FieldDef;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.entity.UserFillLog;

import com.example.datafill.mapper.DataFillFormMapper;

import com.example.datafill.mapper.DynamicSqlMapper;

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

@Service

@RequiredArgsConstructor

public class DynamicTableDdlService {

    private final DataFillFormMapper formMapper;

    private final DynamicSqlMapper dynamicSqlMapper;

    private final UserFillLogMapper userFillLogMapper;

    private final ObjectMapper objectMapper;

    private final SchedulerService schedulerService;

    private final JdbcTemplate jdbcTemplate;

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

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

            throw new RuntimeException("字段 JSON 解析失败", e);

        }

        // 简单校验一下表名，必须是英文字母数字下划线

        if (!form.getTableName().matches("^[a-zA-Z0-9_]+$")) {

            throw new RuntimeException("物理表名只能包含字母、数字和下划线");

        }

        // 3. 拼接 PostgreSQL 建表 DDL 语句

        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE TABLE \"").append(form.getTableName()).append("\" ( ");

        // 强制带上主键ID字段

        ddl.append("\"id\" VARCHAR(50) PRIMARY KEY, ");

        java.util.Set<String> reservedNames = java.util.Set.of("id", "is_deleted", "w_insert_dt", "w_update_dt", "load_user", "job_instance", "extra_data");

        java.util.Set<String> seenNames = new java.util.HashSet<>();

        for (FieldDef field : fields) {

            String colName = field.getColumnName();

            if (colName == null || !colName.matches("^[a-zA-Z0-9_]+$")) {

                throw new RuntimeException("列名只能包含字母、数字和下划线: " + colName);

            }

            if (reservedNames.contains(colName.toLowerCase())) {

                if ("extra_data".equalsIgnoreCase(colName)) {

                    // 允许 extra_data 出现在字段列表中（用于视觉提示），但不需要为其生成额外的物理列 DDL

                    continue; 

                }

                throw new RuntimeException("列名 '" + colName + "' 是系统保留字段，不允许用户创建，请修改英文字段名");

            }

            if (!seenNames.add(colName.toLowerCase())) {

                throw new RuntimeException("检测到重复的英文字段名: " + colName);

            }

            ddl.append("\"").append(field.getColumnName()).append("\" ");

            // 简单的类型映射 (PGSQL 语法)

            switch (field.getType().toLowerCase()) {

                case "varchar":

                case "text":

                case "input":

                case "select":

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

                default:

                    ddl.append("VARCHAR(255)");

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

        ddl.append("\"is_deleted\" SMALLINT DEFAULT 0, ");

        ddl.append("\"extra_data\" JSONB");

        ddl.append(" );");

        // 4. 执行建表原生 SQL

        dynamicSqlMapper.executeDdl(ddl.toString());

        // 添加表注释和字段注释

        // 注释里的单引号要转义防注入

        dynamicSqlMapper.executeDdl("COMMENT ON TABLE \"" + form.getTableName() + "\" IS '" + form.getName().replace("'", "''") + "';");

        for (FieldDef field : fields) {

            dynamicSqlMapper.executeDdl("COMMENT ON COLUMN \"" + form.getTableName() + "\".\"" + field.getColumnName() + "\" IS '" + field.getName().replace("'", "''") + "';");

        }

        // 5. 将表单元数据存入元数据表

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

        // 新建表单时立即初始化循环任务的截止时间

        schedulerService.initOrRefreshDeadline(form, LocalDateTime.now());

        formMapper.insert(form);

        return form.getId();

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

            long timestamp = System.currentTimeMillis();

            String newTableName = form.getTableName() + "_del_" + timestamp;

            dynamicSqlMapper.executeDdl("ALTER TABLE \"" + form.getTableName() + "\" RENAME TO \"" + newTableName + "\"");

        } catch (Exception e) {

            // 忽略由于表不存在导致的错误

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

        // 提醒时间允许前端传 null 表示使用默认值

        if (incoming.getReminderTime() != null || incoming.getReminderTime() == null) {

            exist.setReminderTime(incoming.getReminderTime());

        }

        LocalDateTime now = LocalDateTime.now();

        exist.setUpdateTime(now);

        // 更新时重新核算截止时间

        schedulerService.initOrRefreshDeadline(exist, now);

        formMapper.updateById(exist);

    }

    /**

     * 3.5 按用户汇总任务列表（待填报 / 已过期）

     * （这个方法只读不涉及 DML 和大数据量，放在这里也可由原 Service 转移而来，或者 Controller 直接调用）

     */

    public Map<String, Object> getUserTasks(String userEmail) {

        LocalDateTime now = LocalDateTime.now();

        List<DataFillForm> allForms = formMapper.selectList(null);

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

            LocalDateTime nextFillTime = null;

            boolean completedCurrentCycle = false;

            if (cycleDays != null && cycleDays > 0 && lastSubmitTime != null) {

                nextFillTime = lastSubmitTime.plusDays(cycleDays);

                completedCurrentCycle = now.isBefore(nextFillTime);

            } else if (lastSubmitTime != null) {

                // 没有配置周期，只要填过一次就视为完成

                completedCurrentCycle = true;

            }

            long secondsLeft = 0;

            if (deadline != null && now.isBefore(deadline)) {

                secondsLeft = java.time.Duration.between(now, deadline).getSeconds();

            }

            Map<String, Object> item = new LinkedHashMap<>();

            item.put("formId", form.getId());

            item.put("name", form.getName());

            item.put("deadline", deadline);

            item.put("status", form.getStatus());

            item.put("secondsLeft", secondsLeft);

            item.put("nextFillTime", nextFillTime);

            if (isExpired) {

                expired.add(item);

            } else if (completedCurrentCycle) {

                completed.add(item);

            } else {

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

