package com.example.datafill.dto;

import lombok.Data;

import java.util.List;

@Data
public class FormBatchFolderMoveRequest {
    private List<String> formIds;
    private String folderId;
}
