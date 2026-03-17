package com.example.datafill.controller;

import com.example.datafill.entity.SystemConfig;
import com.example.datafill.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system-config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService configService;

    @GetMapping("/all")
    public List<SystemConfig> getAll() {
        return configService.getAllConfigs();
    }

    @PostMapping("/update")
    public String update(@RequestBody Map<String, Object> params) {
        String key = (String) params.get("key");
        Object value = params.get("value");
        configService.updateConfig(key, value);
        return "success";
    }

    @GetMapping("/dict")
    public Map<String, String> getDict() {
        return configService.getDwDict();
    }

    @GetMapping("/kw-pairs")
    public Map<String, String> getKwPairs() {
        return configService.getKwPairs();
    }

    @GetMapping("/naming-convention")
    public Map<String, Object> getNamingConvention() {
        return configService.getNamingConvention();
    }
}
