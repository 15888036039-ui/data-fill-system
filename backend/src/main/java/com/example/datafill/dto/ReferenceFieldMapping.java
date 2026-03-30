package com.example.datafill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceFieldMapping {
    private Integer columnIndex;
    private String excelHeader;
    private String columnName;
    private boolean jsonMapped;
}
