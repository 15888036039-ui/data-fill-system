package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private String tableName;   // 要在数据库里创建的物理表名 (如 df_employee)
    private String forms;       // 字段定义的 JSON 字符串

    /** 表单状态：ACTIVE(可填报)、EXPIRED(已过期)、DISABLED(停用) */
    private String status;

    /** 填报截止时间（超过后默认不能再填报） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime deadline;

    /** 提醒天数（距离截止时间还剩多少天时发提醒，不配置默认 3 天） */
    private Integer reminderDays;

    /** 提醒策略：DEADLINE=按固定截止时间一次性；MONTHLY=每月某日；WEEKLY=每周某天 */
    private String reminderMode;

    /** 当 reminderMode=MONTHLY 时，每月第几天（1-31），例如 10 代表每月10号 */
    private Integer monthlyDay;

    /** 当 reminderMode=WEEKLY 时，每周第几天（1-7，1=周一...7=周日） */
    private Integer weeklyDayOfWeek;

    /** 收件人邮箱列表的 JSON 数组字符串，如 ["a@xx.com","b@xx.com"] */
    private String recipientEmails;

    /** 允许填报的用户邮箱列表 JSON 字符串，如 ["u1@xx.com","u2@xx.com"]，为空表示所有用户都能填报 */
    private String fillUserEmails;

    /** 填报周期天数（例如 1=每天可填一次；7=每7天可填一次；为空或<=0 表示只需填报一次） */
    private Integer cycleDays;

    /** 提醒时间（HH:mm），例如 09:00；为空时默认 09:00 */
    private String reminderTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /** 键值对配对规则配置 (用于导入时识别 JSON 字段) */
    private String kvConfig;
}
