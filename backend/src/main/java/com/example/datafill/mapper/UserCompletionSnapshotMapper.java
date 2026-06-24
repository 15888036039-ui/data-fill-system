package com.example.datafill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.datafill.entity.UserCompletionSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCompletionSnapshotMapper extends BaseMapper<UserCompletionSnapshot> {

    /**
     * 使用覆盖写方式更新快照（PostgreSQL ON CONFLICT）
     */
    @Update("INSERT INTO user_completion_snapshot (user_email, form_id, last_submit_time, count, update_time) " +
            "VALUES (#{userEmail}, #{formId}, #{lastSubmitTime}, #{count}, #{updateTime}) " +
            "ON CONFLICT (user_email, form_id) DO UPDATE SET " +
            "last_submit_time = EXCLUDED.last_submit_time, " +
            "count = EXCLUDED.count, " +
            "update_time = EXCLUDED.update_time")
    void upsert(UserCompletionSnapshot snapshot);
}
