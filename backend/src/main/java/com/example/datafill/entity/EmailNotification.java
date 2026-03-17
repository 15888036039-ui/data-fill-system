package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("email_notification")
public class EmailNotification {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String formId;              // 关联的表单ID
    private String recipientEmail;      // 收件人邮箱
    private String notificationType;    // 通知类型: REMINDER(提醒), DEADLINE_WARNING(截止警告), APPROVAL_REQUEST(审批请求)
    private String subject;             // 邮件主题
    private String content;             // 邮件内容
    private String status;              // 发送状态: PENDING(待发送), SENT(已发送), FAILED(发送失败)
    private LocalDateTime scheduledTime; // 计划发送时间
    private LocalDateTime sentTime;     // 实际发送时间
    private String errorMessage;        // 发送失败时的错误信息

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}