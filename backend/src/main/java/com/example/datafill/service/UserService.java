package com.example.datafill.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Check if the user email exists in the dim_organization_mon table.
     * 
     * @param email The user email to check
     * @return true if the email exists and is not deleted
     */
    public boolean isUserRegistered(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        String sql = "SELECT COUNT(1) FROM \"dim_organization_mon\" WHERE \"email_username\" = ? AND (\"delete_flag\" IS NULL OR \"delete_flag\" = false) AND \"dim_month_id\" = (SELECT MAX(\"dim_month_id\") FROM \"dim_organization_mon\")";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, email);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取所有已注册用户的邮箱列表（用于权限分配下拉框），取最新月份
     */
    public java.util.List<String> getAllUserEmails() {
        String sql = "SELECT DISTINCT \"email_username\" FROM \"dim_organization_mon\" WHERE (\"delete_flag\" IS NULL OR \"delete_flag\" = false) AND \"email_username\" IS NOT NULL AND \"dim_month_id\" = (SELECT MAX(\"dim_month_id\") FROM \"dim_organization_mon\") ORDER BY \"email_username\"";
        try {
            return jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }
}
