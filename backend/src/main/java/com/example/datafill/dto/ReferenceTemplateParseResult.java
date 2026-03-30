package com.example.datafill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceTemplateParseResult {
    private String tableName;
    private String tableComment;
    private List<String> filterColumns;
    private List<FieldDef> fields;
    private List<ReferenceFieldMapping> headerMappings;
    private List<ReferenceTableColumn> referenceRows;
    private List<ExcelParseResult.DetectedPair> kvPairs;
    private String parserProfile;
}
