package com.example.datafill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.example.datafill.entity.SystemConfig;

import com.example.datafill.mapper.SystemConfigMapper;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

@Service

@RequiredArgsConstructor

public class SystemConfigService {

    private final SystemConfigMapper configMapper;

    private final ObjectMapper objectMapper;

    /**

     * 获取中文到英文的映射字典

     */

    public Map<String, String> getDwDict() {

        String val = getConfigValue("dw_dict");

        if (val == null || val.isBlank()) return new HashMap<>();

        try {

            return objectMapper.readValue(val, new TypeReference<Map<String, String>>() {});

        } catch (Exception e) {

            return new HashMap<>();

        }

    }

    /**

     * 获取精确配对的关键词库 (Key -> Value)

     */

    public Map<String, String> getKwPairs() {

        String val = getConfigValue("kw_pairs");

        if (val == null || val.isBlank()) return new HashMap<>();

        try {

            return objectMapper.readValue(val, new TypeReference<Map<String, String>>() {});

        } catch (Exception e) {

            return new HashMap<>();

        }

    }

    /**

     * 获取列名规范配置

     */

    public Map<String, Object> getNamingConvention() {

        String val = getConfigValue("naming_convention");

        if (val == null || val.isBlank()) return new HashMap<>();

        try {

            return objectMapper.readValue(val, new TypeReference<Map<String, Object>>() {});

        } catch (Exception e) {

            return new HashMap<>();

        }

    }

    public void updateConfig(String key, Object value) {

        try {

            String jsonVal = objectMapper.writeValueAsString(value);

            SystemConfig config = configMapper.selectOne(new LambdaQueryWrapper<SystemConfig>().eq(SystemConfig::getConfigKey, key));

            if (config == null) {

                config = new SystemConfig();

                config.setConfigKey(key);

                config.setConfigValue(jsonVal);

                configMapper.insert(config);

            } else {

                config.setConfigValue(jsonVal);

                configMapper.updateById(config);

            }

        } catch (Exception e) {

            throw new RuntimeException("Save config error", e);

        }

    }

    private String getConfigValue(String key) {

        SystemConfig config = configMapper.selectOne(new LambdaQueryWrapper<SystemConfig>().eq(SystemConfig::getConfigKey, key));

        return config != null ? config.getConfigValue()  : null;

    }

    public List<SystemConfig> getAllConfigs() {

        return configMapper.selectList(null);

    }

}

