package com.example.datafill.controller;

import com.example.datafill.entity.DataFillForm;
import com.example.datafill.entity.DataFillFolder;
import com.example.datafill.mapper.DataFillFormMapper;
import com.example.datafill.service.DataFillFolderService;
import com.example.datafill.service.DynamicTableDdlService;
import com.example.datafill.service.DynamicDataDmlService;
import com.example.datafill.service.ExcelService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fill")
public class DataFillController {

    private final DynamicTableDdlService tableDdlService;
    private final DynamicDataDmlService dataDmlService;
    private final ExcelService excelService;
    private final DataFillFolderService folderService;
    private final DataFillFormMapper formMapper;
    private final com.example.datafill.mapper.OperationLogMapper operationLogMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${data-fill.mail.admin-email:}")
    private String adminEmail;

    public DataFillController(
            DynamicTableDdlService tableDdlService,
            DynamicDataDmlService dataDmlService,
            ExcelService excelService,
            DataFillFolderService folderService,
            DataFillFormMapper formMapper,
            com.example.datafill.mapper.OperationLogMapper operationLogMapper,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.tableDdlService = tableDdlService;
        this.dataDmlService = dataDmlService;
        this.excelService = excelService;
        this.folderService = folderService;
        this.formMapper = formMapper;
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    private boolean isUserAdmin(String email) {
        if (email == null || email.trim().isEmpty()) return false;
        // 允许 FineReport 管理账号作为超级管理员
        if ("finereport_manage".equalsIgnoreCase(email.trim())) return true;
        if (adminEmail == null || adminEmail.trim().isEmpty()) return false;
        String[] admins = adminEmail.split(",");
        for (String admin : admins) {
            if (admin.trim().equalsIgnoreCase(email.trim())) return true;
        }
        return false;
    }

    private void assertAdmin(String userEmail) {
        if (!isUserAdmin(userEmail)) {
            throw new com.example.datafill.exception.AppException(403, "仅管理员可执行该操作");
        }
    }

    private boolean canUserAccessForm(DataFillForm form, String userEmail, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            return false;
        }
        String fillEmails = form.getFillUserEmails();
        if (fillEmails == null || fillEmails.trim().isEmpty()) {
            return false;
        }
        try {
            List<String> allowed = objectMapper
                    .readValue(fillEmails, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (allowed == null || allowed.isEmpty()) {
                return false;
            }
            return allowed.stream().anyMatch(e -> e != null && e.equalsIgnoreCase(userEmail));
        } catch (Exception e) {
            return false;
        }
    }

    private void recordLog(String formId, String userEmail, String type, String desc) {
        if (userEmail == null || userEmail.trim().isEmpty()) userEmail = "未知用户";
        com.example.datafill.entity.OperationLog log = new com.example.datafill.entity.OperationLog();
        log.setFormId(formId);
        log.setUserEmail(userEmail);
        log.setOperationType(type);
        log.setOperationDesc(desc);
        log.setCreateTime(java.time.LocalDateTime.now());
        operationLogMapper.insert(log);
    }

    // 获取表单模板列表（支持按用户权限过滤）
    @GetMapping("/forms")
    public List<DataFillForm> getForms(
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String groupTag) {
        boolean isAdmin = isUserAdmin(userEmail);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFillForm> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFillForm>().orderByDesc("create_time");
        if (groupTag != null && !groupTag.trim().isEmpty()) {
            qw.eq("group_tag", groupTag.trim());
        }
        List<DataFillForm> allForms = formMapper.selectList(qw);
        List<DataFillForm> visibleForms;
        if (isAdmin) {
            visibleForms = allForms;
        } else {
            visibleForms = allForms.stream()
                    .filter(form -> canUserAccessForm(form, userEmail, false))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (folderId == null || folderId.trim().isEmpty()) {
            return visibleForms;
        }

        if (DataFillFolderService.UNCATEGORIZED_FOLDER_ID.equals(folderId)) {
            return visibleForms.stream()
                    .filter(form -> form.getFolderId() == null || form.getFolderId().trim().isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }

        java.util.Set<String> selectedFolderIds = folderService.collectFolderAndDescendants(folderId);
        if (selectedFolderIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return visibleForms.stream()
                .filter(form -> form.getFolderId() != null && selectedFolderIds.contains(form.getFolderId()))
                .collect(java.util.stream.Collectors.toList());
    }

    @GetMapping("/folders/tree")
    public List<com.example.datafill.dto.DataFillFolderNode> getFolderTree(
            @RequestParam(required = false) String userEmail,
            @RequestParam(required = false) String groupTag) {
        boolean isAdmin = isUserAdmin(userEmail);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFillForm> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataFillForm>().orderByDesc("create_time");
        if (groupTag != null && !groupTag.trim().isEmpty()) {
            qw.eq("group_tag", groupTag.trim());
        }
        List<DataFillForm> allForms = formMapper.selectList(qw);
        List<DataFillForm> visibleForms = allForms.stream()
                .filter(form -> canUserAccessForm(form, userEmail, isAdmin))
                .collect(java.util.stream.Collectors.toList());
        return folderService.buildFolderTree(visibleForms, isAdmin);
    }

    @PostMapping("/folders")
    public DataFillFolder createFolder(
            @RequestParam String userEmail,
            @RequestBody DataFillFolder folder) {
        assertAdmin(userEmail);
        folder.setCreator(userEmail);
        return folderService.createFolder(folder);
    }

    @PutMapping("/folders/{id}")
    public DataFillFolder updateFolder(
            @PathVariable String id,
            @RequestParam String userEmail,
            @RequestBody DataFillFolder folder) {
        assertAdmin(userEmail);
        return folderService.updateFolder(id, folder);
    }

    @DeleteMapping("/folders/{id}")
    public String deleteFolder(
            @PathVariable String id,
            @RequestParam String userEmail) {
        assertAdmin(userEmail);
        folderService.deleteFolder(id);
        return "success";
    }

    @PutMapping("/forms/{id}/folder")
    public String moveFormFolder(
            @PathVariable String id,
            @RequestParam String userEmail,
            @RequestBody com.example.datafill.dto.FormFolderMoveRequest request) {
        assertAdmin(userEmail);
        folderService.moveFormToFolder(id, request.getFolderId());
        return "success";
    }

    @PutMapping("/forms/folder/batch")
    public String moveFormsFolderBatch(
            @RequestParam String userEmail,
            @RequestBody com.example.datafill.dto.FormBatchFolderMoveRequest request) {
        assertAdmin(userEmail);
        folderService.moveFormsToFolder(request.getFormIds(), request.getFolderId());
        return "success";
    }

    @PutMapping("/folders/reorder")
    public String reorderFolders(
            @RequestParam String userEmail,
            @RequestBody List<com.example.datafill.dto.FolderReorderItem> items) {
        assertAdmin(userEmail);
        folderService.reorderFolders(items);
        return "success";
    }

    // 根据ID获取某个表单的配置
    @GetMapping("/forms/{id}")
    public DataFillForm getFormById(@PathVariable String id) {
        return formMapper.selectById(id);
    }

    // [管理端核心]: 删除表单模板（根据参数决定是重命名备份还是彻底删除物理表）
    @DeleteMapping("/forms/{id}")
    public String deleteFormAndTable(
            @PathVariable String id, 
            @RequestParam String userEmail,
            @RequestParam(required = false, defaultValue = "false") boolean dropTable) {
        assertAdmin(userEmail);
        tableDdlService.deleteFormAndTable(id, dropTable);
        String logDesc = dropTable ? "彻底删除了表单及其物理表(DROP)" : "删除了表单及其物理表(RENAME备份)";
        recordLog(id, userEmail, "DELETE_FORM", logDesc);
        return "success";
    }

    // 获取物理数据库中的所有应用层模式(Schema)，排除系统级 Schema
    @GetMapping("/schemas")
    public List<String> getAvailableSchemas() {
        return tableDdlService.getAvailableSchemas();
    }

    @GetMapping("/checkTable")
    public Map<String, Object> checkTable(
            @RequestParam(required = false) String schemaName,
            @RequestParam String tableName) {
        return tableDdlService.checkTableStatus(schemaName, tableName);
    }

    // [管理端核心]: 提交表单配置，并在数据库真实建表 (CREATE TABLE)
    @PostMapping("/forms/createTable")
    public String createTable(@RequestBody DataFillForm form, @RequestParam String userEmail) {
        assertAdmin(userEmail);
        String formId = tableDdlService.createFormAndTable(form);
        recordLog(formId, userEmail, "CREATE_FORM", "创建了表单及物理表");
        return formId;
    }

    @PostMapping("/forms/bindExistingTable")
    public String bindExistingTable(@RequestBody DataFillForm form, @RequestParam String userEmail) {
        assertAdmin(userEmail);
        String formId = tableDdlService.bindExistingTable(form);
        recordLog(formId, userEmail, "BIND_FORM", "绑定了已物理存在的表");
        return formId;
    }

    /**
     * [管理端]: 更新表单元数据（不修改物理表结构）
     */
    @PutMapping("/forms/{id}")
    public String updateForm(@PathVariable String id, @RequestBody DataFillForm form, @RequestParam String userEmail) {
        assertAdmin(userEmail);
        tableDdlService.updateFormMeta(id, form);
        recordLog(id, userEmail, "UPDATE_FORM", "更新了表单元数据或表结构");
        return "success";
    }

    // [用户端核心]: 向动态生成的物理表中插入一行数据
    @PostMapping("/data/{formId}")
    public String insertData(@PathVariable String formId, @RequestBody Map<String, Object> rowData, @RequestParam(required = false) String userEmail) {
        dataDmlService.insertRowData(formId, rowData);
        recordLog(formId, userEmail, "ADD", "新增了1条数据");
        return "success";
    }

    // [用户端核心]: 获取某张动态物理表里的全部填报数据
    @GetMapping("/data/{formId}")
    public Map<String, Object> listData(
            @PathVariable String formId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String userEmail) {
        boolean isAdmin = isUserAdmin(userEmail);
        return dataDmlService.getTableDataPage(formId, page, size, null, userEmail, isAdmin);
    }



    /**
     * 获取某个用户的任务列表（待填报 / 已过期）
     * 供帆软“我的填报”嵌入页面使用
     */
    @GetMapping("/user/tasks")
    public Map<String, Object> getUserTasks(
            @RequestParam String userEmail,
            @RequestParam(required = false) String groupTag) {
        boolean isAdmin = isUserAdmin(userEmail);
        return tableDdlService.getUserTasks(userEmail, groupTag, isAdmin);
    }

    // [用户端核心]: 条件筛选查询
    @PostMapping("/data/{formId}/list")
    public Map<String, Object> listDataWithFilter(
            @PathVariable String formId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String userEmail,
            @RequestBody(required = false) Map<String, String> filters) {
        boolean isAdmin = isUserAdmin(userEmail);
        return dataDmlService.getTableDataPage(formId, page, size, filters, userEmail, isAdmin);
    }

    // [用户端核心]: 批量软删除动态物理表里的数据
    @PostMapping("/data/{formId}/batchDelete")
    public String batchDeleteData(
            @PathVariable String formId, 
            @RequestBody List<String> dataIds,
            @RequestParam(required = false) String userEmail) {
        boolean isAdmin = isUserAdmin(userEmail);
        dataDmlService.batchDeleteRowData(formId, dataIds, userEmail, isAdmin);
        recordLog(formId, userEmail, "DELETE", "批量删除了 " + dataIds.size() + " 条数据");
        return "success";
    }

    // [用户端核心]: 根据筛选条件删除所有匹配数据
    @PostMapping("/data/{formId}/deleteAllFiltered")
    public String deleteAllFiltered(
            @PathVariable String formId,
            @RequestParam(required = false) String userEmail,
            @RequestBody(required = false) Map<String, String> filters) {
        boolean isAdmin = isUserAdmin(userEmail);
        dataDmlService.deleteAllFilteredData(formId, filters, userEmail, isAdmin);
        recordLog(formId, userEmail, "DELETE", "按条件清空了匹配的数据");
        return "success";
    }

    // [用户端核心]: 软删除某张动态物理表里的一条数据
    @DeleteMapping("/data/{formId}/{dataId}")
    public String deleteData(
            @PathVariable String formId, 
            @PathVariable String dataId,
            @RequestParam(required = false) String userEmail) {
        boolean isAdmin = isUserAdmin(userEmail);
        dataDmlService.deleteRowData(formId, dataId, userEmail, isAdmin);
        recordLog(formId, userEmail, "DELETE", "删除了1条数据");
        return "success";
    }

    // [用户端核心]: 修改数据
    @PutMapping("/data/{formId}/{dataId}")
    public String updateData(
            @PathVariable String formId, 
            @PathVariable String dataId, 
            @RequestBody Map<String, Object> rowData,
            @RequestParam(required = false) String userEmail) {
        boolean isAdmin = isUserAdmin(userEmail);
        dataDmlService.updateRowData(formId, dataId, rowData, userEmail, isAdmin);
        recordLog(formId, userEmail, "UPDATE", "更新了1条数据");
        return "success";
    }

    /**
     * 下载当前表单对应的 Excel 模板
     */
    @GetMapping("/template/{formId}")
    public void downloadTemplate(@PathVariable String formId, HttpServletResponse response) throws IOException {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String fileName = (form.getName() != null ? form.getName() : form.getTableName()) + "_模板.xlsx";
        String encodedFileName = URLEncoder.encode(fileName, "UTF-8");

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);

        excelService.exportTemplate(formId, response.getOutputStream());
    }

    /**
     * 上传 Excel，将数据批量写入动态物理表
     */
    @PostMapping("/import/{formId}")
    public Map<String, Object> importData(
            @PathVariable String formId, 
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "append") String mode,
            @RequestParam(value = "creator", required = false) String creator,
            @RequestParam(value = "load_user", required = false) String loadUser) throws IOException {
        String finalCreator = (loadUser != null && !loadUser.trim().isEmpty()) ? loadUser : creator;
        recordLog(formId, finalCreator, "UPLOAD", "通过 Excel 导入了 " + (finalCreator == null ? "未知用户" : finalCreator) + " 的数据");
        return excelService.importData(formId, file, mode, finalCreator);
    }

    /**
     * 快速导入 Excel 解析表头，生成字段配置
     */
    @PostMapping("/forms/parseExcel")
    public com.example.datafill.dto.ExcelParseResult parseExcelHeaders(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "density") String mode,
            @RequestParam(value = "smartType", defaultValue = "true") boolean smartType,
            @RequestParam(value = "kvPairEnabled", defaultValue = "true") boolean kvPairEnabled) throws IOException {
        return excelService.parseExcelHeaders(file, mode, smartType, kvPairEnabled);
    }

    @PostMapping("/forms/parseReferenceTemplate")
    public com.example.datafill.dto.ReferenceTemplateParseResult parseReferenceTemplate(
            @RequestParam("file") MultipartFile file) throws IOException {
        return excelService.parseReferenceTemplate(file);
    }

    @GetMapping("/inspectTable")
    public com.example.datafill.dto.ExcelParseResult inspectExistingTable(
            @RequestParam(required = false, defaultValue = "public") String schemaName,
            @RequestParam String tableName) {
        return excelService.inspectExistingTable(schemaName, tableName);
    }

    /**
     * 获取某张表单的操作日志
     */
    @GetMapping("/data/{formId}/logs")
    public List<com.example.datafill.entity.OperationLog> getLogs(@PathVariable String formId) {
        return operationLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.example.datafill.entity.OperationLog>()
                .eq("form_id", formId)
                .orderByDesc("create_time"));
    }

    @PostMapping("/forms/{id}/repairTable")
    public Map<String, Object> repairTable(
            @PathVariable String id,
            @RequestParam String userEmail,
            @RequestBody List<String> columns) {
        assertAdmin(userEmail);
        return tableDdlService.repairTable(id, columns);
    }

    @PostMapping("/forms/repairTableByName")
    public Map<String, Object> repairTableByName(
            @RequestParam(required = false, defaultValue = "public") String schemaName,
            @RequestParam String tableName,
            @RequestParam String userEmail,
            @RequestBody List<String> columns) {
        assertAdmin(userEmail);
        return tableDdlService.repairTableByName(schemaName, tableName, columns);
    }
}
