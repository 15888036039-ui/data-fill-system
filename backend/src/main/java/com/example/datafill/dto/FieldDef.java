package com.example.datafill.dto;

import lombok.Data;
import java.util.List;

@Data
public class FieldDef {
    private String columnName; // 数据库列名，例如: emp_name
    private String originalColumnName; // 编辑时用于追踪原始列名，支持重命名已有物理列
    private String name;       // 显示名称，例如: 员工姓名
    private String type;       // 类型：varchar, int, decimal, datetime 等
    private Integer length;    // 长度：如 255
    private Boolean required;  // 是否必填
    private Boolean filterable;// 是否作为查询筛选条件
    private String dbType;     // 物理数据库类型，例如: VARCHAR(255), INTEGER, TEXT
    private List<String> options; // 下拉框选项 (仅给前端使用，建表时忽略)
    
    // 校验相关
    private String pattern;      // 正则校验表达式
    private String patternMsg;   // 正则校验失败提示
    private Double min;          // 最小值 (仅数字)
    private Double max;          // 最大值 (仅数字)
    private Integer minLength;   // 最小长度 (仅文本)
    private Integer maxLength;   // 最大长度 (仅文本与字段定义一致)
    private Boolean hideInForm;  // 是否在填报模版/表单中隐藏
    private Boolean hideInList;  // 是否在列表展示中隐藏
    private Boolean systemLocked; // 是否为系统锁定字段 (禁止修改列名或删除)
    private List<String> visibleEmails; // 填报可见权限控制：允许查看/填报此字段的邮箱列表，不配置则所有人可见

    // 进阶校验：自定义 SQL 校验 (维度表关联)
    private String validationSql;    // 自定义校验 SQL，如：SELECT 1 FROM dim_table WHERE code = :val
    private String validationSqlMsg; // 校验失败时的提示语

    // 筛选器配置
    private String filterType;       // 筛选控件类型：input(输入框), select(下拉框)
    private List<String> filterOptions; // 筛选下拉框选项
    private String filterOptionsSql;    // 筛选下拉选项 SQL
}
