package com.example.datafill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceTableColumn {
    private String columnName;
    private String fieldType;
    private String precision;
    private String notNull;
    private String valueRange;
    private String comment;
}
