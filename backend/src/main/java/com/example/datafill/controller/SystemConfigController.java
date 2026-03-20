package com.example.datafill.controller;

import com.example.datafill.entity.SystemConfig;
import com.example.datafill.service.SystemConfigService;
import com.example.datafill.service.ExcelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system-config")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SystemConfigController {

    private final SystemConfigService configService;
    private final ExcelService excelService;

    @PostMapping("/test-naming")
    public java.util.Map<String, String> testNaming(@RequestBody java.util.Map<String, String> params) {
        String input = params.get("input");
        log.info("Testing naming convention for input: {}", input);
        if (input == null || input.isBlank()) return java.util.Map.of("result", "");
        try {
            String result = excelService.testNaming(input);
            log.info("Naming test result: {}", result);
            return java.util.Map.of("result", result);
        } catch (Exception e) {
            log.error("Naming test failed", e);
            throw e;
        }
    }

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
