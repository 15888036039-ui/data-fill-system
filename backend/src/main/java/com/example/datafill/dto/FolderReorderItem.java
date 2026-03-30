package com.example.datafill.dto;

import lombok.Data;

@Data
public class FolderReorderItem {
    private String id;
    private String parentId;
    private Integer sortOrder;
}
