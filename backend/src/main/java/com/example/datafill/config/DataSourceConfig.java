package com.example.datafill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;

/**
 * 双数据源：primary = 填报系统元数据（MyBatis）；dynamic = 用户导入/填报的物理表及数仓视图（如 etl_manage.*）。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.primary")
    public org.springframework.boot.autoconfigure.jdbc.DataSourceProperties primaryDataSourceProperties() {
        return new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.dynamic")
    public org.springframework.boot.autoconfigure.jdbc.DataSourceProperties dynamicDataSourceProperties() {
        return new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
    }

    @Bean(name = "dynamicDataSource")
    public DataSource dynamicDataSource() {
        return dynamicDataSourceProperties().initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(@Qualifier("primaryDataSource") DataSource primaryDataSource) {
        return new DataSourceTransactionManager(primaryDataSource);
    }

    @Bean(name = "dynamicTransactionManager")
    public PlatformTransactionManager dynamicTransactionManager(@Qualifier("dynamicDataSource") DataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }

    @Bean
    @Primary
    public JdbcTemplate primaryJdbcTemplate(@Qualifier("primaryDataSource") DataSource primaryDataSource) {
        return new JdbcTemplate(primaryDataSource);
    }

    @Bean(name = "dynamicJdbcTemplate")
    public JdbcTemplate dynamicJdbcTemplate(@Qualifier("dynamicDataSource") DataSource dynamicDataSource) {
        return new JdbcTemplate(dynamicDataSource);
    }
}
