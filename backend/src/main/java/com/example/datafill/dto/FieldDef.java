package com.example.datafill.dto;

import lombok.Data;
import java.util.List;

@Data
public class FieldDef {
    private String columnName; // 数据库列名，例如: emp_name
    private String name;       // 显示名称，例如: 员工姓名
    private String type;       // 类型：varchar, int, decimal, datetime 等
    private Integer length;    // 长度：如 255
    private Boolean required;  // 是否必填
    private Boolean filterable;// 是否作为查询筛选条件
    private String dbType;     // 物理数据库类型，例如: VARCHAR(255), INTEGER, TEXT
    private List<String> options; // 下拉框选项 (仅给前端使用，建表时忽略)
}
