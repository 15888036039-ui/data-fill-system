package com.example.datafill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.datafill.entity.AdminApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdminApprovalMapper extends BaseMapper<AdminApproval> {

    /**
     * 查询待审批的申请
     */
    @Select("SELECT * FROM admin_approval WHERE status = 'PENDING' ORDER BY applied_time")
    List<AdminApproval> selectPendingApprovals();

    /**
     * 查询指定表单的审批记录
     */
    @Select("SELECT * FROM admin_approval WHERE form_id = #{formId} ORDER BY applied_time DESC")
    List<AdminApproval> selectApprovalsByFormId(String formId);
}