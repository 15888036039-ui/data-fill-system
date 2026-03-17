package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("admin_approval")
public class AdminApproval {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String formId;              // 关联的表单ID
    private String dataId;              // 逾期提交的数据ID
    private String applicantEmail;      // 申请人邮箱
    private String applicantName;       // 申请人姓名
    private String reason;              // 申请理由
    private String status;              // 审批状态: PENDING(待审批), APPROVED(已批准), REJECTED(已拒绝)
    private String approverEmail;       // 审批人邮箱
    private String approvalComment;     // 审批意见
    private LocalDateTime appliedTime;  // 申请时间
    private LocalDateTime approvedTime; // 审批时间

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}