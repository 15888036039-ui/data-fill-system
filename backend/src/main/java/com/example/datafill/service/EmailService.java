package com.example.datafill.service;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.mail.javamail.JavaMailSender;

import org.springframework.mail.javamail.MimeMessageHelper;

import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;

import jakarta.mail.internet.MimeMessage;

import java.time.LocalDateTime;

import java.time.format.DateTimeFormatter;

/**

 * 邮件发送服务

 */

@Slf4j

@Service

@RequiredArgsConstructor

public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")

    private String fromEmail;

    @Value("${data-fill.mail.subject-prefix:[美鹰·智数平台] }")
    private String subjectPrefix;

    @Value("${data-fill.mail.system-url:http://localhost:5173}")
    private String systemUrl;

    /**

     * 发送提醒邮件

     */

    public boolean sendReminderEmail(String to, String formName, LocalDateTime deadline, int daysLeft) {

        String subject = subjectPrefix + "数据填报提醒";

        String content = buildReminderContent(formName, deadline, daysLeft);

        return sendHtmlEmail(to, subject, content);

    }

    /**
     * 发送截止警告邮件
     */
    public boolean sendDeadlineWarningEmail(String to, String formName, LocalDateTime deadline, int daysLeft) {
        String subject = subjectPrefix + "数据填报截止警告";
        String content = buildDeadlineWarningContent(formName, deadline, daysLeft);
        return sendHtmlEmail(to, subject, content);
    }

    /**

     * 发送审批申请邮件给管理员

     */

    public boolean sendApprovalRequestEmail(String adminEmail, String applicantEmail, String formName, String reason) {

        String subject = subjectPrefix + "逾期填报审批申请";

        String content = buildApprovalRequestContent(applicantEmail, formName, reason);

        return sendHtmlEmail(adminEmail, subject, content);

    }

    /**

     * 发送审批结果邮件给申请人

     */

    public boolean sendApprovalResultEmail(String to, String formName, boolean approved, String comment) {

        String subject = subjectPrefix + "审批结果通知";

        String content = buildApprovalResultContent(formName, approved, comment);

        return sendHtmlEmail(to, subject, content);

    }

    /**

     * 发送HTML格式的邮件

     */

    private boolean sendHtmlEmail(String to, String subject, String htmlContent) {

        try {

            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);

            helper.setTo(to);

            helper.setSubject(subject);

            helper.setText(htmlContent, true);

            mailSender.send(message);

            log.info("邮件发送成功: {} -> {}", fromEmail, to);

            return true;

        } catch (MessagingException e) {

            log.error("邮件发送失败: {} -> {}, 错误: {}", fromEmail, to, e.getMessage());

            return false;

        }

    }

    /**

     * 构建提醒邮件内容

     */

    private String buildReminderContent(String formName, LocalDateTime deadline, long totalHours) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String deadlineStr = deadline.format(formatter);
        
        long days = totalHours / 24;
        long hours = totalHours % 24;
        String timeRemaining;
        if (days > 0) {
            timeRemaining = String.format("%d 天 %d 小时", days, hours);
        } else {
            timeRemaining = String.format("%d 小时", hours);
        }

        return """
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #e2e8f0; border-radius: 8px;">
                    <h2 style="color: #2563eb; margin-top: 0;">数据填报提醒</h2>
                    <p>您好！</p>
                    <p>您有以下数据填报任务需要完成：</p>
                    <div style="background-color: #f8fafc; padding: 15px; border-radius: 5px; margin: 20px 0; border-left: 4px solid #2563eb;">
                        <p><strong>表单名称：</strong>%s</p>
                        <p><strong>截止时间：</strong>%s</p>
                        <p><strong>剩余时间：</strong><span style="color: #ef4444; font-weight: bold;">%s</span></p>
                    </div>
                    <div style="margin: 20px 0; text-align: center;">
                        <a href="%s" style="background-color: #2563eb; color: white; padding: 10px 20px; text-decoration: none; border-radius: 4px; font-weight: bold;">立即前往填报</a>
                    </div>
                    <p>请及时登录系统完成数据填报，避免错过截止时间。</p>
                    <p style="word-break: break-all;">系统地址：<a href="%s" style="color: #2563eb;">%s</a></p>
                    <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 20px 0;">
                    <p style="color: #64748b; font-size: 12px; text-align: center;">
                        此邮件为 [美鹰·智数平台] 自动发送，请勿直接回复。
                    </p>
                </div>
            </body>
            </html>
            """.formatted(formName, deadlineStr, timeRemaining, systemUrl, systemUrl, systemUrl);
    }

    /**

     * 构建截止警告邮件内容

     */

    /**
     * 构建截止警告邮件内容
     */
    private String buildDeadlineWarningContent(String formName, LocalDateTime deadline, long totalHours) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String deadlineStr = deadline.format(formatter);

        long days = totalHours / 24;
        long hours = totalHours % 24;
        String timeRemaining;
        if (days > 0) {
            timeRemaining = String.format("%d 天 %d 小时", days, hours);
        } else {
            timeRemaining = String.format("%d 小时", hours);
        }

        return """
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #fee2e2; border-radius: 8px;">
                    <h2 style="color: #ef4444; margin-top: 0;">⚠️ 数据填报截止警告</h2>
                    <p>您好！</p>
                    <p>您的以下数据填报任务即将到期：</p>
                    <div style="background-color: #fef2f2; border: 1px solid #fecaca; padding: 15px; border-radius: 5px; margin: 20px 0; border-left: 4px solid #ef4444;">
                        <p><strong>表单名称：</strong>%s</p>
                        <p><strong>截止时间：</strong>%s</p>
                        <p><strong>剩余时间：</strong><span style="color: #ef4444; font-weight: bold;">%s</span></p>
                        <p style="color: #ef4444; font-weight: bold;">该表单即将停止接受新的填报数据，请尽快完成！</p>
                    </div>
                    <div style="margin: 20px 0; text-align: center;">
                        <a href="%s" style="background-color: #ef4444; color: white; padding: 10px 20px; text-decoration: none; border-radius: 4px; font-weight: bold;">立即前往系统</a>
                    </div>
                    <p>请在此时间点前及时完成数据填报，逾期后该表单将停止接收新的数据，请合理安排填报进度。</p>
                    <p style="word-break: break-all;">链接地址：<a href="%s" style="color: #ef4444;">%s</a></p>
                    <hr style="border: none; border-top: 1px solid #fee2e2; margin: 20px 0;">
                    <p style="color: #64748b; font-size: 12px; text-align: center;">
                        此邮件为 [美鹰·智数平台] 自动发送，请勿直接回复。
                    </p>
                </div>
            </body>
            </html>
            """.formatted(formName, deadlineStr, timeRemaining, systemUrl, systemUrl, systemUrl);
    }

    /**

     * 构建审批申请邮件内容

     */

    private String buildApprovalRequestContent(String applicantEmail, String formName, String reason) {

        return """

            <html>

            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">

                <div style="max-width: 600px; margin: 0 auto; padding: 20px;">

                    <h2 style="color: #f39c12;">审批申请通知</h2>

                    <p>管理员您好！</p>

                    <p>收到新的逾期填报审批申请：</p>

                    <div style="background-color: #fffbf0; border: 1px solid #f6e05e; padding: 15px; border-radius: 5px; margin: 20px 0;">

                        <p><strong>申请人：</strong>%s</p>

                        <p><strong>表单名称：</strong>%s</p>

                        <p><strong>申请理由：</strong>%s</p>

                    </div>

                    <p>请登录系统查看并处理该审批申请。</p>

                    <p style="color: #7f8c8d; font-size: 12px;">

                        此邮件由系统自动发送，请勿回复。

                    </p>

                </div>

            </body>

            </html>

            """.formatted(applicantEmail, formName, reason);

    }

    /**

     * 构建审批结果邮件内容

     */

    private String buildApprovalResultContent(String formName, boolean approved, String comment) {

        String statusColor = approved ? "#27ae60" : "#e74c3c";

        String statusText = approved ? "已批准" : "已拒绝";

        String statusIcon = approved ? "✅" : "❌";

        return """

            <html>

            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">

                <div style="max-width: 600px; margin: 0 auto; padding: 20px;">

                    <h2 style="color: %s;">%s 审批结果通知</h2>

                    <p>您好！</p>

                    <p>您的逾期填报审批申请结果如下：</p>

                    <div style="background-color: %s; border: 1px solid %s; padding: 15px; border-radius: 5px; margin: 20px 0;">

                        <p><strong>表单名称：</strong>%s</p>

                        <p><strong>审批结果：</strong><span style="font-weight: bold;">%s</span></p>

                        %s

                    </div>

                    %s

                    <p style="color: #7f8c8d; font-size: 12px;">

                        此邮件由系统自动发送，请勿回复。

                    </p>

                </div>

            </body>

            </html>

            """.formatted(

                statusColor,

                statusIcon + " " + statusText,

                approved ? "#f0f9ff" : "#fff5f5",

                statusColor,

                formName,

                statusText,

                comment != null && !comment.trim().isEmpty() ? "<p><strong>审批意见：</strong>" + comment + "</p>" : "",

                approved ? "<p>您现在可以继续填报该表单的数据了。</p>" : "<p>如有疑问，请联系管理员。</p>"

            );

    }

}