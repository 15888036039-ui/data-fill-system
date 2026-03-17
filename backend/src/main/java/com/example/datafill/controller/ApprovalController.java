package com.example.datafill.controller;

import com.example.datafill.entity.AdminApproval;
import com.example.datafill.service.ApprovalService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/request")
    public String submitApprovalRequest(@RequestBody ApprovalRequestDto dto) {
        return approvalService.submitApprovalRequest(
                dto.getFormId(),
                dto.getApplicantEmail(),
                dto.getApplicantName(),
                dto.getReason()
        );
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable("id") String approvalId,
                        @RequestBody ApprovalDecisionDto dto) {
        approvalService.approveRequest(approvalId, dto.getApproverEmail(), dto.getComment());
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable("id") String approvalId,
                       @RequestBody ApprovalDecisionDto dto) {
        approvalService.rejectRequest(approvalId, dto.getApproverEmail(), dto.getComment());
    }

    @GetMapping("/pending")
    public List<AdminApproval> listPending() {
        return approvalService.getPendingApprovals();
    }

    @GetMapping("/form/{formId}")
    public List<AdminApproval> listByForm(@PathVariable String formId) {
        return approvalService.getApprovalsByFormId(formId);
    }

    @Data
    public static class ApprovalRequestDto {
        private String formId;
        private String applicantEmail;
        private String applicantName;
        private String reason;
    }

    @Data
    public static class ApprovalDecisionDto {
        private String approverEmail;
        private String comment;
    }
}

