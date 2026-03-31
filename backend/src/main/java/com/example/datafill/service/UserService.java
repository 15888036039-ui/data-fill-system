package com.example.datafill.service;

import com.example.datafill.dto.UserOption;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("dynamicJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Value("${data-fill.user.default-email-domain:furniwell.com}")
    private String defaultEmailDomain;

    /** 帆软内置超级管理员账号，不在组织架构表中，始终视为已注册 */
    private static final String BUILTIN_ADMIN_USERNAME = "finereport_manage";

    /**
     * Check if the user email exists in the report_department_user_list view.
     * 
     * @param email The user email to check
     * @return true if the email exists
     */
    public boolean isUserRegistered(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        if (BUILTIN_ADMIN_USERNAME.equalsIgnoreCase(email.trim())) {
            return true;
        }

        String sql = "SELECT COUNT(1) FROM etl_manage.report_department_user_list WHERE email = ?";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, email);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取所有已注册用户的邮箱列表（用于权限分配下拉框）
     * 开发/测试环境下，如果组织架构视图没有数据，至少返回内置管理员账号，避免前端下拉框完全为空。
     */
    public java.util.List<String> getAllUserEmails() {
        String sql = "SELECT DISTINCT email FROM etl_manage.report_department_user_list WHERE email IS NOT NULL ORDER BY email";
        java.util.List<String> emails;
        try {
            emails = jdbcTemplate.queryForList(sql, String.class);
        } catch (Exception e) {
            emails = new java.util.ArrayList<>();
        }
        if (emails == null || emails.isEmpty()) {
            emails = new java.util.ArrayList<>();
            emails.add(BUILTIN_ADMIN_USERNAME);
        }
        return emails;
    }

    public List<UserOption> getAllUserOptions() {
        String sql = "SELECT username, email, fullname AS display_name FROM etl_manage.report_department_user_list ORDER BY username";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            List<UserOption> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String username = toText(row.get("username"));
                String email = toText(row.get("email"));
                
                // Use email as unique key if username is missing, otherwise use username
                String key = (username != null && !username.isBlank()) ? username : email;
                if (key == null || key.isBlank() || !seen.add(key)) {
                    continue;
                }

                String displayName = toText(row.get("display_name"));
                email = normalizeEmail(username, email);

                UserOption option = new UserOption();
                option.setUsername(username);
                option.setEmail(email);
                option.setLabel(buildUserLabel(displayName, username, email));
                result.add(option);
            }
            // 如果视图里没有任何数据，至少注入一个内置管理员账户
            if (result.isEmpty()) {
                UserOption admin = new UserOption();
                admin.setUsername(BUILTIN_ADMIN_USERNAME);
                admin.setEmail(BUILTIN_ADMIN_USERNAME);
                admin.setLabel(buildUserLabel("系统管理员", BUILTIN_ADMIN_USERNAME, BUILTIN_ADMIN_USERNAME));
                result.add(admin);
            }
            return result;
        } catch (Exception e) {
            // Fallback: simplified list from emails
            List<UserOption> fallback = new ArrayList<>();
            for (String email : getAllUserEmails()) {
                UserOption option = new UserOption();
                String prefix = email.contains("@") ? email.split("@")[0] : email;
                option.setUsername(prefix);
                option.setEmail(email);
                option.setLabel(buildUserLabel(null, prefix, email));
                fallback.add(option);
            }
            return fallback;
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        // This method is kept for compatibility but no longer used for the primary view
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return resultSet.next();
        } catch (Exception e) {
            return false;
        }
    }

    private String buildUserLabel(String displayName, String username, String email) {
        String base = (displayName != null && !displayName.isBlank()) ? displayName : username;
        if (email != null && !email.isBlank() && !email.equalsIgnoreCase(base)) {
            return base + " (" + email + ")";
        }
        return base;
    }

    private String normalizeEmail(String username, String email) {
        if (email != null && !email.isBlank()) {
            return email;
        }
        if (username == null || username.isBlank()) {
            return null;
        }
        if (username.contains("@")) {
            return username;
        }
        String domain = defaultEmailDomain == null ? "furniwell.com" : defaultEmailDomain.trim();
        if (domain.isEmpty()) {
            domain = "furniwell.com";
        }
        if (domain.startsWith("@")) {
            domain = domain.substring(1);
        }
        return username + "@" + domain;
    }

    private String toText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
