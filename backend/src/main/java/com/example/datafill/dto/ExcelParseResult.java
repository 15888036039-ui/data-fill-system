package com.example.datafill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelParseResult {
    private List<FieldDef> fields;
    private List<DetectedPair> potentialPairs;
    private List<String> originalHeaders; // 用于前端还原字段
    private boolean truncated;
    private int totalColumns;

    // 审计列检测结果
    private List<String> missingColumns;
    private String detectedInsertDt;
    private String detectedUpdateDt;
    private String detectedDeleteFlag;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedPair {
        private String keyBase;
        private String valueBase;
        private List<String> suffixes;
        private List<Integer> keyIndices;
        private List<Integer> valueIndices;
        private String displayName; // 建议显示名，如 "描述/金额"
        private String suggestedColumnName; // 建议列名，如 "description_amount_json"
    }
}
