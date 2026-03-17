package com.example.datafill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.datafill.entity.EmailNotification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface EmailNotificationMapper extends BaseMapper<EmailNotification> {

    /**
     * 查询待发送的通知
     */
    @Select("SELECT * FROM email_notification WHERE status = 'PENDING' AND scheduled_time <= #{now} ORDER BY scheduled_time")
    List<EmailNotification> selectPendingNotifications(LocalDateTime now);

    /**
     * 查询指定表单的已发送通知
     */
    @Select("SELECT * FROM email_notification WHERE form_id = #{formId} AND status = 'SENT' ORDER BY sent_time DESC")
    List<EmailNotification> selectSentNotificationsByFormId(String formId);

    /**
     * 判断某个表单在指定时间是否已经存在某类型通知（用于避免重复创建）
     */
    @Select("SELECT COUNT(1) FROM email_notification WHERE form_id = #{formId} AND notification_type = #{type} AND scheduled_time = #{scheduledTime}")
    int countNotificationByFormAndTypeAndTime(String formId, String type, LocalDateTime scheduledTime);
}