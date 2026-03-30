package com.example.datafill.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataFillFolderNode {
    private String id;
    private String name;
    private String parentId;
    private Integer sortOrder;
    private Integer templateCount;
    private Boolean systemNode;
    private List<DataFillFolderNode> children = new ArrayList<>();
}
