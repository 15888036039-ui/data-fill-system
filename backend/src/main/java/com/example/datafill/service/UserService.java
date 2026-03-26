package com.example.datafill.service;

import com.example.datafill.dto.UserOption;
import lombok.RequiredArgsConstructor;
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

    private final JdbcTemplate jdbcTemplate;

    @Value("${data-fill.user.default-email-domain:furniwell.com}")
    private String defaultEmailDomain;

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

    public List<UserOption> getAllUserOptions() {
        String emailColumn = resolveFirstExistingColumn(List.of(
                "email",
                "email_address",
                "corp_email",
                "mail",
                "user_email"
        ));
        String nameColumn = resolveFirstExistingColumn(List.of(
                "emp_name",
                "user_name",
                "username",
                "name"
        ));

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT \"email_username\" AS username");
        if (emailColumn != null) {
            sql.append(", \"").append(emailColumn).append("\" AS email");
        }
        if (nameColumn != null) {
            sql.append(", \"").append(nameColumn).append("\" AS display_name");
        }
        sql.append(" FROM \"dim_organization_mon\"")
                .append(" WHERE (\"delete_flag\" IS NULL OR \"delete_flag\" = false)")
                .append(" AND \"email_username\" IS NOT NULL")
                .append(" AND \"dim_month_id\" = (SELECT MAX(\"dim_month_id\") FROM \"dim_organization_mon\")")
                .append(" ORDER BY \"email_username\"");

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString());
            List<UserOption> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String username = toText(row.get("username"));
                if (username == null || username.isBlank() || !seen.add(username)) {
                    continue;
                }

                String email = toText(row.get("email"));
                String displayName = toText(row.get("display_name"));
                email = normalizeEmail(username, email);

                UserOption option = new UserOption();
                option.setUsername(username);
                option.setEmail(email);
                option.setLabel(buildUserLabel(displayName, username, email));
                result.add(option);
            }
            return result;
        } catch (Exception e) {
            List<UserOption> fallback = new ArrayList<>();
            for (String username : getAllUserEmails()) {
                UserOption option = new UserOption();
                option.setUsername(username);
                option.setEmail(normalizeEmail(username, null));
                option.setLabel(buildUserLabel(null, username, option.getEmail()));
                fallback.add(option);
            }
            return fallback;
        }
    }

    private String resolveFirstExistingColumn(List<String> candidates) {
        for (String candidate : candidates) {
            if (columnExists("dim_organization_mon", candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean columnExists(String tableName, String columnName) {
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
