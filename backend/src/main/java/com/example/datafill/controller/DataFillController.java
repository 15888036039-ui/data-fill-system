package com.example.datafill.controller;

import com.example.datafill.entity.DataFillForm;
import com.example.datafill.mapper.DataFillFormMapper;
import com.example.datafill.service.DynamicTableDdlService;
import com.example.datafill.service.DynamicDataDmlService;
import com.example.datafill.service.ExcelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fill")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // 允许前端本地测试跨域
public class DataFillController {

    private final DynamicTableDdlService tableDdlService;
    private final DynamicDataDmlService dataDmlService;
    private final ExcelService excelService;
    private final DataFillFormMapper formMapper;

    // 获取所有创建过的表单模板列表
    @GetMapping("/forms")
    public List<DataFillForm> getForms() {
        return formMapper.selectList(null);
    }

    // 根据ID获取某个表单的配置
    @GetMapping("/forms/{id}")
    public DataFillForm getFormById(@PathVariable String id) {
        return formMapper.selectById(id);
    }

    // [管理端核心]: 软删除表单及其物理表
    @DeleteMapping("/forms/{id}")
    public String deleteFormAndTable(@PathVariable String id) {
        tableDdlService.deleteFormAndTable(id);
        return "success";
    }

    // [管理端核心]: 提交表单配置，并在数据库真实建表 (CREATE TABLE)
    @PostMapping("/forms/createTable")
    public String createTable(@RequestBody DataFillForm form) {
        return tableDdlService.createFormAndTable(form);
    }

    /**
     * [管理端]: 更新表单元数据（不修改物理表结构）
     */
    @PutMapping("/forms/{id}")
    public String updateForm(@PathVariable String id, @RequestBody DataFillForm form) {
        tableDdlService.updateFormMeta(id, form);
        return "success";
    }

    // [用户端核心]: 向动态生成的物理表中插入一行数据
    @PostMapping("/data/{formId}")
    public String insertData(@PathVariable String formId, @RequestBody Map<String, Object> rowData) {
        dataDmlService.insertRowData(formId, rowData);
        return "success";
    }

    // [用户端核心]: 获取某张动态物理表里的全部填报数据
    @GetMapping("/data/{formId}")
    public Map<String, Object> listData(
            @PathVariable String formId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return dataDmlService.getTableDataPage(formId, page, size, null);
    }

    /**
     * 获取某张动态物理表用于筛选的下拉选项（根据当前已有数据去重）
     */
    @GetMapping("/data/{formId}/filters")
    public Map<String, java.util.List<String>> getFilterOptions(
            @PathVariable String formId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        return dataDmlService.getFilterOptions(formId, userEmail, isAdmin);
    }

    /**
     * 获取某个用户的任务列表（待填报 / 已过期）
     * 供帆软“我的填报”嵌入页面使用
     */
    @GetMapping("/user/tasks")
    public Map<String, Object> getUserTasks(@RequestParam String userEmail) {
        return tableDdlService.getUserTasks(userEmail);
    }

    // [用户端核心]: 条件筛选查询
    @PostMapping("/data/{formId}/list")
    public Map<String, Object> listDataWithFilter(
            @PathVariable String formId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestBody(required = false) Map<String, String> filters) {
        return dataDmlService.getTableDataPage(formId, page, size, filters);
    }

    // [用户端核心]: 批量软删除动态物理表里的数据
    @PostMapping("/data/{formId}/batchDelete")
    public String batchDeleteData(
            @PathVariable String formId, 
            @RequestBody List<String> dataIds,
            @RequestParam(required = false) String userEmail,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        dataDmlService.batchDeleteRowData(formId, dataIds, userEmail, isAdmin);
        return "success";
    }

    // [用户端核心]: 软删除某张动态物理表里的一条数据
    @DeleteMapping("/data/{formId}/{dataId}")
    public String deleteData(
            @PathVariable String formId, 
            @PathVariable String dataId,
            @RequestParam(required = false) String userEmail,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        dataDmlService.deleteRowData(formId, dataId, userEmail, isAdmin);
        return "success";
    }

    // [用户端核心]: 修改数据
    @PutMapping("/data/{formId}/{dataId}")
    public String updateData(
            @PathVariable String formId, 
            @PathVariable String dataId, 
            @RequestBody Map<String, Object> rowData,
            @RequestParam(required = false) String userEmail,
            @RequestParam(defaultValue = "false") boolean isAdmin) {
        dataDmlService.updateRowData(formId, dataId, rowData, userEmail, isAdmin);
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
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

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
            @RequestParam(value = "creator", required = false) String creator) throws IOException {
        int count = excelService.importData(formId, file, mode, creator);
        return Map.of("success", true, "count", count);
    }

    /**
     * 快速导入 Excel 解析表头，生成字段配置
     */
    @PostMapping("/forms/parseExcel")
    public List<com.example.datafill.dto.FieldDef> parseExcelHeaders(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "density") String mode,
            @RequestParam(value = "smartType", defaultValue = "true") boolean smartType) throws IOException {
        return excelService.parseExcelHeaders(file, mode, smartType, true);
    }
}
