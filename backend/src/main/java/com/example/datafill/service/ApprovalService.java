package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.example.datafill.entity.AdminApproval;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.mapper.AdminApprovalMapper;

import com.example.datafill.mapper.DataFillFormMapper;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import com.example.datafill.service.EmailService;
import java.time.LocalDateTime;

import java.util.List;

/**

 * 管理员批准服务

 */

@Slf4j

@Service

@RequiredArgsConstructor

public class ApprovalService {

    private final AdminApprovalMapper approvalMapper;

    private final DataFillFormMapper formMapper;

    private final NotificationService notificationService;

    private final EmailService emailService;

    /**

     * 提交逾期填报申请

     */

    @Transactional

    public String submitApprovalRequest(String formId, String applicantEmail, String applicantName, String reason) {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        // 检查是否已经存在待审批的申请

        QueryWrapper<AdminApproval> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("form_id", formId)

                   .eq("applicant_email", applicantEmail)

                   .eq("status", "PENDING");

        AdminApproval existingApproval = approvalMapper.selectOne(queryWrapper);

        if (existingApproval != null) {

            throw new RuntimeException("您已经有一个待审批的申请，请等待管理员处理");

        }

        AdminApproval approval = new AdminApproval();

        approval.setFormId(formId);

        approval.setApplicantEmail(applicantEmail);

        approval.setApplicantName(applicantName);

        approval.setReason(reason);

        approval.setStatus("PENDING");

        approval.setAppliedTime(LocalDateTime.now());

        approval.setCreateTime(LocalDateTime.now());

        approval.setUpdateTime(LocalDateTime.now());

        approvalMapper.insert(approval);

        // 创建审批申请通知

        notificationService.createApprovalRequestNotification(formId, applicantEmail, reason);

        log.info("提交审批申请成功: {} - {}", applicantEmail, form.getName());

        return approval.getId();

    }

    /**

     * 批准申请

     */

    @Transactional

    public void approveRequest(String approvalId, String approverEmail, String approvalComment) {

        AdminApproval approval = approvalMapper.selectById(approvalId);

        if (approval == null) {

            throw new RuntimeException("审批申请不存在");

        }

        if (!"PENDING".equals(approval.getStatus())) {

            throw new RuntimeException("该申请已被处理");

        }

        approval.setStatus("APPROVED");

        approval.setApproverEmail(approverEmail);

        approval.setApprovalComment(approvalComment);

        approval.setApprovedTime(LocalDateTime.now());

        approval.setUpdateTime(LocalDateTime.now());

        approvalMapper.updateById(approval);

        // 发送批准邮件给申请人

        DataFillForm form = formMapper.selectById(approval.getFormId());

        if (form != null) {

            emailService.sendApprovalResultEmail(

                approval.getApplicantEmail(),

                form.getName(),

                true,

                approvalComment

            );

        }

        log.info("批准申请成功: {} - {}", approvalId, approverEmail);

    }

    /**

     * 拒绝申请

     */

    @Transactional

    public void rejectRequest(String approvalId, String approverEmail, String approvalComment) {

        AdminApproval approval = approvalMapper.selectById(approvalId);

        if (approval == null) {

            throw new RuntimeException("审批申请不存在");

        }

        if (!"PENDING".equals(approval.getStatus())) {

            throw new RuntimeException("该申请已被处理");

        }

        approval.setStatus("REJECTED");

        approval.setApproverEmail(approverEmail);

        approval.setApprovalComment(approvalComment);

        approval.setApprovedTime(LocalDateTime.now());

        approval.setUpdateTime(LocalDateTime.now());

        approvalMapper.updateById(approval);

        // 发送拒绝邮件给申请人

        DataFillForm form = formMapper.selectById(approval.getFormId());

        if (form != null) {

            emailService.sendApprovalResultEmail(

                approval.getApplicantEmail(),

                form.getName(),

                false,

                approvalComment

            );

        }

        log.info("拒绝申请成功: {} - {}", approvalId, approverEmail);

    }

    /**

     * 获取待审批申请列表

     */

    public List<AdminApproval> getPendingApprovals() {

        return approvalMapper.selectPendingApprovals();

    }

    /**

     * 获取表单的审批记录

     */

    public List<AdminApproval> getApprovalsByFormId(String formId) {

        return approvalMapper.selectApprovalsByFormId(formId);

    }

    /**

     * 检查用户是否有有效的批准（用于逾期填报）

     */

    public boolean hasValidApproval(String formId, String applicantEmail) {

        QueryWrapper<AdminApproval> queryWrapper = new QueryWrapper<>();

        queryWrapper.eq("form_id", formId)

                   .eq("applicant_email", applicantEmail)

                   .eq("status", "APPROVED")

                   .orderByDesc("approved_time")

                   .last("LIMIT 1");

        AdminApproval approval = approvalMapper.selectOne(queryWrapper);

        if (approval == null) {

            return false;

        }

        // 检查批准是否在有效期内（例如7天内有效）

        LocalDateTime approvedTime = approval.getApprovedTime();

        LocalDateTime expiryTime = approvedTime.plusDays(7); // 批准有效期7天

        LocalDateTime now = LocalDateTime.now();

        return now.isBefore(expiryTime);

    }

}