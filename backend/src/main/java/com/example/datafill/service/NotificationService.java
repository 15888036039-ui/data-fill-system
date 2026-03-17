package com.example.datafill.service;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.entity.EmailNotification;

import com.example.datafill.mapper.DataFillFormMapper;

import com.example.datafill.mapper.EmailNotificationMapper;

import com.example.datafill.mapper.UserFillLogMapper;

import com.example.datafill.entity.UserFillLog;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.List;

import java.util.Map;

/**

 * 邮件通知服务

 */

@Slf4j

@Service

@RequiredArgsConstructor

public class NotificationService {

    private final EmailNotificationMapper notificationMapper;

    private final DataFillFormMapper formMapper;

    private final UserFillLogMapper userFillLogMapper;

    private final EmailService emailService;

    private final ObjectMapper objectMapper;

    private final JdbcTemplate jdbcTemplate;

    /**

     * 创建提醒通知

     */

    @Transactional

    public void createReminderNotification(String formId, LocalDateTime scheduledTime) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            log.warn("表单不存在: {}", formId);

            return;

        }

        // 避免同一表单、同一时间点重复创建提醒通知

        int existCount = notificationMapper.countNotificationByFormAndTypeAndTime(formId, "REMINDER", scheduledTime);

        if (existCount > 0) {

            log.info("表单 {} 在 {} 的提醒通知已存在，跳过创建", formId, scheduledTime);

            return;

        }

        // 解析收件人邮箱列表

        List<String> recipients = parseRecipientEmails(form.getRecipientEmails());

        if (recipients.isEmpty()) {

            log.warn("表单 {} 没有配置收件人邮箱", formId);

            return;

        }

        if (form.getDeadline() != null && scheduledTime.isAfter(form.getDeadline())) {

            log.warn("表单 {} 截止时间已过，不创建提醒通知", formId);

            return;

        }

        LocalDateTime cycleStart = calculateCycleStartTime(form.getReminderMode(), form.getDeadline());

        for (String email : recipients) {

            if (hasUserFilledInCycle(formId, email, cycleStart)) {

                log.info("用户 {} 在当前周期(自 {}) 已填报表单 {}，跳过发送提醒", email, cycleStart, formId);

                continue;

            }

            EmailNotification notification = new EmailNotification();

            notification.setFormId(formId);

            notification.setRecipientEmail(email);

            notification.setNotificationType("REMINDER");

            notification.setSubject("数据填报提醒 - " + form.getName());

            notification.setContent("您有数据填报任务即将到期，请及时完成。");

            notification.setStatus("PENDING");

            notification.setScheduledTime(scheduledTime);

            notification.setCreateTime(LocalDateTime.now());

            notification.setUpdateTime(LocalDateTime.now());

            notificationMapper.insert(notification);

            log.info("创建提醒通知: {} -> {}", email, scheduledTime);

        }

    }

    /**

     * 创建截止警告通知

     */

    @Transactional

    public void createDeadlineWarningNotification(String formId, LocalDateTime scheduledTime) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            log.warn("表单不存在: {}", formId);

            return;

        }

        List<String> recipients = parseRecipientEmails(form.getRecipientEmails());

        if (recipients.isEmpty()) {

            log.warn("表单 {} 没有配置收件人邮箱", formId);

            return;

        }

        LocalDateTime cycleStart = calculateCycleStartTime(form.getReminderMode(), form.getDeadline());

        for (String email : recipients) {

            if (hasUserFilledInCycle(formId, email, cycleStart)) {

                log.info("用户 {} 面临截稿(自 {}) 但已填报表单 {}，跳过发送截止警告", email, cycleStart, formId);

                continue;

            }

            EmailNotification notification = new EmailNotification();

            notification.setFormId(formId);

            notification.setRecipientEmail(email);

            notification.setNotificationType("DEADLINE_WARNING");

            notification.setSubject("数据填报截止警告 - " + form.getName());

            notification.setContent("数据填报已截止，如需继续填报请申请管理员批准。");

            notification.setStatus("PENDING");

            notification.setScheduledTime(scheduledTime);

            notification.setCreateTime(LocalDateTime.now());

            notification.setUpdateTime(LocalDateTime.now());

            notificationMapper.insert(notification);

            log.info("创建截止警告通知: {} -> {}", email, scheduledTime);

        }

    }

    /**

     * 创建审批申请通知

     */

    @Transactional

    public void createApprovalRequestNotification(String formId, String applicantEmail, String reason) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            log.warn("表单不存在: {}", formId);

            return;

        }

        // 管理员邮箱从配置或表单配置中获取，暂时使用全局配置优先，其次备用固定值

        String adminEmail = form.getRecipientEmails() != null && !form.getRecipientEmails().isBlank()
                 ? parseRecipientEmails(form.getRecipientEmails()).stream().findFirst().orElse("admin@company.com")
                : "admin@company.com";

        EmailNotification notification = new EmailNotification();

        notification.setFormId(formId);

        notification.setRecipientEmail(adminEmail);

        notification.setNotificationType("APPROVAL_REQUEST");

        notification.setSubject("逾期填报审批申请 - " + form.getName());

        // 将申请人和理由放到 JSON 里，后续发送时再解析

        try {

            notification.setContent(objectMapper.writeValueAsString(Map.of(

                    "applicantEmail", applicantEmail,

                    "reason", reason

            )));

        } catch (Exception e) {

            notification.setContent("{}");

        }

        notification.setStatus("PENDING");

        notification.setScheduledTime(LocalDateTime.now()); // 立即发送

        notification.setCreateTime(LocalDateTime.now());

        notification.setUpdateTime(LocalDateTime.now());

        notificationMapper.insert(notification);

        log.info("创建审批申请通知: {} -> {}", adminEmail, applicantEmail);

    }

    /**

     * 处理待发送的通知

     */

    @Transactional

    public void processPendingNotifications() {

        LocalDateTime now = LocalDateTime.now();

        List<EmailNotification> pendingNotifications = notificationMapper.selectPendingNotifications(now);

        log.info("发现 {} 条待发送通知", pendingNotifications.size());

        for (EmailNotification notification : pendingNotifications) {

            try {

                boolean success = sendNotification(notification);

                if (success) {

                    notification.setStatus("SENT");

                    notification.setSentTime(now);

                    notification.setUpdateTime(now);

                    notificationMapper.updateById(notification);

                    log.info("通知发送成功: {}", notification.getId());

                } else {

                    notification.setStatus("FAILED");

                    notification.setErrorMessage("邮件发送失败");

                    notification.setUpdateTime(now);

                    notificationMapper.updateById(notification);

                    log.warn("通知发送失败: {}", notification.getId());

                }

            } catch (Exception e) {

                log.error("处理通知异常: {}", notification.getId(), e);

                notification.setStatus("FAILED");

                notification.setErrorMessage(e.getMessage());

                notification.setUpdateTime(now);

                notificationMapper.updateById(notification);

            }

        }

    }

    /**

     * 发送通知邮件

     */

    private boolean sendNotification(EmailNotification notification) {

        DataFillForm form = formMapper.selectById(notification.getFormId());

        if (form == null) {

            return false;

        }

        switch (notification.getNotificationType()) {

            case "REMINDER" -> {

                int daysLeft = calculateDaysLeft(form.getDeadline(), notification.getScheduledTime());

                return emailService.sendReminderEmail(

                    notification.getRecipientEmail(),

                    form.getName(),

                    form.getDeadline(),

                    daysLeft

                );

            }

            case "DEADLINE_WARNING" -> {

                return emailService.sendDeadlineWarningEmail(

                    notification.getRecipientEmail(),

                    form.getName(),

                    form.getDeadline()

                );

            }

            case "APPROVAL_REQUEST" -> {

                String applicantEmail = "unknown@example.com";

                String reason = "逾期填报申请";

                try {

                    if (notification.getContent() != null && !notification.getContent().isBlank()) {

                        Map<String, Object> obj = objectMapper.readValue(notification.getContent(), new TypeReference<Map<String, Object>>() {});

                        Object ae = obj.get("applicantEmail");

                        Object rs = obj.get("reason");

                        if (ae != null && !ae.toString().isBlank()) {

                            applicantEmail = ae.toString();

                        }

                        if (rs != null && !rs.toString().isBlank()) {

                            reason = rs.toString();

                        }

                    }

                } catch (Exception e) {

                    log.warn("解析审批申请通知内容失败: {}", notification.getId(), e);

                }

                return emailService.sendApprovalRequestEmail(

                        notification.getRecipientEmail(),

                        applicantEmail,

                        form.getName(),

                        reason

                );

            }

            default -> {

                log.warn("未知通知类型: {}", notification.getNotificationType());

                return false;

            }

        }

    }

    /**

     * 解析收件人邮箱列表

     */

    private List<String> parseRecipientEmails(String recipientEmailsJson) {

        if (recipientEmailsJson == null || recipientEmailsJson.trim().isEmpty()) {

            return List.of();

        }

        try {

            return objectMapper.readValue(recipientEmailsJson, new TypeReference<List<String>>() {});

        } catch (Exception e) {

            log.error("解析收件人邮箱失败: {}", recipientEmailsJson, e);

            return List.of();

        }

    }

    /**

     * 计算剩余天数

     */

    private int calculateDaysLeft(LocalDateTime deadline, LocalDateTime referenceTime) {

        if (deadline == null || referenceTime == null) {

            return 0;

        }

        return (int) java.time.Duration.between(referenceTime, deadline).toDays();

    }

    /**

     * 获取表单的已发送通知

     */

    public List<EmailNotification> getSentNotificationsByFormId(String formId) {

        return notificationMapper.selectSentNotificationsByFormId(formId);

    }

    /**

     * 计算当前填报周期的开始时间

     */

    private LocalDateTime calculateCycleStartTime(String mode, LocalDateTime deadline) {

        if (deadline == null) {

            return LocalDateTime.MIN; // 如果没有截止时间，视为从一开始计算

        }

        if ("MONTHLY".equalsIgnoreCase(mode)) {

            return deadline.minusMonths(1);

        } else if ("WEEKLY".equalsIgnoreCase(mode)) {

            return deadline.minusDays(7);

        } else {

            // "DEADLINE" 一次性任务

            return LocalDateTime.MIN;

        }

    }

    /**

     * 判断用户是否在当前周期内已填报过

     */

    private boolean hasUserFilledInCycle(String formId, String userEmail, LocalDateTime cycleStart) {

        if (userEmail == null || userEmail.isBlank()) {

            return false;

        }

        UserFillLog lastLog = userFillLogMapper.selectLastByFormAndUser(formId, userEmail);

        if (lastLog == null || lastLog.getSubmitTime() == null) {

            // 增强逻辑：如果日志不存在，尝试从物理表直接探测数据

            DataFillForm form = formMapper.selectById(formId);

            if (form != null && form.getTableName() != null) {

                try {

                    String checkSql = String.format("SELECT MAX(w_insert_dt) FROM \"%s\" WHERE load_user = ?", form.getTableName());

                    LocalDateTime tableSubmitTime = jdbcTemplate.queryForObject(checkSql, LocalDateTime.class, userEmail);

                    if (tableSubmitTime != null) {

                        return tableSubmitTime.isAfter(cycleStart);

                    }

                } catch (Exception ignored) {

                    // 表不存在或字段缺失等异常，忽略

                }

            }

            return false;

        }

        // 如果用户的最后一次提交时间 > 当前周期的开始时间，说明本期已经填过了

        return lastLog.getSubmitTime().isAfter(cycleStart);

    }

}