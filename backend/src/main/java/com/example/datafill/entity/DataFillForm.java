package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

@Data
@TableName("data_fill_form")
public class DataFillForm {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    
    private String name;        // 表单中文名
    @TableField("table_name")
    private String tableName;   // 要在数据库里创建的物理表名 (如 df_employee)
    @TableField(value = "table_comment", updateStrategy = FieldStrategy.IGNORED)
    private String tableComment; // 数据库表注释
    @TableField(value = "folder_id", updateStrategy = FieldStrategy.IGNORED)
    private String folderId;    // 所属目录ID，null 表示默认 (原未分类)
    private String forms;       // 字段定义的 JSON 字符串

    /** 表单状态：ACTIVE(可填报)、EXPIRED(已过期)、DISABLED(停用) */
    private String status;

    /** 填报截止时间（超过后默认不能再填报） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;

    /** 提前提醒天数（支持小数，如 0.5 代表提前 12 小时） */
    @TableField("reminder_days")
    private Double reminderDays;

    /** 提醒策略：DEADLINE=按固定截止时间一次性；MONTHLY=每月某日；WEEKLY=每周某天 */
    private String reminderMode;

    /** 当 reminderMode=MONTHLY 时，每月第几天（1-31），例如 10 代表每月10号 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer monthlyDay;

    /** 当 reminderMode=WEEKLY 时，每周第几天（1-7，1=周一...7=周日） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer weeklyDayOfWeek;

    /** 收件人邮箱列表的 JSON 数组字符串，如 ["a@xx.com","b@xx.com"] */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String recipientEmails;

    /** 允许填报的用户邮箱列表 JSON 字符串，如 ["u1@xx.com","u2@xx.com"]，为空表示所有用户都能填报 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String fillUserEmails;

    /** 填报周期天数（例如 1=每天可填一次；7=每7天可填一次；为空或<=0 表示只需填报一次） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer cycleDays;

    /** 提醒时间（HH:mm），例如 09:00；为空时默认 09:00 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String reminderTime;

    /** 固定期限模式下的提醒发送时间*/
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @TableField("reminder_date_time")
    private LocalDateTime reminderDateTime;

    @TableField("deadline_monthly_day")
    private Integer deadlineMonthlyDay;

    @TableField("deadline_weekly_day_of_week")
    private Integer deadlineWeeklyDayOfWeek;

    @TableField("deadline_time")
    private String deadlineTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /** 键值对配对规则配置 (用于导入时识别 JSON 字段) */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String kvConfig;

    /** 参考模板解析结果配置（用于回显表头映射、数据库字段参考等） */
    @TableField(value = "reference_template_config", updateStrategy = FieldStrategy.IGNORED)
    private String referenceTemplateConfig;

    /** 表单创建人 */
    private String creator;

    /** 数据库模式 (Schema) */
    @TableField("schema_name")
    private String schemaName;

    /** 是否强制硬删除（系统硬删代替软删） */
    @TableField("hard_delete")
    private Boolean hardDelete;

    /** 分组标识（用于分链接展示，如 link_a, link_b） */
    @TableField(value = "group_tag", updateStrategy = FieldStrategy.IGNORED)
    private String groupTag;

    /** 基于业务或填报规范的描述说明（展示在填报页顶部） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String description;

    @TableField("is_external")
    private Boolean isExternal;

    @TableField("pk_column")
    private String pkColumn;

    @TableField("insert_dt_column")
    private String insertDtColumn;

    @TableField("update_dt_column")
    private String updateDtColumn;

    @TableField("delete_flag_column")
    private String deleteFlagColumn;

    @TableField("allow_add")
    private Boolean allowAdd;   // 允许普通用户新增数据

    @TableField("allow_edit")
    private Boolean allowEdit;  // 允许普通用户修改现有数据

    @TableField("allow_delete")
    private Boolean allowDelete; // 允许普通用户删除现有数据
}
