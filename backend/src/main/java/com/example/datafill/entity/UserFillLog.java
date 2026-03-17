package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_fill_log")
public class UserFillLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String formId;      // 表单ID
    private String dataId;      // 对应动态物理表中的数据ID
    private String userEmail;   // 用户邮箱（帆软传入）
    private LocalDateTime submitTime; // 提交时间

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

