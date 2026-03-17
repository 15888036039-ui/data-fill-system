package com.example.datafill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.datafill.entity.UserFillLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserFillLogMapper extends BaseMapper<UserFillLog> {

    @Select("SELECT * FROM user_fill_log WHERE form_id = #{formId} AND user_email = #{userEmail} ORDER BY submit_time DESC LIMIT 1")
    UserFillLog selectLastByFormAndUser(@Param("formId") String formId, @Param("userEmail") String userEmail);
}

