package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户填报状态快照表，用于缓存并加速任务列表的查询性能。
 * 避免每次加载列表都进行 N+1 次物理表 MAX 查询。
 */
@Data
@TableName("user_completion_snapshot")
public class UserCompletionSnapshot {

    /**
     * 用户邮箱与表单ID构成联合主键
     */
    private String userEmail;

    private String formId;

    /**
     * 最后一次填报的时间（取日志或物理表中最新的）
     */
    private LocalDateTime lastSubmitTime;

    /**
     * 该用户在该表单中拥有的有效数据条数（可选，用于辅助判定）
     */
    private Integer count;

    /**
     * 快照更新时间
     */
    private LocalDateTime updateTime;
}
