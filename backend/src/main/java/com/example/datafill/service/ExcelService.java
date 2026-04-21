package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.mapper.DataFillFormMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;

import org.apache.poi.ss.usermodel.Row;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.github.pjfanning.xlsx.StreamingReader;
import org.apache.poi.util.IOUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import java.util.List;

import java.util.Map;

import java.util.HashSet;

import java.util.Set;

import net.sourceforge.pinyin4j.PinyinHelper;

import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;

import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;

import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;

@Service
@RequiredArgsConstructor

public class ExcelService {
    private static final Logger log = LoggerFactory.getLogger(ExcelService.class);
    private static final String EXCEL_ROW_META_KEY = "__excel_row_num__";
    private static final java.util.regex.Pattern NUMBER_PATTERN = java.util.regex.Pattern
            .compile("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$");

    private static final java.util.Set<String> EXISTING_TABLE_SYSTEM_COLUMNS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "id", "load_user", "extra_data",
                    "w_insert_dt", "w_update_dt", "delete_flag",
                    "ctime", "mtime", "create_time", "update_time", "created_at", "updated_at",
                    "is_delete", "deleted", "del_flag", "insert_time"));

    private static final String[] INSERT_AUDIT_LEXICON = { "w_insert_dt", "ctime", "create_time", "created_at",
            "insert_time" };
    private static final String[] UPDATE_AUDIT_LEXICON = { "w_update_dt", "mtime", "update_time", "updated_at" };
    private static final String[] DELETE_FLAG_LEXICON = { "delete_flag", "is_delete", "deleted", "del_flag" };

    private String detectRole(java.util.Set<String> columns, String[] lexicon) {
        for (String candidate : lexicon) {
            if (columns.contains(candidate.toLowerCase())) {
                return candidate.toLowerCase();
            }
        }
        return null;
    }

    private final DataFillFormMapper formMapper;

    private final DynamicDataDmlService dataDmlService;

    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("dynamicJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    private final SystemConfigService configService;

    @org.springframework.beans.factory.annotation.Value("${data-fill.import.max-file-size-mb:20}")
    private int maxFileSizeMb;


    static {
        // 针对 50MB 压缩文件，解压后的单个 Record 块可能超过默认的 100MB 限制
        // 这里设置为 500MB，允许处理更大或更复杂的 Excel 结构
        IOUtils.setByteArrayMaxOverride(500 * 1024 * 1024);
    }

    /**
     * 5.1 对外提供列名生成逻辑的测试接口
     */
    public String testNaming(String originalName) {
        return generateDwColumnName(originalName, 0);
    }

    private String readCell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        if (value == null) {
            return "";
        }
        value = value.replace("\uFEFF", "").trim();
        return value;
    }

    private boolean isRowBlank(List<String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int findRowIndexByFirstCell(List<List<String>> rows, String label) {
        for (int i = 0; i < rows.size(); i++) {
            if (label.equalsIgnoreCase(readCell(rows.get(i), 0))) {
                return i;
            }
        }
        return -1;
    }

    private List<String> splitMultiValueCells(List<String> row, int startIndex) {
        List<String> result = new ArrayList<>();
        if (row == null) {
            return result;
        }
        for (int i = startIndex; i < row.size(); i++) {
            String raw = readCell(row, i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String[] parts = raw.split("[,，；;\\n]+");
            for (String part : parts) {
                String normalized = part == null ? "" : part.trim();
                if (normalized != null && !normalized.trim().isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    private String normalizeReferencePrecision(String precision) {
        if (precision == null) {
            return "";
        }
        return precision.trim().replace("\"", "");
    }

    private String buildReferenceDbType(String fieldType, String precision) {
        String normalizedType = lower(fieldType);
        String normalizedPrecision = normalizeReferencePrecision(precision);
        if (normalizedType == null || normalizedType.trim().isEmpty()) {
            return "VARCHAR(255)";
        }
        if ("json".equals(normalizedType)) {
            return "JSON";
        }
        if (normalizedType.contains("json")) {
            return "JSONB";
        }
        if (normalizedType.contains("datetime") || normalizedType.contains("timestamp")) {
            return "TIMESTAMP";
        }
        if ("date".equals(normalizedType)) {
            return "DATE";
        }
        if ("time".equals(normalizedType)) {
            return "TIME";
        }
        if (normalizedType.contains("decimal") || normalizedType.contains("numeric")) {
            if (normalizedPrecision != null && !normalizedPrecision.trim().isEmpty()) {
                String body = normalizedPrecision.replace("(", "").replace(")", "");
                return "NUMERIC(" + body + ")";
            }
            // 参考模板仅写 numeric / decimal 且无精度时，与源表一致，使用无约束 NUMERIC
            return "NUMERIC";
        }
        if (normalizedType.contains("bigint")) {
            return "BIGINT";
        }
        // 参考模板里经常出现：字段类型=int，精度=10（例如 int10）。
        // 页面上保留精度信息，方便用户看到“int10”语义；真正建表时再由 DDL 层归一化成 PG 可执行类型。
        if (normalizedType.equals("int")) {
            if (normalizedPrecision != null && !normalizedPrecision.trim().isEmpty()) {
                String digits = normalizedPrecision.replace("(", "").replace(")", "");
                if (digits.matches("\\d+")) {
                    return "INT(" + digits + ")";
                }
            }
            return "INT";
        }
        if (normalizedType.contains("integer")) {
            return "INTEGER";
        }
        if (normalizedType.contains("bool")) {
            return "BOOLEAN";
        }
        if (normalizedType.contains("char") || normalizedType.contains("text") || normalizedType.contains("string")) {
            if (normalizedPrecision != null && !normalizedPrecision.trim().isEmpty()
                    && normalizedPrecision.matches("\\d+")) {
                return "VARCHAR(" + normalizedPrecision + ")";
            }
            return normalizedType.contains("text") ? "TEXT" : "VARCHAR(255)";
        }
        if (normalizedPrecision != null && !normalizedPrecision.trim().isEmpty()
                && normalizedPrecision.matches("\\d+")) {
            return normalizedType.toUpperCase() + "(" + normalizedPrecision + ")";
        }
        return normalizedType.toUpperCase();
    }

    private boolean isRequiredMark(String marker) {
        String normalized = lower(marker);
        return "v".equals(normalized) || "y".equals(normalized) || "yes".equals(normalized)
                || "true".equals(normalized) || "1".equals(normalized) || "not null".equals(normalized);
    }

    private String normalizePairBase(String source) {
        if (source == null) {
            return "";
        }
        return source.toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\-：:\\s]+$", "");
    }

    private List<List<String>> readReferenceTemplateRows(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (fileName.endsWith(".csv")) {
            return readCsvRows(file);
        }
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return rows;
            }
            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                int lastCellNum = row == null ? -1 : row.getLastCellNum();
                if (lastCellNum < 0) {
                    rows.add(new ArrayList<>());
                    continue;
                }
                StringBuilder tsv = new StringBuilder(1024 * 1024);
                int batchCount = 0;
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < lastCellNum; i++) {
                    Cell cell = row.getCell(i);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                rows.add(cells);
            }
        } catch (Exception e) {
            throw new RuntimeException("读取参考模板失败: " + e.getMessage(), e);
        }
        return rows;
    }

    private List<List<String>> readCsvRows(MultipartFile file) throws IOException {
        String content;
        try (InputStreamReader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            content = sb.toString();
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentCell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    currentCell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                currentRow.add(currentCell.toString().trim());
                currentCell.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !inQuotes) {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                currentRow.add(currentCell.toString().trim());
                currentCell.setLength(0);
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            } else {
                currentCell.append(ch);
            }
        }
        if (currentCell.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(currentCell.toString().trim());
            rows.add(currentRow);
        }
        return rows;
    }

    private Set<String> loadPhysicalColumns(String tableName) {
        List<String> columns = jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ?", String.class, tableName);
        Set<String> result = new HashSet<>();
        for (String column : columns) {
            if (column != null) {
                result.add(column.toLowerCase());
            }
        }
        return result;
    }

    private static class ColumnStat {
        int colIndex;
        String headerName;
        int nonBlankCount = 0;
        boolean couldBeDate = true;
        boolean couldBeNumber = true;
        boolean hasDecimal = false;
        Set<String> uniqueValues = new HashSet<>();
        List<String> rawValues = new ArrayList<>();

        ColumnStat(int idx, String name) {
            this.colIndex = idx;
            this.headerName = name;
        }
    }

    /**
     * 
     * 5. 生成当前表单对应Excel 填报模板
     * 
     */

    public void exportTemplate(String formId, OutputStream outputStream) throws IOException {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {
            });

        } catch (JsonProcessingException e) {
            throw new RuntimeException("表单解析错误", e);
        }

        // 核心变更：下载模板时，排除掉被管理员标记为“在表单中隐藏”的业务字段
        fields.removeIf(f -> Boolean.TRUE.equals(f.getHideInForm()));

        List<String> templateHeaders = new ArrayList<>();
        Map<String, String> displayNameByHeader = new LinkedHashMap<>();

        // 对“参考模板创建”的表单，下载模板优先使用原始 excel 表头，避免用户无法按模板回传。
        if (form.getReferenceTemplateConfig() != null && !form.getReferenceTemplateConfig().trim().isEmpty()) {
            try {
                Map<String, Object> referenceConfig = objectMapper.readValue(
                        form.getReferenceTemplateConfig(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object mappingsObj = referenceConfig.get("headerMappings");
                if (mappingsObj instanceof List<?>) {
                    List<Map<String, Object>> mappingRows = new ArrayList<>();
                    for (Object item : (List<?>) mappingsObj) {
                        if (!(item instanceof Map<?, ?>)) {
                            continue;
                        }
                        Map<?, ?> raw = (Map<?, ?>) item;
                        String excelHeader = asText(raw.get("excelHeader"));
                        String columnName = asText(raw.get("columnName"));
                        Integer columnIndex = asInteger(raw.get("columnIndex"));
                        if (excelHeader == null || excelHeader.trim().isEmpty() || columnName == null
                                || columnName.trim().isEmpty()) {
                            continue;
                        }
                        Map<String, Object> row = new HashMap<>();
                        row.put("excelHeader", excelHeader.trim());
                        row.put("columnName", columnName.trim());
                        row.put("columnIndex", columnIndex);
                        mappingRows.add(row);
                    }
                    mappingRows.sort((a, b) -> {
                        Integer ai = (Integer) a.get("columnIndex");
                        Integer bi = (Integer) b.get("columnIndex");
                        if (ai == null && bi == null)
                            return 0;
                        if (ai == null)
                            return 1;
                        if (bi == null)
                            return -1;
                        return Integer.compare(ai, bi);
                    });

                    for (Map<String, Object> row : mappingRows) {
                        String excelHeader = String.valueOf(row.get("excelHeader"));
                        String columnName = String.valueOf(row.get("columnName"));
                        
                        // 校验该列是否在业务字段列表中（且未被隐藏）
                        FieldDef targetField = fields.stream()
                                .filter(f -> f.getColumnName() != null
                                        && f.getColumnName().equalsIgnoreCase(columnName))
                                .findFirst()
                                .orElse(null);
                        
                        if (targetField != null) {
                            templateHeaders.add(excelHeader);
                            displayNameByHeader.put(excelHeader, 
                                targetField.getName() == null || targetField.getName().trim().isEmpty() 
                                ? excelHeader : targetField.getName());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("下载模板解析 referenceTemplateConfig 失败, formId={}", formId, e);
            }
        }

        // 非参考模板（或解析失败）走原逻辑：第一行使用数据库列名
        if (templateHeaders.isEmpty()) {
            for (FieldDef field : fields) {
                if (field.getColumnName() == null || field.getColumnName().trim().isEmpty()) {
                    continue;
                }
                String header = field.getColumnName();
                templateHeaders.add(header);
                displayNameByHeader.put(header,
                        field.getName() == null || field.getName().trim().isEmpty() ? header : field.getName());
            }
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet(form.getName() != null ? form.getName() : form.getTableName());

            // 仅保留一行：业务表头
            Row headerRow = sheet.createRow(0);
            int colIndex = 0;

            for (String header : templateHeaders) {
                Cell headerCell = headerRow.createCell(colIndex);
                // 优先使用显示名称（字段中文名），对于参考模板则直接使用其存储的原始 excelHeader (header 变量)
                String display = displayNameByHeader.getOrDefault(header, header);

                // 如果是参考模板创建的表单，header 变量本身就是原始 excel 表头，直接设置即可
                // 如果是常规表单，header 是数据库字段名，display 是字段中文名
                if (form.getReferenceTemplateConfig() != null && !form.getReferenceTemplateConfig().trim().isEmpty()) {
                    headerCell.setCellValue(header);
                } else {
                    headerCell.setCellValue(display);
                }

                // 自适应列宽
                sheet.autoSizeColumn(colIndex);
                colIndex++;
            }

            workbook.write(outputStream);

        }

    }

    /**
     * 
     * 6. 解析上传Excel，将每一行作为一条填报记录写入动态物理表
     * 
     */

    /**
     * 6. 将 Excel 数据批量导入物理表
     * 优化：元数据计算外提，分片分批入库
     */
    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public Map<String, Object> importData(String formId, MultipartFile file, String mode, String creator, boolean isAdmin)
            throws IOException {
        if (file.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
            throw new RuntimeException("上传文件过大（超过 " + maxFileSizeMb + "MB），为了确保系统稳定性，请将数据分批进行导入。");
        }
        DataFillForm form = formMapper.selectById(formId);
        if (form == null)
            throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();

        List<FieldDef> fields;
        try {
            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("表单解析错误", e);
        }

        Map<String, String> headerMap = new HashMap<>();
        Map<String, String> normalizedHeaderMap = new HashMap<>();
        Set<String> fieldColumnNames = new HashSet<>();
        List<com.example.datafill.dto.ReferenceFieldMapping> referenceHeaderMappings = new ArrayList<>();
        for (FieldDef f : fields) {
            if (f.getColumnName() != null) {
                String columnName = f.getColumnName().trim();
                headerMap.put(columnName, f.getColumnName());
                normalizedHeaderMap.putIfAbsent(normalizeHeaderKey(columnName), f.getColumnName());
                fieldColumnNames.add(columnName.toLowerCase());
            }
            if (f.getName() != null) {
                String displayName = f.getName().trim();
                headerMap.put(displayName, f.getColumnName());
                headerMap.put(f.getName().replaceAll("[\\r\\n]+", "").trim(), f.getColumnName());
                normalizedHeaderMap.putIfAbsent(normalizeHeaderKey(displayName), f.getColumnName());
            }
        }

        // 参考模板创建的表单需要额外支持“上传文件原始表头 -> 数据库字段”的映射，
        // 否则像 "Billing Source" 这类英文头会落不到 billing_source，最终掉进 extra_data。
        if (form.getReferenceTemplateConfig() != null && !form.getReferenceTemplateConfig().trim().isEmpty()) {
            try {
                Map<String, Object> referenceConfig = objectMapper.readValue(
                        form.getReferenceTemplateConfig(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object mappingsObj = referenceConfig.get("headerMappings");
                if (mappingsObj instanceof List<?>) {
                    List<?> mappings = (List<?>) mappingsObj;
                    for (Object item : mappings) {
                        if (!(item instanceof Map<?, ?>)) {
                            continue;
                        }
                        Map<?, ?> mapping = (Map<?, ?>) item;
                        Object excelHeaderObj = mapping.get("excelHeader");
                        Object columnNameObj = mapping.get("columnName");
                        if (excelHeaderObj == null || columnNameObj == null) {
                            continue;
                        }
                        String excelHeader = String.valueOf(excelHeaderObj).trim();
                        String columnName = String.valueOf(columnNameObj).trim();
                        Object jsonMappedObj = mapping.get("jsonMapped");
                        boolean jsonMapped = jsonMappedObj instanceof Boolean
                                ? (Boolean) jsonMappedObj
                                : Boolean.parseBoolean(String.valueOf(jsonMappedObj));
                        if (excelHeader.trim().isEmpty() || columnName.trim().isEmpty()) {
                            continue;
                        }
                        headerMap.put(excelHeader, columnName);
                        headerMap.put(excelHeader.replaceAll("[\\r\\n]+", "").trim(), columnName);
                        normalizedHeaderMap.putIfAbsent(normalizeHeaderKey(excelHeader), columnName);
                        referenceHeaderMappings.add(new com.example.datafill.dto.ReferenceFieldMapping(null,
                                excelHeader, columnName, jsonMapped));
                    }
                }
            } catch (Exception e) {
                // 保留回退行为，但输出日志方便排查“有数据却映射不到列”的问题
                log.warn("解析 referenceTemplateConfig.headerMappings 失败, formId={}", form.getId(), e);
            }
        }

        int totalCount = 0;
        java.util.Set<String> unresolvedHeaders = new java.util.LinkedHashSet<>();
        int skippedUnmappedRowCount = 0;
        Integer firstSkippedUnmappedRowNum = null;

        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(1000)
                .bufferSize(131072)
                .open(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", true);
                res.put("count", 0);
                return res;
            }

            java.util.Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", true);
                res.put("count", 0);
                return res;
            }

            Row headerRow = rowIterator.next();
            if (headerRow == null) {
                Map<String, Object> res = new HashMap<>();
                res.put("success", true);
                res.put("count", 0);
                return res;
            }

            int lastCol = headerRow.getLastCellNum();
            String[] headers = new String[lastCol];
            boolean isTemplate = false;
            org.apache.poi.ss.usermodel.DataFormatter dataFormatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 0; i < lastCol; i++) {
                Cell c = headerRow.getCell(i);
                if (c != null) {
                    headers[i] = dataFormatter.formatCellValue(c).trim();
                    final String hn = headers[i];
                    if (fields.stream().anyMatch(f -> hn.equals(f.getColumnName())))
                        isTemplate = true;
                }
            }

            Map<String, List<Integer>> groups = new HashMap<>();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(.*?)(\\d*)$");
            for (int c = 0; c < lastCol; c++) {
                if (headers[c] == null)
                    continue;
                java.util.regex.Matcher m = pattern.matcher(headers[c].trim());
                if (m.matches()) {
                    String s = m.group(2);
                    if (s.isEmpty())
                        s = "0";
                    groups.computeIfAbsent(s, k -> new ArrayList<>()).add(c);
                }
            }

            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> savedPairs = new ArrayList<>();
            if (form.getKvConfig() != null && !form.getKvConfig().trim().isEmpty()) {
                try {
                    savedPairs = objectMapper.readValue(form.getKvConfig(),
                            new TypeReference<List<com.example.datafill.dto.ExcelParseResult.DetectedPair>>() {
                            });
                } catch (Exception e) {
                }
            }

            int startRow = 1;
            boolean checkedTemplate = false;
            int BATCH_SIZE = 10000;
            List<Map<String, Object>> buffer = new ArrayList<>(BATCH_SIZE);
            boolean hasReferenceMappings = !referenceHeaderMappings.isEmpty();

            java.util.Set<String> importSystemColumns = new java.util.HashSet<>(EXISTING_TABLE_SYSTEM_COLUMNS);
            importSystemColumns.add("ctime");
            importSystemColumns.add("mtime");
            importSystemColumns.add("create_time");
            importSystemColumns.add("update_time");
            importSystemColumns.add("creator");
            importSystemColumns.add("applicantemail");
            importSystemColumns.add("applicantname");
            importSystemColumns.add("applicant_email");
            importSystemColumns.add("applicant_name");

            java.util.Set<String> businessFieldColumns = new java.util.HashSet<>();
            java.util.Map<String, String> requiredFieldDisplayByColumn = new java.util.LinkedHashMap<>();
            boolean explicitExtraDataField = false;
            for (FieldDef f : fields) {
                if (f.getColumnName() == null || f.getColumnName().trim().isEmpty())
                    continue;
                String col = f.getColumnName().trim().toLowerCase();
                String origCol = f.getColumnName().trim();
                if ("extra_data".equals(col)) {
                    explicitExtraDataField = true;
                    continue;
                }
                if (importSystemColumns.contains(col))
                    continue;
                businessFieldColumns.add(col);
                if (Boolean.TRUE.equals(f.getRequired()) && !Boolean.TRUE.equals(f.getHideInForm())) {
                    String display = (f.getName() == null || f.getName().trim().isEmpty()) ? origCol : f.getName();
                    requiredFieldDisplayByColumn.put(origCol, display);
                }
            }

            String[] cachedPinyinHeaders = new String[lastCol];
            String[] cachedDbCols = new String[lastCol];
            boolean[] isDateColumn = new boolean[lastCol];
            boolean[] dateColumnChecked = new boolean[lastCol];
            boolean[] cachedIsBusinessCol = new boolean[lastCol];
            boolean[] cachedIsJsonCol = new boolean[lastCol];
            String[] cachedJsonKeys = new String[lastCol];
            Map<String, Integer> actualHeaderIndexMap = new LinkedHashMap<>();

            // Build Json lookup set first
            Set<String> jsonColumnNames = new HashSet<>();
            for (FieldDef f : fields) {
                if (f.getColumnName() == null)
                    continue;
                String col = f.getColumnName().trim().toLowerCase();
                if (col.endsWith("_json") || "JSONB".equalsIgnoreCase(f.getDbType())
                        || "JSON".equalsIgnoreCase(f.getDbType())) {
                    jsonColumnNames.add(col);
                }
            }

            for (int c = 0; c < lastCol; c++) {
                if (headers[c] != null) {
                    cachedPinyinHeaders[c] = generateDwColumnName(headers[c], c);
                    String dbCol = headerMap.get(headers[c]);
                    if (dbCol == null)
                        dbCol = headerMap.get(headers[c].replaceAll("[\\r\\n]+", ""));
                    if (dbCol == null)
                        dbCol = normalizedHeaderMap.get(normalizeHeaderKey(headers[c]));
                    cachedDbCols[c] = dbCol;

                    if (dbCol != null) {
                        String lowerDbCol = dbCol.toLowerCase();
                        cachedIsBusinessCol[c] = businessFieldColumns.contains(lowerDbCol);
                        cachedIsJsonCol[c] = jsonColumnNames.contains(lowerDbCol);
                    }
                    cachedJsonKeys[c] = !headers[c].trim().isEmpty() ? headers[c].trim() : cachedPinyinHeaders[c];

                    actualHeaderIndexMap.putIfAbsent(headers[c].trim(), c);
                    actualHeaderIndexMap.putIfAbsent(headers[c].replaceAll("[\\r\\n]+", "").trim(), c);
                    actualHeaderIndexMap.putIfAbsent(normalizeHeaderKey(headers[c]), c);
                    if (hasReferenceMappings && dbCol == null && !headers[c].trim().isEmpty()) {
                        unresolvedHeaders.add(headers[c].trim());
                    }
                }
            }
            if (hasReferenceMappings && !unresolvedHeaders.isEmpty()) {
                java.util.List<String> unresolvedSamples = unresolvedHeaders.stream().limit(12)
                        .collect(java.util.stream.Collectors.toList());
                log.warn("参考模板导入存在未映射表头, formId={}, unresolvedCount={}, samples={}",
                        formId, unresolvedHeaders.size(), unresolvedSamples);
            }

            record KVPairConfig(int fk, int fv, String targetJsonCol) {
            }
            List<KVPairConfig> activeKVPairs = new ArrayList<>();

            for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                List<Integer> idxs = entry.getValue();
                if (idxs.size() < 2)
                    continue;
                String suffix = entry.getKey();
                Integer foundK = null, foundV = null;
                String targetJsonCol = "extra_data";

                if (!savedPairs.isEmpty()) {
                    for (com.example.datafill.dto.ExcelParseResult.DetectedPair sp : savedPairs) {
                        if (sp.getSuffixes().contains(suffix)) {
                            Integer fk = null, fv = null;
                            for (Integer idx : idxs) {
                                String h = headers[idx];
                                String mappedCol = null;
                                if (h != null) {
                                    mappedCol = headerMap.get(h.trim());
                                    if (mappedCol == null)
                                        mappedCol = headerMap.get(h.replaceAll("[\\r\\n]+", "").trim());
                                    if (mappedCol == null)
                                        mappedCol = normalizedHeaderMap.get(normalizeHeaderKey(h));
                                }
                                // 仅跳过“已映射到普通业务字段”的列；映射到 JSON 列的仍允许参与键值对识别
                                if (mappedCol != null && !jsonColumnNames.contains(mappedCol.toLowerCase()))
                                    continue;

                                String source = (h == null || h.trim().isEmpty() || "...".equals(h.trim())) ? mappedCol
                                        : h;
                                if (source == null || source.trim().isEmpty())
                                    continue;
                                String name = source.toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\s]+$",
                                        "");
                                if (name.equals(sp.getKeyBase().toLowerCase()))
                                    fk = idx;
                                else if (name.equals(sp.getValueBase().toLowerCase()))
                                    fv = idx;
                            }
                            if (fk != null && fv != null) {
                                foundK = fk;
                                foundV = fv;
                                targetJsonCol = sp.getSuggestedColumnName() != null ? sp.getSuggestedColumnName()
                                        : "extra_data";
                                break;
                            }
                        }
                    }
                }

                if (foundK != null && foundV != null) {
                    activeKVPairs.add(new KVPairConfig(foundK, foundV, targetJsonCol));
                }
            }

            // 对参考模板优先按“已展开的真实表头映射 + kvConfig”做精确配对，避免退化成把表头名塞进 JSON。
            if (!savedPairs.isEmpty() && !referenceHeaderMappings.isEmpty()) {
                Map<String, KVPairConfig> precisePairMap = new LinkedHashMap<>();
                for (com.example.datafill.dto.ExcelParseResult.DetectedPair sp : savedPairs) {
                    String targetJsonCol = sp.getSuggestedColumnName() != null ? sp.getSuggestedColumnName()
                            : "extra_data";
                    for (String sTemp : sp.getSuffixes()) {
                        final String suffixText = sTemp == null ? "" : sTemp.trim();
                        Integer fk = null;
                        Integer fv = null;
                        for (com.example.datafill.dto.ReferenceFieldMapping mapping : referenceHeaderMappings) {
                            if (!targetJsonCol.equalsIgnoreCase(mapping.getColumnName()))
                                continue;
                            String header = mapping.getExcelHeader();
                            if (header == null || header.trim().isEmpty())
                                continue;
                            String normalizedHeader = header.trim();
                            String headerSuffix = "";
                            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(.*?)(\\d*)$")
                                    .matcher(normalizedHeader);
                            if (matcher.matches()) {
                                headerSuffix = matcher.group(2) == null ? "" : matcher.group(2);
                            }
                            if (!suffixText.equals(headerSuffix))
                                continue;
                            Integer actualIdx = actualHeaderIndexMap.get(normalizedHeader);
                            if (actualIdx == null) {
                                actualIdx = actualHeaderIndexMap
                                        .get(normalizedHeader.replaceAll("[\\r\\n]+", "").trim());
                            }
                            if (actualIdx == null) {
                                actualIdx = actualHeaderIndexMap.get(normalizeHeaderKey(normalizedHeader));
                            }
                            if (actualIdx == null)
                                continue;

                            String base = normalizePairBase(normalizedHeader);
                            if (base.equals(sp.getKeyBase().toLowerCase())) {
                                fk = actualIdx;
                            } else if (base.equals(sp.getValueBase().toLowerCase())) {
                                fv = actualIdx;
                            }
                        }
                        if (fk != null && fv != null) {
                            precisePairMap.put(targetJsonCol + "|" + suffixText,
                                    new KVPairConfig(fk, fv, targetJsonCol));
                        }
                    }
                }
                if (!precisePairMap.isEmpty()) {
                    activeKVPairs = new ArrayList<>(precisePairMap.values());
                }
            }

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                if (row == null)
                    continue;

                if (!checkedTemplate && row.getRowNum() == 1) {
                    checkedTemplate = true;
                    boolean isSecondHeader = false;
                    for (int c = 0; c < lastCol; c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null) {
                            String val = dataFormatter.formatCellValue(cell).trim();
                            if (!val.isEmpty() && fields.stream().anyMatch(f -> val.equals(f.getName()))) {
                                isSecondHeader = true;
                                break;
                            }
                        }
                    }
                    if (isSecondHeader) {
                        startRow = 2;
                        continue;
                    }
                }

                if (row.getRowNum() < startRow)
                    continue;
                Map<String, Object> rowData = new LinkedHashMap<>();
                Map<String, Map<String, Object>> dynamicExtras = new LinkedHashMap<>();
                Map<String, Object> defaultExtra = new LinkedHashMap<>();
                boolean empty = true;
                Set<Integer> consumed = new HashSet<>();

                for (KVPairConfig pc : activeKVPairs) {
                    Cell kc = row.getCell(pc.fk()), vc = row.getCell(pc.fv());
                    String kvStr = (kc == null) ? null : dataFormatter.formatCellValue(kc).trim();
                    Object vvObj = null;
                    if (vc != null) {
                        if (vc.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            int vCol = pc.fv();
                            if (!dateColumnChecked[vCol]) {
                                isDateColumn[vCol] = org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(vc);
                                dateColumnChecked[vCol] = true;
                            }
                            vvObj = isDateColumn[vCol] ? vc.getDateCellValue() : vc.getNumericCellValue();
                        } else {
                            String s = dataFormatter.formatCellValue(vc).trim();
                            // Handle formatted numbers like "1,875.00"
                            if (s.contains(",") && s.replace(",", "").matches("-?\\d*\\.?\\d+")) {
                                try {
                                    vvObj = new java.math.BigDecimal(s.replace(",", ""));
                                } catch (Exception e) {
                                    vvObj = s;
                                }
                            } else {
                                vvObj = s;
                            }
                        }
                    }
                    if (kvStr != null && !kvStr.isEmpty() && vvObj != null && !"".equals(vvObj)) {
                        dynamicExtras.computeIfAbsent(pc.targetJsonCol(), k -> new LinkedHashMap<>()).put(kvStr, vvObj);
                        consumed.add(pc.fk());
                        consumed.add(pc.fv());
                    }
                }

                boolean hasMappedBusinessValue = false;
                for (int c = 0; c < lastCol; c++) {
                    if (consumed.contains(c) || headers[c] == null)
                        continue;
                    Cell cell = row.getCell(c);
                    if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK)
                        continue;
                    Object val = null;
                    org.apache.poi.ss.usermodel.CellType type = cell.getCellType();

                    if (type == org.apache.poi.ss.usermodel.CellType.STRING) {
                        val = cell.getStringCellValue();
                    } else if (type == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        double dVal = cell.getNumericCellValue();
                        if (!dateColumnChecked[c]) {
                            isDateColumn[c] = org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell);
                            dateColumnChecked[c] = true;
                        }
                        // 动态复查：Excel 允许同一列不同单元格有不同格式，不能只依赖第一行的缓存
                        boolean isDate = isDateColumn[c]
                                || org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell);

                        if (isDate && dVal > 0 && dVal < 3000000) {
                            // 对于在合法范围内的正常 Excel 日期（如 46082），直接获取原生的 java.util.Date 对象。
                            // 避免使用 DataFormatter，因为它极易受到服务器或 Excel locale 的影响而输出如 "3/1/26 5:45"
                            // 的奇葩文本导致后端入库截断报错，
                            // 返回原生 Date 可以保证系统底层使用统一的 "yyyy-MM-dd HH:mm:ss" 进行高质量落表。
                            val = cell.getDateCellValue();
                        } else {
                            // 对于超出合法范围的数字（如直接填在日期栏的 20260312），当做纯阿拉伯数字字符串处理并拦截
                            val = new java.math.BigDecimal(dVal).stripTrailingZeros().toPlainString();
                        }
                    } else if (type == org.apache.poi.ss.usermodel.CellType.BOOLEAN) {
                        val = cell.getBooleanCellValue();
                    } else if (type == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        try {
                            val = cell.getStringCellValue();
                        } catch (Exception e) {
                            val = cell.getNumericCellValue();
                        }
                    } else {
                        String s = dataFormatter.formatCellValue(cell).trim();
                        // Handle formatted numbers like "1,875.00"
                        if (s.contains(",") && s.replace(",", "").matches("-?\\d*\\.?\\d+")) {
                            try {
                                val = new java.math.BigDecimal(s.replace(",", ""));
                            } catch (Exception e) {
                                val = s;
                            }
                        } else {
                            val = s;
                        }
                    }
                    if (val != null && !"".equals(val)) {
                        empty = false;
                        String dbCol = cachedDbCols[c];
                        if (dbCol != null) {
                            if (cachedIsJsonCol[c]) {
                                dynamicExtras.computeIfAbsent(dbCol, k -> new LinkedHashMap<>()).put(cachedJsonKeys[c],
                                        val);
                            } else {
                                rowData.put(dbCol, val);
                                if (cachedIsBusinessCol[c])
                                    hasMappedBusinessValue = true;
                            }
                        } else
                            defaultExtra.put(cachedPinyinHeaders[c], val);
                    }
                }

                if (empty && dynamicExtras.isEmpty() && defaultExtra.isEmpty())
                    continue;

                // 合并所有其它的到 extra_data
                // 若模板已定义了业务 JSON 字段（如 xxx_json），默认不再把剩余列强行并入 extra_data。
                // 只有当表单字段中显式存在 extra_data 时，才把 defaultExtra 合并进去。
                if (!defaultExtra.isEmpty() && fieldColumnNames.contains("extra_data")) {
                    dynamicExtras.computeIfAbsent("extra_data", k -> new LinkedHashMap<>()).putAll(defaultExtra);
                }

                // 将所有 JSON 列转换为字符串并加入 rowData
                for (Map.Entry<String, Map<String, Object>> exEntry : dynamicExtras.entrySet()) {
                    Map<String, Object> extraMap = exEntry.getValue();
                    if (extraMap.isEmpty()) {
                        rowData.put(exEntry.getKey(), "{}");
                        continue;
                    }
                    // Fast JSON building for simple maps
                    StringBuilder jsonSb = new StringBuilder(extraMap.size() * 64);
                    jsonSb.append("{");
                    boolean first = true;
                    for (Map.Entry<String, Object> entry : extraMap.entrySet()) {
                        if (!first)
                            jsonSb.append(",");
                        first = false;
                        jsonSb.append("\"").append(entry.getKey().replace("\"", "\\\"")).append("\":");
                        Object v = entry.getValue();
                        if (v == null)
                            jsonSb.append("null");
                        else if (v instanceof Number || v instanceof Boolean)
                            jsonSb.append(v.toString());
                        else {
                            String s = v.toString();
                            if (NUMBER_PATTERN.matcher(s).matches()) {
                                jsonSb.append(s);
                            } else {
                                jsonSb.append("\"")
                                        .append(s.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"))
                                        .append("\"");
                            }
                        }
                    }
                    jsonSb.append("}");
                    rowData.put(exEntry.getKey(), jsonSb.toString());
                }

                if (!hasMappedBusinessValue && !explicitExtraDataField) {
                    skippedUnmappedRowCount++;
                    if (firstSkippedUnmappedRowNum == null) {
                        firstSkippedUnmappedRowNum = row.getRowNum() + 1;
                    }
                    continue;
                }

                if (!requiredFieldDisplayByColumn.isEmpty()) {
                    List<String> missingRequired = new ArrayList<>();
                    for (Map.Entry<String, String> requiredEntry : requiredFieldDisplayByColumn.entrySet()) {
                        String requiredCol = requiredEntry.getKey();
                        if (isBlankValue(rowData.get(requiredCol))) {
                            missingRequired.add(requiredEntry.getValue() + "(" + requiredCol + ")");
                        }
                    }
                    if (!missingRequired.isEmpty()) {
                        throw new RuntimeException(
                                "导入失败：第 " + (row.getRowNum() + 1) + " 行缺少必填字段: " + String.join(", ", missingRequired));
                    }
                }

                if (fieldColumnNames.contains("ctime") && !rowData.containsKey("ctime")) {
                    rowData.put("ctime", LocalDateTime.now());
                }
                if (fieldColumnNames.contains("mtime") && !rowData.containsKey("mtime")) {
                    rowData.put("mtime", LocalDateTime.now());
                }

                rowData.put(EXCEL_ROW_META_KEY, row.getRowNum() + 1);
                if (creator != null && !creator.trim().isEmpty())
                    rowData.put("creator", creator);
                buffer.add(rowData);

                if (buffer.size() >= BATCH_SIZE) {
                    flushImportBuffer(formId, buffer, isAdmin);
                    totalCount += buffer.size();
                    buffer.clear();
                }
            }

            if (!buffer.isEmpty()) {
                flushImportBuffer(formId, buffer, isAdmin);
                totalCount += buffer.size();
                buffer.clear();
            }

            if (totalCount == 0 && skippedUnmappedRowCount > 0) {
                String unresolvedHint = unresolvedHeaders.isEmpty()
                        ? ""
                        : ("；未匹配表头示例: " + String.join(", ",
                                unresolvedHeaders.stream().limit(8).collect(java.util.stream.Collectors.toList())));
                throw new RuntimeException("导入失败：检测到 " + skippedUnmappedRowCount
                        + " 行数据未映射到业务字段（首行: 第 " + firstSkippedUnmappedRowNum + " 行）"
                        + "，请确认上传文件表头与模板一致" + unresolvedHint);
            }

        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", totalCount);
        if (!unresolvedHeaders.isEmpty()) {
            result.put("unresolvedHints", String.join(", ", unresolvedHeaders));
        }
        return result;
    }

    private void flushImportBuffer(String formId, List<Map<String, Object>> rows, boolean isAdmin) {
        try {
            dataDmlService.batchInsertRowData(formId, rows, isAdmin);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("line\\s+(\\d+):", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(msg);
            if (matcher.find()) {
                try {
                    int copyLine = Integer.parseInt(matcher.group(1));
                    int idx = copyLine - 1;
                    if (idx >= 0 && idx < rows.size()) {
                        Object excelRowObj = rows.get(idx).get(EXCEL_ROW_META_KEY);
                        Integer excelRow = asInteger(excelRowObj);
                        if (excelRow != null && excelRow > 0) {
                            throw new RuntimeException("导入失败：Excel 第 " + excelRow + " 行写库失败。原始错误：" + msg, e);
                        }
                    }
                } catch (Exception ignored) {
                    // keep fallback error below
                }
            }
            throw e;
        }
    }

    /**
     * 
     * 7. 解析上传Excel 表头，生成字段定义列(增强
     * 
     */

    public com.example.datafill.dto.ReferenceTemplateParseResult parseReferenceTemplate(MultipartFile file)
            throws IOException {
        if (file.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
            throw new RuntimeException("上传文件过大（超过 " + maxFileSizeMb + "MB），为了确保系统稳定性，请将数据分批进行识别。");
        }
        List<List<String>> rows = readReferenceTemplateRows(file);
        if (rows.isEmpty()) {
            throw new RuntimeException("参考模板内容为空");
        }

        int dbFieldRowIndex = findRowIndexByFirstCell(rows, "对应数据库字段");
        int excelHeaderRowIndex = findRowIndexByFirstCell(rows, "excel表头");
        int filterRowIndex = findRowIndexByFirstCell(rows, "筛选器");
        int tableNameRowIndex = findRowIndexByFirstCell(rows, "表名");
        int tableCommentRowIndex = findRowIndexByFirstCell(rows, "表注释");
        int referenceHeaderRowIndex = findRowIndexByFirstCell(rows, "数据库表参考");

        if (dbFieldRowIndex < 0 || excelHeaderRowIndex < 0 || referenceHeaderRowIndex < 0) {
            throw new RuntimeException("未识别到参考模板关键区块，请确认包含“对应数据库字段 / excel表头 / 数据库表参考”");
        }

        List<String> dbColumnsRow = rows.get(dbFieldRowIndex);
        List<String> excelHeadersRow = rows.get(excelHeaderRowIndex);
        List<String> filterColumns = filterRowIndex >= 0 ? splitMultiValueCells(rows.get(filterRowIndex), 1)
                : new ArrayList<>();
        Set<String> filterColumnSet = new LinkedHashSet<>();
        for (String filterColumn : filterColumns) {
            filterColumnSet.add(filterColumn.toLowerCase());
        }

        List<com.example.datafill.dto.ReferenceFieldMapping> headerMappings = new ArrayList<>();
        Map<String, List<com.example.datafill.dto.ReferenceFieldMapping>> jsonColumnMappings = new LinkedHashMap<>();
        int maxColumnSize = Math.max(dbColumnsRow.size(), excelHeadersRow.size());
        String activeJsonColumn = null;
        for (int columnIndex = 1; columnIndex < maxColumnSize; columnIndex++) {
            String dbColumn = readCell(dbColumnsRow, columnIndex);
            String excelHeader = readCell(excelHeadersRow, columnIndex);
            if (!dbColumn.trim().isEmpty()) {
                activeJsonColumn = dbColumn.toLowerCase().endsWith("_json") ? dbColumn : null;
            }
            // 参考模板里有些列用 "..." 或者表头为空做了省略展示。
            // 我们不能因为 excelHeader 为空就跳过映射，否则 kv 对应的列索引会丢失。
            // 这里仅在 dbColumn 也为空时才跳过。
            if (excelHeader.trim().isEmpty() && dbColumn.trim().isEmpty()) {
                continue;
            }

            if (excelHeader.trim().isEmpty()) {
                if (!dbColumn.trim().isEmpty() && !dbColumn.toLowerCase().endsWith("_json")) {
                    activeJsonColumn = null;
                }
            }
            String mappedColumn = dbColumn;
            boolean jsonMapped = false;
            if (mappedColumn.trim().isEmpty() && activeJsonColumn != null) {
                mappedColumn = activeJsonColumn;
                jsonMapped = true;
            } else if (!mappedColumn.trim().isEmpty() && mappedColumn.toLowerCase().endsWith("_json")) {
                jsonMapped = true;
            }

            // 这里的 columnIndex 必须是“headerMappings 数组内的顺序索引”，否则前端根据 originalHeaders
            // 下标回填字段时会错位
            int sequentialIndex = headerMappings.size();
            com.example.datafill.dto.ReferenceFieldMapping mapping = new com.example.datafill.dto.ReferenceFieldMapping(
                    sequentialIndex, excelHeader, mappedColumn, jsonMapped);
            headerMappings.add(mapping);
            if (!mappedColumn.trim().isEmpty() && mappedColumn.toLowerCase().endsWith("_json")) {
                jsonColumnMappings.computeIfAbsent(mappedColumn, key -> new ArrayList<>()).add(mapping);
            }
            if (!dbColumn.trim().isEmpty() && !dbColumn.toLowerCase().endsWith("_json")) {
                activeJsonColumn = null;
            }
        }

        List<com.example.datafill.dto.ReferenceTableColumn> referenceRows = new ArrayList<>();
        LinkedHashMap<String, FieldDef> fieldMap = new LinkedHashMap<>();
        for (int i = referenceHeaderRowIndex + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String firstCell = readCell(row, 0);
            String columnName = readCell(row, 1);
            if ("功能".equalsIgnoreCase(firstCell) || "说明".equalsIgnoreCase(firstCell) || "业务规则".equalsIgnoreCase(firstCell)) {
                break;
            }
            if (isRowBlank(row)) {
                continue;
            }
            if (columnName == null || columnName.trim().isEmpty() || !columnName.trim().matches("^[a-zA-Z0-9_]+$")) {
                continue;
            }

            com.example.datafill.dto.ReferenceTableColumn referenceRow = new com.example.datafill.dto.ReferenceTableColumn(
                    columnName,
                    readCell(row, 2),
                    readCell(row, 3),
                    readCell(row, 4),
                    readCell(row, 5),
                    readCell(row, 6));
            referenceRows.add(referenceRow);

            String normalizedColumnName = columnName.trim();
            boolean isSystem = EXISTING_TABLE_SYSTEM_COLUMNS.contains(normalizedColumnName.toLowerCase());

            FieldDef field = new FieldDef();
            field.setColumnName(normalizedColumnName);
            field.setOriginalColumnName(normalizedColumnName);
            field.setName(referenceRow.getComment() == null || referenceRow.getComment().trim().isEmpty()
                    ? normalizedColumnName
                    : referenceRow.getComment().trim());
            String dbType = buildReferenceDbType(referenceRow.getFieldType(), referenceRow.getPrecision());
            field.setDbType(dbType);
            applyFieldTypeByDbType(field, dbType);
            field.setRequired(!isSystem && isRequiredMark(referenceRow.getNotNull()));
            field.setFilterable(filterColumnSet.contains(normalizedColumnName.toLowerCase()));
            field.setSystemLocked(isSystem);
            if (isSystem && !"extra_data".equalsIgnoreCase(normalizedColumnName)) {
                field.setHideInForm(true);
                field.setHideInList(true);
            }
            fieldMap.putIfAbsent(normalizedColumnName.toLowerCase(), field);
        }

        for (com.example.datafill.dto.ReferenceFieldMapping mapping : headerMappings) {
            if (mapping.getColumnName() == null || mapping.getColumnName().trim().isEmpty()) {
                continue;
            }
            boolean isSystem = EXISTING_TABLE_SYSTEM_COLUMNS.contains(mapping.getColumnName().toLowerCase());
            fieldMap.computeIfAbsent(mapping.getColumnName().toLowerCase(), key -> {
                FieldDef field = new FieldDef();
                field.setColumnName(mapping.getColumnName());
                field.setOriginalColumnName(mapping.getColumnName());
                field.setName(mapping.getExcelHeader() != null && !mapping.getExcelHeader().trim().isEmpty()
                        ? mapping.getExcelHeader()
                        : mapping.getColumnName());
                field.setDbType(mapping.getColumnName().toLowerCase().endsWith("_json") ? "jsonb" : "varchar(255)");
                applyFieldTypeByDbType(field, field.getDbType());
                field.setRequired(false);
                field.setFilterable(filterColumnSet.contains(mapping.getColumnName().toLowerCase()));
                field.setSystemLocked(isSystem);
                if (isSystem && !"extra_data".equalsIgnoreCase(mapping.getColumnName())) {
                    field.setHideInForm(true);
                    field.setHideInList(true);
                }
                return field;
            });
        }

        List<com.example.datafill.dto.ExcelParseResult.DetectedPair> kvPairs = new ArrayList<>();
        for (Map.Entry<String, List<com.example.datafill.dto.ReferenceFieldMapping>> entry : jsonColumnMappings
                .entrySet()) {
            kvPairs.addAll(detectReferenceJsonPairs(entry.getKey(), entry.getValue()));
        }

        headerMappings = expandReferenceHeaderMappings(headerMappings, kvPairs);
        jsonColumnMappings = groupReferenceMappingsByJsonColumn(headerMappings);
        kvPairs = new ArrayList<>();
        for (Map.Entry<String, List<com.example.datafill.dto.ReferenceFieldMapping>> entry : jsonColumnMappings
                .entrySet()) {
            kvPairs.addAll(detectReferenceJsonPairs(entry.getKey(), entry.getValue()));
        }

        String tableName = tableNameRowIndex >= 0 ? readCell(rows.get(tableNameRowIndex), 1) : "";
        String tableComment = tableCommentRowIndex >= 0 ? readCell(rows.get(tableCommentRowIndex), 1) : "";
        if (tableName.trim().isEmpty()) {
            throw new RuntimeException("未识别到参考模板中的表名");
        }

        // --- 核心增强：自动补全缺失的系统审计字段 ---
        java.util.Set<String> physicalColumns = new java.util.HashSet<>();
        for (FieldDef f : fieldMap.values()) {
            if (f.getColumnName() != null) physicalColumns.add(f.getColumnName().toLowerCase());
        }

        // 1. 强制补齐 id
        if (!physicalColumns.contains("id")) {
            FieldDef idField = new FieldDef();
            idField.setColumnName("id");
            idField.setOriginalColumnName("id");
            idField.setName("ID");
            idField.setDbType("int4");
            idField.setType("number");
            idField.setSystemLocked(true);
            idField.setHideInForm(true);
            idField.setHideInList(true);
            fieldMap.put("id", idField);
        }

        // 2. 补齐标准审计列（w_insert_dt, w_update_dt, delete_flag, load_user）
        // 不再使用 detectRole 动态拦截，而是只要物理列里没有显式的 w_insert_dt，就补齐一个；
        // 如果已经有了，确保其在 fieldMap 中存在。
        if (!physicalColumns.contains("w_insert_dt")) {
            FieldDef f = new FieldDef();
            f.setColumnName("w_insert_dt");
            f.setOriginalColumnName("w_insert_dt");
            f.setName("创建时间");
            f.setDbType("timestamp");
            f.setType("datetime");
            f.setSystemLocked(true);
            f.setHideInForm(true);
            f.setHideInList(true);
            fieldMap.put("w_insert_dt", f);
        }
        if (!physicalColumns.contains("w_update_dt")) {
            FieldDef f = new FieldDef();
            f.setColumnName("w_update_dt");
            f.setOriginalColumnName("w_update_dt");
            f.setName("更新时间");
            f.setDbType("timestamp");
            f.setType("datetime");
            f.setSystemLocked(true);
            f.setHideInForm(true);
            f.setHideInList(true);
            fieldMap.put("w_update_dt", f);
        }
        if (!physicalColumns.contains("delete_flag")) {
            FieldDef f = new FieldDef();
            f.setColumnName("delete_flag");
            f.setOriginalColumnName("delete_flag");
            f.setName("删除标记");
            f.setDbType("boolean");
            f.setType("boolean");
            f.setSystemLocked(true);
            f.setHideInForm(true);
            f.setHideInList(true);
            fieldMap.put("delete_flag", f);
        }
        if (!physicalColumns.contains("load_user") && !physicalColumns.contains("fill_user")) {
            FieldDef f = new FieldDef();
            f.setColumnName("load_user");
            f.setOriginalColumnName("load_user");
            f.setName("导入用户");
            f.setDbType("varchar(100)");
            f.setType("input");
            f.setSystemLocked(true);
            f.setHideInForm(true);
            f.setHideInList(true);
            fieldMap.put("load_user", f);
        }

        com.example.datafill.dto.ReferenceTemplateParseResult result = new com.example.datafill.dto.ReferenceTemplateParseResult();
        result.setTableName(tableName);
        result.setTableComment(tableComment.trim().isEmpty() ? tableName : tableComment);
        result.setFilterColumns(new ArrayList<>(filterColumnSet));
        result.setFields(new ArrayList<>(fieldMap.values()));
        result.setHeaderMappings(headerMappings);
        result.setReferenceRows(referenceRows);
        result.setKvPairs(kvPairs);
        result.setParserProfile("label_driven_reference_v1");
        return result;
    }

    private Map<String, List<com.example.datafill.dto.ReferenceFieldMapping>> groupReferenceMappingsByJsonColumn(
            List<com.example.datafill.dto.ReferenceFieldMapping> headerMappings) {
        Map<String, List<com.example.datafill.dto.ReferenceFieldMapping>> grouped = new LinkedHashMap<>();
        for (com.example.datafill.dto.ReferenceFieldMapping mapping : headerMappings) {
            String columnName = mapping.getColumnName();
            if (columnName != null && columnName.toLowerCase().endsWith("_json")) {
                grouped.computeIfAbsent(columnName, key -> new ArrayList<>()).add(mapping);
            }
        }
        return grouped;
    }

    private List<com.example.datafill.dto.ReferenceFieldMapping> expandReferenceHeaderMappings(
            List<com.example.datafill.dto.ReferenceFieldMapping> headerMappings,
            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> kvPairs) {
        if (headerMappings == null || headerMappings.isEmpty() || kvPairs == null || kvPairs.isEmpty()) {
            return headerMappings;
        }

        List<com.example.datafill.dto.ReferenceFieldMapping> result = new ArrayList<>();
        java.util.Set<String> expandedJsonColumns = new java.util.HashSet<>();

        for (int i = 0; i < headerMappings.size(); i++) {
            com.example.datafill.dto.ReferenceFieldMapping mapping = headerMappings.get(i);
            String columnName = mapping.getColumnName();
            if (columnName == null || !columnName.toLowerCase().endsWith("_json")
                    || expandedJsonColumns.contains(columnName)) {
                result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, mapping.getExcelHeader(),
                        mapping.getColumnName(), mapping.isJsonMapped()));
                continue;
            }

            List<com.example.datafill.dto.ReferenceFieldMapping> jsonMappings = new ArrayList<>();
            int j = i;
            while (j < headerMappings.size()) {
                com.example.datafill.dto.ReferenceFieldMapping current = headerMappings.get(j);
                if (!columnName.equals(current.getColumnName())) {
                    break;
                }
                jsonMappings.add(current);
                j++;
            }

            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> columnPairs = kvPairs.stream()
                    .filter(pair -> columnName.equalsIgnoreCase(pair.getSuggestedColumnName()))
                    .collect(java.util.stream.Collectors.toList());
            if (columnPairs.isEmpty()) {
                for (com.example.datafill.dto.ReferenceFieldMapping item : jsonMappings) {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, item.getExcelHeader(),
                            item.getColumnName(), item.isJsonMapped()));
                }
                expandedJsonColumns.add(columnName);
                i = j - 1;
                continue;
            }

            boolean containsEllipsis = jsonMappings.stream().anyMatch(item -> {
                String header = item.getExcelHeader();
                return header == null || header.trim().isEmpty() || "...".equals(header.trim());
            });
            if (!containsEllipsis) {
                for (com.example.datafill.dto.ReferenceFieldMapping item : jsonMappings) {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, item.getExcelHeader(),
                            item.getColumnName(), item.isJsonMapped()));
                }
                expandedJsonColumns.add(columnName);
                i = j - 1;
                continue;
            }

            com.example.datafill.dto.ExcelParseResult.DetectedPair pair = columnPairs.get(0);
            List<Integer> suffixNums = new ArrayList<>();
            for (String suffix : pair.getSuffixes()) {
                if (suffix == null || suffix.trim().isEmpty()) {
                    suffixNums.add(1);
                    continue;
                }
                try {
                    suffixNums.add(Integer.parseInt(suffix));
                } catch (Exception ignored) {
                }
            }
            if (suffixNums.isEmpty()) {
                for (com.example.datafill.dto.ReferenceFieldMapping item : jsonMappings) {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, item.getExcelHeader(),
                            item.getColumnName(), item.isJsonMapped()));
                }
                expandedJsonColumns.add(columnName);
                i = j - 1;
                continue;
            }

            int minSuffix = suffixNums.stream().min(Integer::compareTo).orElse(1);
            int maxSuffix = suffixNums.stream().max(Integer::compareTo).orElse(minSuffix);

            String keyTemplate = inferHeaderTemplate(jsonMappings, pair.getKeyBase());
            String valueTemplate = inferHeaderTemplate(jsonMappings, pair.getValueBase());
            if (keyTemplate == null || valueTemplate == null) {
                for (com.example.datafill.dto.ReferenceFieldMapping item : jsonMappings) {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, item.getExcelHeader(),
                            item.getColumnName(), item.isJsonMapped()));
                }
                expandedJsonColumns.add(columnName);
                i = j - 1;
                continue;
            }

            boolean keyFirst = inferKeyFirst(jsonMappings, pair.getKeyBase(), pair.getValueBase());
            for (int suffix = minSuffix; suffix <= maxSuffix; suffix++) {
                String keyHeader = renderHeaderTemplate(keyTemplate, suffix);
                String valueHeader = renderHeaderTemplate(valueTemplate, suffix);
                if (keyFirst) {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, keyHeader, columnName, true));
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, valueHeader, columnName, true));
                } else {
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, valueHeader, columnName, true));
                    result.add(new com.example.datafill.dto.ReferenceFieldMapping(null, keyHeader, columnName, true));
                }
            }

            expandedJsonColumns.add(columnName);
            i = j - 1;
        }

        for (int index = 0; index < result.size(); index++) {
            result.get(index).setColumnIndex(index);
        }
        return result;
    }

    private String resolveReferenceHeaderSource(com.example.datafill.dto.ReferenceFieldMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String header = mapping.getExcelHeader();
        if (header != null && !header.trim().isEmpty() && !"...".equals(header.trim())) {
            return header.trim();
        }
        return mapping.getColumnName() == null ? "" : mapping.getColumnName().trim();
    }

    private String normalizeReferenceBase(String source) {
        if (source == null) {
            return "";
        }
        return source.toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\-：:\\s]+$", "");
    }

    private String inferHeaderTemplate(
            List<com.example.datafill.dto.ReferenceFieldMapping> mappings,
            String targetBase) {
        if (mappings == null || targetBase == null) {
            return null;
        }
        for (com.example.datafill.dto.ReferenceFieldMapping mapping : mappings) {
            String source = resolveReferenceHeaderSource(mapping);
            if (source == null || source.trim().isEmpty())
                continue;
            String normalizedBase = normalizeReferenceBase(source);
            if (!targetBase.equalsIgnoreCase(normalizedBase))
                continue;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(.*?)(\\d+)([^\\d]*)$").matcher(source);
            if (matcher.matches()) {
                return matcher.group(1) + "{n}" + matcher.group(3);
            }
        }
        return null;
    }

    private String renderHeaderTemplate(String template, int suffix) {
        return template == null ? "" : template.replace("{n}", String.valueOf(suffix));
    }

    private boolean inferKeyFirst(
            List<com.example.datafill.dto.ReferenceFieldMapping> mappings,
            String keyBase,
            String valueBase) {
        if (mappings == null || mappings.isEmpty()) {
            return true;
        }
        for (int i = 0; i < mappings.size() - 1; i++) {
            String currentBase = normalizeReferenceBase(resolveReferenceHeaderSource(mappings.get(i)));
            String nextBase = normalizeReferenceBase(resolveReferenceHeaderSource(mappings.get(i + 1)));
            if (keyBase.equalsIgnoreCase(currentBase) && valueBase.equalsIgnoreCase(nextBase)) {
                return true;
            }
            if (valueBase.equalsIgnoreCase(currentBase) && keyBase.equalsIgnoreCase(nextBase)) {
                return false;
            }
        }
        return true;
    }

    private List<com.example.datafill.dto.ExcelParseResult.DetectedPair> detectReferenceJsonPairs(
            String jsonColumnName,
            List<com.example.datafill.dto.ReferenceFieldMapping> mappings) {
        List<com.example.datafill.dto.ExcelParseResult.DetectedPair> pairs = new ArrayList<>();
        Map<String, String> kwPairs = new LinkedHashMap<>();
        kwPairs.putAll(configService.getKwPairs());

        Map<String, List<com.example.datafill.dto.ReferenceFieldMapping>> groupedBySuffix = new LinkedHashMap<>();
        java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("(.*?)(\\d*)$");
        for (com.example.datafill.dto.ReferenceFieldMapping mapping : mappings) {
            // 参考模板里可能用 "..." 省略中间列，但“对应数据库字段”依然是完整列名
            // 所以优先用 excelHeader；如果是省略号/空，再退回到 columnName 做识别。
            String source = mapping.getExcelHeader();
            if (source == null || source.trim().isEmpty() || "...".equals(source.trim())) {
                source = mapping.getColumnName();
            }
            if (source == null || source.trim().isEmpty() || "...".equals(source.trim())) {
                continue;
            }
            java.util.regex.Matcher matcher = numPattern.matcher(source.trim());
            if (matcher.matches()) {
                String suffix = matcher.group(2);
                groupedBySuffix.computeIfAbsent(suffix, key -> new ArrayList<>()).add(mapping);
            }
        }

        for (Map.Entry<String, List<com.example.datafill.dto.ReferenceFieldMapping>> entry : groupedBySuffix
                .entrySet()) {
            String suffix = entry.getKey();
            List<com.example.datafill.dto.ReferenceFieldMapping> sameSuffixMappings = entry.getValue();
            if (sameSuffixMappings.size() < 2) {
                continue;
            }
            for (int i = 0; i < sameSuffixMappings.size(); i++) {
                com.example.datafill.dto.ReferenceFieldMapping left = sameSuffixMappings.get(i);
                String leftSource = left.getExcelHeader();
                if (leftSource == null || (leftSource != null && leftSource.trim().isEmpty())
                        || "...".equals(leftSource.trim())) {
                    leftSource = left.getColumnName();
                }
                leftSource = leftSource == null ? "" : leftSource.trim();
                if (suffix == null)
                    suffix = "";
                String rawLeftBase = leftSource.length() >= suffix.length()
                        ? leftSource.substring(0, leftSource.length() - suffix.length())
                        : leftSource;
                final String leftBase = rawLeftBase.toLowerCase().trim().replaceAll("[_\\-：:\\s]+$", "");
                for (int j = 0; j < sameSuffixMappings.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    com.example.datafill.dto.ReferenceFieldMapping right = sameSuffixMappings.get(j);
                    String rightSource = right.getExcelHeader();
                    if (rightSource == null || (rightSource != null && rightSource.trim().isEmpty())
                            || "...".equals(rightSource.trim())) {
                        rightSource = right.getColumnName();
                    }
                    rightSource = rightSource == null ? "" : rightSource.trim();
                    String rawRightBase = rightSource.length() >= suffix.length()
                            ? rightSource.substring(0, rightSource.length() - suffix.length())
                            : rightSource;
                    final String rightBase = rawRightBase.toLowerCase().trim().replaceAll("[_\\-：:\\s]+$", "");
                    for (Map.Entry<String, String> pairRule : kwPairs.entrySet()) {
                        String keyBase = pairRule.getKey().toLowerCase().trim();
                        String valueBase = pairRule.getValue().toLowerCase().trim();

                        boolean isMatch = false;
                        if (leftBase.equals(keyBase) && rightBase.equals(valueBase)) {
                            isMatch = true;
                        } else if (leftBase.endsWith(keyBase) && rightBase.endsWith(valueBase)) {
                            String preA = leftBase.substring(0, leftBase.length() - keyBase.length()).trim();
                            String preB = rightBase.substring(0, rightBase.length() - valueBase.length()).trim();
                            if (preA.equals(preB)) {
                                isMatch = true;
                            }
                        }

                        if (!isMatch) {
                            continue;
                        }
                        com.example.datafill.dto.ExcelParseResult.DetectedPair existing = pairs.stream()
                                .filter(p -> leftBase.equals(p.getKeyBase())
                                        && rightBase.equals(p.getValueBase())
                                        && jsonColumnName.equalsIgnoreCase(p.getSuggestedColumnName()))
                                .findFirst()
                                .orElse(null);
                        if (existing == null) {
                            existing = new com.example.datafill.dto.ExcelParseResult.DetectedPair();
                            existing.setKeyBase(leftBase);
                            existing.setValueBase(rightBase);
                            existing.setKeyIndices(new ArrayList<>());
                            existing.setValueIndices(new ArrayList<>());
                            existing.setSuffixes(new ArrayList<>());
                            existing.setDisplayName(leftBase + "/" + rightBase);
                            existing.setSuggestedColumnName(jsonColumnName);
                            pairs.add(existing);
                        }
                        if (!existing.getSuffixes().contains(suffix)) {
                            existing.getSuffixes().add(suffix);
                        }
                        if (!existing.getKeyIndices().contains(left.getColumnIndex())) {
                            existing.getKeyIndices().add(left.getColumnIndex());
                        }
                        if (!existing.getValueIndices().contains(right.getColumnIndex())) {
                            existing.getValueIndices().add(right.getColumnIndex());
                        }
                    }
                }
            }
        }

        // 如果模板用 "..." 省略了中间列，POI 读取后可能只保留部分后缀（例如 1,2,3,51）。
        // 这里做一个区间补全：当我们已经识别出 key/value 后缀的首尾，并且在中间存在占位符，
        // 则尝试补全 min..max 的 suffix，同时补全 keyIndices/valueIndices。
        boolean hasEllipsisPlaceholder = mappings != null && mappings.stream()
                .anyMatch(m -> {
                    String h = m.getExcelHeader();
                    return h == null || h.trim().isEmpty() || "...".equals(h.trim());
                });

        if (hasEllipsisPlaceholder) {
            for (com.example.datafill.dto.ExcelParseResult.DetectedPair pair : pairs) {
                if (pair.getSuffixes() == null || pair.getSuffixes().isEmpty())
                    continue;
                if (pair.getKeyIndices() == null || pair.getKeyIndices().size() < 2)
                    continue;
                if (pair.getValueIndices() == null || pair.getValueIndices().size() < 2)
                    continue;

                List<Integer> suffixNums = new ArrayList<>();
                for (String s : pair.getSuffixes()) {
                    if (s == null || s.trim().isEmpty())
                        continue;
                    try {
                        suffixNums.add(Integer.parseInt(s));
                    } catch (Exception ignored) {
                    }
                }
                if (suffixNums.isEmpty())
                    continue;

                int minSuffix = suffixNums.stream().min(Integer::compareTo).orElse(0);
                int maxSuffix = suffixNums.stream().max(Integer::compareTo).orElse(0);
                int expectedCount = (maxSuffix - minSuffix + 1);
                if (expectedCount <= 1)
                    continue;
                if (pair.getSuffixes().size() >= expectedCount)
                    continue; // 已经完整

                // 推断 key 的步长：通常 key_i 与 key_{i+1} 间隔固定（例如相邻 key/value 成对时 step=2）
                List<Integer> keyIdxSorted = pair.getKeyIndices().stream()
                        .filter(java.util.Objects::nonNull).distinct().sorted()
                        .collect(java.util.stream.Collectors.toList());
                List<Integer> valIdxSorted = pair.getValueIndices().stream()
                        .filter(java.util.Objects::nonNull).distinct().sorted()
                        .collect(java.util.stream.Collectors.toList());
                if (keyIdxSorted.size() < 2 || valIdxSorted.size() < 2)
                    continue;

                int keyStep = keyIdxSorted.get(1) - keyIdxSorted.get(0);
                if (keyStep <= 0)
                    continue;

                int keyStartIdx = keyIdxSorted.get(0);
                int valStartIdx = valIdxSorted.get(0);

                // 用已有 keyIndices 进行校验：推算 minSuffix 的 key/value 下标应当落在已知集合里
                if (!pair.getKeyIndices().contains(keyStartIdx) || !pair.getValueIndices().contains(valStartIdx)) {
                    continue;
                }

                List<String> expandedSuffixes = new ArrayList<>();
                List<Integer> expandedKeyIndices = new ArrayList<>();
                List<Integer> expandedValueIndices = new ArrayList<>();
                int maxIndexLimit = 0;
                if (mappings != null && !mappings.isEmpty()) {
                    maxIndexLimit = mappings.stream()
                            .map(com.example.datafill.dto.ReferenceFieldMapping::getColumnIndex)
                            .filter(java.util.Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .max()
                            .orElse(0) + 1;
                }

                for (int s = minSuffix; s <= maxSuffix; s++) {
                    expandedSuffixes.add(String.valueOf(s));
                    int kIdx = keyStartIdx + (s - minSuffix) * keyStep;
                    int vIdx = valStartIdx + (s - minSuffix) * keyStep;
                    // 防越界：如果模板实际列数不足，则停止补全
                    if (kIdx < 0 || kIdx >= maxIndexLimit || vIdx < 0 || vIdx >= maxIndexLimit) {
                        break;
                    }
                    expandedKeyIndices.add(kIdx);
                    expandedValueIndices.add(vIdx);
                }

                if (expandedSuffixes.size() == expectedCount) {
                    pair.setSuffixes(expandedSuffixes);
                    pair.setKeyIndices(expandedKeyIndices);
                    pair.setValueIndices(expandedValueIndices);
                }
            }
        }

        for (com.example.datafill.dto.ExcelParseResult.DetectedPair pair : pairs) {
            String range = formatSuffixRange(pair.getSuffixes());
            String suffixDesc = range.isEmpty() ? "无编号" : range;
            pair.setDisplayName(pair.getKeyBase() + "/" + pair.getValueBase() + " (" + suffixDesc + ")");
        }
        return pairs;
    }

    public com.example.datafill.dto.ExcelParseResult parseExcelHeaders(MultipartFile file, String mode,
            boolean smartType, boolean kvPairEnabled) throws IOException {
        if (file.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
            throw new RuntimeException("上传文件过大，当前限制为 " + maxFileSizeMb + "MB");
        }
        com.example.datafill.dto.ExcelParseResult result = new com.example.datafill.dto.ExcelParseResult();
        List<FieldDef> fields = new ArrayList<>();
        List<com.example.datafill.dto.ExcelParseResult.DetectedPair> potentialPairs = new ArrayList<>();
        result.setFields(fields);
        result.setPotentialPairs(potentialPairs);

        if ("json".equalsIgnoreCase(mode)) {
            return result;
        }

        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(131072)
                .open(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null)
                return result;

            java.util.Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext())
                return result;

            Row headerRow = rowIterator.next();
            if (headerRow == null)
                return result;

            int lastColumn = headerRow.getLastCellNum();
            if (lastColumn <= 0)
                return result;

            int scanLimit = 100;

            org.apache.poi.ss.usermodel.DataFormatter dataFormatter = new org.apache.poi.ss.usermodel.DataFormatter();
            List<String> originalHeaders = new ArrayList<>();
            List<ColumnStat> stats = new ArrayList<>();
            for (int c = 0; c < lastColumn; c++) {
                Cell cell = headerRow.getCell(c);
                String name = (cell == null) ? "" : dataFormatter.formatCellValue(cell).trim();
                originalHeaders.add(name);
                if (name.isEmpty())
                    name = "未命名字段" + (c + 1);
                stats.add(new ColumnStat(c, name));
            }
            result.setOriginalHeaders(originalHeaders);

            int rowCount = 0;
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                rowCount++;
                if (row == null)
                    continue;
                for (int c = 0; c < lastColumn; c++) {
                    Cell cell = row.getCell(c);
                    ColumnStat s = stats.get(c);
                    if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK)
                        continue;
                    s.nonBlankCount++;
                    String val = dataFormatter.formatCellValue(cell).trim();
                    s.uniqueValues.add(val);
                    if (s.rawValues.size() < 100)
                        s.rawValues.add(val);
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        if (!org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                            s.couldBeDate = false;
                            double d = cell.getNumericCellValue();
                            if (d != Math.floor(d))
                                s.hasDecimal = true;
                        }
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        s.couldBeDate = false;
                        String text = cell.getStringCellValue().trim().replace(",", "");
                        try {
                            double d = Double.parseDouble(text);
                            if (d != Math.floor(d))
                                s.hasDecimal = true;
                        } catch (NumberFormatException e) {
                            s.couldBeNumber = false;
                        }
                    } else {
                        s.couldBeNumber = false;
                        s.couldBeDate = false;
                    }
                }
                if (rowCount >= scanLimit)
                    break;
            }

            result.setTotalColumns(stats.size());
            List<ColumnStat> targetColumns = stats;
            if (targetColumns.size() > 1000) {
                targetColumns = targetColumns.subList(0, 1000);
                result.setTruncated(true);
            }

            Set<String> usedColNames = new HashSet<>(java.util.Arrays.asList("id", "create_time", "delete_flag",
                    "extra_data", "w_insert_dt", "w_update_dt", "load_user"));

            Map<String, String> kwPairs = new HashMap<>();
            Map<String, List<Integer>> groupedBySuffix = new HashMap<>();
            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("(.*?)(\\d*)$");

            if (kvPairEnabled) {
                kwPairs.putAll(configService.getKwPairs());

                // 先扫描所有列名，填充 groupedBySuffix
                for (int i = 0; i < stats.size(); i++) {
                    String hHeader = stats.get(i).headerName.trim();
                    java.util.regex.Matcher sm = numPattern.matcher(hHeader);
                    if (sm.matches()) {
                        String suffix = sm.group(2);
                        groupedBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(i);
                    }
                }

                // 按后缀分组处理
                for (Map.Entry<String, List<Integer>> entry : groupedBySuffix.entrySet()) {
                    String suffix = entry.getKey();
                    List<Integer> indices = entry.getValue();
                    if (indices.size() < 2)
                        continue; // 至少需要两个字段才能组成配对

                    // 在同一组（相同数字编号）内寻找满足“基因库”定义的配对
                    for (int i = 0; i < indices.size(); i++) {
                        int idxA = indices.get(i);
                        String headerA = stats.get(idxA).headerName.trim();
                        // 移除数字后缀得到 BaseName
                        String baseA = headerA.substring(0, headerA.length() - suffix.length()).toLowerCase().trim()
                                .replaceAll("[_\\-：:\\s]+$", "");

                        for (int j = 0; j < indices.size(); j++) {
                            if (i == j)
                                continue;
                            int idxB = indices.get(j);
                            String headerB = stats.get(idxB).headerName.trim();
                            String baseB = headerB.substring(0, headerB.length() - suffix.length()).toLowerCase().trim()
                                    .replaceAll("[_\\-：:\\s]+$", "");

                            // 检查 (baseA, baseB) 是否命中基因库中的一对
                            for (Map.Entry<String, String> pair : kwPairs.entrySet()) {
                                String k = pair.getKey().toLowerCase().trim();
                                String v = pair.getValue().toLowerCase().trim();

                                boolean isMatch = false;
                                if (baseA.equals(k) && baseB.equals(v)) {
                                    isMatch = true;
                                } else if (baseA.endsWith(k) && baseB.endsWith(v)) {
                                    String prefixA = baseA.substring(0, baseA.length() - k.length()).trim();
                                    String prefixB = baseB.substring(0, baseB.length() - v.length()).trim();
                                    if (prefixA.equals(prefixB)) {
                                        isMatch = true;
                                    }
                                }

                                if (isMatch) {
                                    // 命中配对！记录结果并打标
                                    final String kFull = headerA;
                                    final String vFull = headerB;
                                    final String suff = suffix;

                                    com.example.datafill.dto.ExcelParseResult.DetectedPair existing = potentialPairs
                                            .stream()
                                            .filter(p -> p.getKeyBase().equals(baseA) && p.getValueBase().equals(baseB))
                                            .findFirst().orElse(null);

                                    if (existing == null) {
                                        existing = new com.example.datafill.dto.ExcelParseResult.DetectedPair();
                                        existing.setKeyBase(baseA);
                                        existing.setValueBase(baseB);
                                        existing.setKeyIndices(new ArrayList<>());
                                        existing.setValueIndices(new ArrayList<>());
                                        existing.setSuffixes(new ArrayList<>());
                                        existing.setDisplayName(baseA + "/" + baseB);
                                        existing.setSuggestedColumnName(deriveJsonColumnName(baseA, baseB));
                                        potentialPairs.add(existing);
                                    }

                                    if (!existing.getSuffixes().contains(suff))
                                        existing.getSuffixes().add(suff);
                                    if (!existing.getKeyIndices().contains(idxA))
                                        existing.getKeyIndices().add(idxA);
                                    if (!existing.getValueIndices().contains(idxB))
                                        existing.getValueIndices().add(idxB);
                                }
                            }
                        }
                    }
                }

                // 标记已识别为配对的列索引，避免下文重复添加成普通字段
                java.util.Set<Integer> pairedIndices = new java.util.HashSet<>();
                for (com.example.datafill.dto.ExcelParseResult.DetectedPair p : potentialPairs) {
                    pairedIndices.addAll(p.getKeyIndices());
                    pairedIndices.addAll(p.getValueIndices());
                }

                // 准备进入下文普通字段处理逻辑
                for (int i = 0; i < targetColumns.size(); i++) {
                    if (pairedIndices.contains(i))
                        continue;

                    ColumnStat s = targetColumns.get(i);
                    FieldDef def = createFieldDef(s, i, usedColNames, smartType);
                    fields.add(def);
                }
            } else {
                // 原有的非键值对模式逻辑：全部展开为普通字段
                for (int i = 0; i < targetColumns.size(); i++) {
                    ColumnStat s = targetColumns.get(i);
                    FieldDef def = createFieldDef(s, i, usedColNames, smartType);
                    fields.add(def);
                }
            }
        }

        // 5. 最终名称完善 (增加范围提示)
        for (com.example.datafill.dto.ExcelParseResult.DetectedPair p : potentialPairs) {
            String range = formatSuffixRange(p.getSuffixes());
            String suffixDesc = range.isEmpty() ? "无编号" : range;
            p.setDisplayName(p.getKeyBase() + "/" + p.getValueBase() + " (" + suffixDesc + ")");
        }

        return result;
    }

    public com.example.datafill.dto.ExcelParseResult inspectExistingTable(String schemaName, String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new RuntimeException("物理表名不能为空");
        }
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("物理表名只能包含字母、数字和下划线");
        }
        if (schemaName == null || schemaName.trim().isEmpty()) {
            schemaName = "public";
        }

        String existenceSql = "SELECT COUNT(1) FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_name = ?";
        Integer tableCount = jdbcTemplate.queryForObject(existenceSql, Integer.class, schemaName, tableName);
        if (tableCount == null || tableCount <= 0) {
            throw new RuntimeException("指定的物理表不存在: " + schemaName + "." + tableName);
        }

        String sql = "SELECT c.ordinal_position, c.column_name, c.data_type, c.udt_name, " +
                "c.character_maximum_length, c.numeric_precision, c.numeric_scale, c.is_nullable, " +
                "COALESCE(pgd.description, '') AS column_comment " +
                "FROM information_schema.columns c " +
                "INNER JOIN pg_catalog.pg_namespace ns ON ns.nspname = c.table_schema " +
                "INNER JOIN pg_catalog.pg_class cls ON cls.relname = c.table_name AND cls.relnamespace = ns.oid " +
                "LEFT JOIN pg_catalog.pg_description pgd ON pgd.objoid = cls.oid AND pgd.objsubid = c.ordinal_position " +
                "WHERE c.table_schema = ? AND c.table_name = ? " +
                "ORDER BY (CASE WHEN c.column_name = 'id' THEN 0 ELSE 1 END), c.ordinal_position";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, schemaName, tableName);
        if (rows.isEmpty()) {
            throw new RuntimeException("未读取到表字段: " + schemaName + "." + tableName);
        }

        List<FieldDef> fields = new ArrayList<>();
        List<String> originalHeaders = new ArrayList<>();
        int businessIndex = 0;
        for (Map<String, Object> row : rows) {
            String columnName = asText(row.get("column_name"));
            if (columnName == null) {
                continue;
            }
            originalHeaders.add(columnName);
            
            boolean isSystem = EXISTING_TABLE_SYSTEM_COLUMNS.contains(columnName.toLowerCase());

            FieldDef field = new FieldDef();
            field.setColumnName(columnName);
            field.setOriginalColumnName(columnName);
            field.setSystemLocked(isSystem);
            
            String comment = asText(row.get("column_comment"));
            field.setName((comment != null && !comment.trim().isEmpty()) ? comment : columnName);

            String dbType = buildPgType(row);
            field.setDbType(dbType);
            applyFieldTypeByDbType(field, dbType);

            if (isSystem) {
                field.setRequired(false);
                field.setFilterable(false);
                field.setHideInForm(true);
                field.setHideInList(true);
            } else {
                String nullable = asText(row.get("is_nullable"));
                field.setRequired("NO".equalsIgnoreCase(nullable));
                field.setFilterable(businessIndex < 3);
                businessIndex++;
            }
            
            fields.add(field);
        }

        if (fields.isEmpty()) {
            throw new RuntimeException("该表仅包含系统字段，未识别到可配置的业务字段");
        }

        com.example.datafill.dto.ExcelParseResult result = new com.example.datafill.dto.ExcelParseResult();
        result.setFields(fields);
        result.setPotentialPairs(new ArrayList<>());
        result.setOriginalHeaders(originalHeaders);
        result.setTotalColumns(rows.size());
        result.setTruncated(false);

        // 审计列检测逻辑
        java.util.Set<String> physicalColumns = new java.util.HashSet<>();
        for (String h : originalHeaders) {
            if (h != null) physicalColumns.add(h.toLowerCase());
        }

        List<String> missing = new ArrayList<>();
        if (!physicalColumns.contains("id")) {
            missing.add("id");
        } else {
            // 检查 id 类型，如果是 bigint 则标记为缺失/需修复 (int8 = bigint)
            for (Map<String, Object> r : rows) {
                if ("id".equalsIgnoreCase(asText(r.get("column_name")))) {
                    String udt = lower(asText(r.get("udt_name")));
                    if ("int8".equals(udt) || "bigint".equals(udt)) {
                        if (!missing.contains("id")) missing.add("id");
                        break;
                    }
                }
            }
        }

        String dInsert = detectRole(physicalColumns, INSERT_AUDIT_LEXICON);
        String dUpdate = detectRole(physicalColumns, UPDATE_AUDIT_LEXICON);
        String dDelete = detectRole(physicalColumns, DELETE_FLAG_LEXICON);

        if (dInsert == null) missing.add("w_insert_dt");
        if (dUpdate == null) missing.add("w_update_dt");
        if (dDelete == null) missing.add("delete_flag");

        result.setMissingColumns(missing);
        result.setDetectedInsertDt(dInsert);
        result.setDetectedUpdateDt(dUpdate);
        result.setDetectedDeleteFlag(dDelete);

        return result;
    }

    private FieldDef createFieldDef(ColumnStat s, int colIndex, Set<String> usedColNames, boolean smartType) {
        FieldDef def = new FieldDef();
        def.setName(s.headerName);
        String baseColName = generateDwColumnName(s.headerName, colIndex);
        String finalColName = baseColName;
        int suffixVal = 1;
        while (usedColNames.contains(finalColName)) {
            finalColName = baseColName + "_" + suffixVal++;
        }
        usedColNames.add(finalColName);
        def.setColumnName(finalColName);
        def.setType("input");
        def.setDbType("varchar(255)");
        if (smartType && s.nonBlankCount > 0) {
            if (s.couldBeDate) {
                def.setType("datetime");
                def.setDbType("timestamp");
            } else if (s.couldBeNumber) {
                def.setType("number");
                def.setDbType(s.hasDecimal ? "numeric" : "int4");
            } else if (s.rawValues.stream().anyMatch(val -> val.length() > 50)) {
                def.setType("textarea");
                def.setDbType("text");
            }
        }
        def.setRequired(false);
        def.setFilterable(colIndex < 3 && s.nonBlankCount > 0);
        return def;
    }

    private String buildPgType(Map<String, Object> row) {
        String dataType = lower(asText(row.get("data_type")));
        String udtName = lower(asText(row.get("udt_name")));
        Integer charLength = asInteger(row.get("character_maximum_length"));
        Integer precision = asInteger(row.get("numeric_precision"));
        Integer scale = asInteger(row.get("numeric_scale"));

        if ("character varying".equals(dataType) || "varchar".equals(dataType)) {
            return charLength != null && charLength > 0 ? "varchar(" + charLength + ")" : "varchar(255)";
        }
        if ("character".equals(dataType) || "bpchar".equals(udtName)) {
            return charLength != null && charLength > 0 ? "char(" + charLength + ")" : "char(1)";
        }
        if ("text".equals(dataType)) {
            return "text";
        }
        if ("integer".equals(dataType) || "int4".equals(dataType) || "int4".equals(udtName)) {
            return "int4";
        }
        if ("bigint".equals(dataType) || "int8".equals(dataType) || "int8".equals(udtName)) {
            return "int8";
        }
        if ("smallint".equals(dataType) || "int2".equals(dataType) || "int2".equals(udtName)) {
            return "int2";
        }
        if ("boolean".equals(dataType) || "bool".equals(dataType) || "bool".equals(udtName)) {
            return "bool";
        }
        if ("date".equals(dataType)) {
            return "date";
        }
        if ("timestamp without time zone".equals(dataType) || "timestamp with time zone".equals(dataType)
                || "timestamp".equals(dataType) || "timestamptz".equals(udtName)) {
            return "timestamp";
        }
        if ("jsonb".equals(dataType) || "jsonb".equals(udtName)) {
            return "jsonb";
        }
        if ("json".equals(dataType) || "json".equals(udtName)) {
            return "json";
        }
        if ("numeric".equals(dataType) || "decimal".equals(dataType) || "numeric".equals(udtName)) {
            if (precision != null && scale != null) {
                return "numeric(" + precision + ", " + scale + ")";
            }
            return "numeric";
        }
        if ("double precision".equals(dataType) || "float8".equals(udtName)) {
            return "float8";
        }
        if ("real".equals(dataType) || "float4".equals(udtName)) {
            return "float4";
        }
        return udtName != null ? udtName : (dataType != null ? dataType : "varchar(255)");
    }

    private void applyFieldTypeByDbType(FieldDef field, String dbType) {
        String typeStr = dbType == null ? "" : dbType.toUpperCase();
        if (typeStr.contains("TIMESTAMP") || "DATE".equals(typeStr) || typeStr.contains("TIME")) {
            field.setType("datetime");
        } else if (typeStr.contains("INT") || typeStr.contains("NUMERIC") || typeStr.contains("DECIMAL")
                || typeStr.contains("DOUBLE") || typeStr.contains("REAL")) {
            field.setType("number");
        } else if (typeStr.contains("TEXT") || typeStr.contains("JSON")) {
            field.setType("textarea");
        } else if (typeStr.contains("BOOL")) {
            field.setType("switch");
        } else {
            field.setType("input");
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private Integer asInteger(Object value) {
        if (value == null)
            return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeHeaderKey(String value) {
        if (value == null) {
            return "";
        }
        // 统一兼容：
        // Billing Source / billing_source / billingSource / BILLING-SOURCE ->
        // billingsource
        String normalized = value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[\\r\\n]+", " ")
                .trim()
                .toLowerCase();
        return normalized.replaceAll("[^a-z0-9]", "");
    }

    private boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    private String formatSuffixRange(List<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty())
            return "";
        if (suffixes.size() == 1 && suffixes.get(0).isEmpty())
            return "";

        List<Integer> nums = new ArrayList<>();
        boolean hasEmpty = false;
        for (String s : suffixes) {
            if (s.isEmpty()) {
                hasEmpty = true;
                continue;
            }
            try {
                nums.add(Integer.parseInt(s));
            } catch (Exception e) {
            }
        }
        if (nums.isEmpty())
            return hasEmpty ? "无编号" : "";

        java.util.Collections.sort(nums);
        int start = nums.get(0);
        int end = nums.get(nums.size() - 1);
        String range = (start == end) ? String.valueOf(start) : start + "-" + end;
        if (hasEmpty)
            range = "无编号, " + range;
        return range;
    }

    private String generateDwColumnName(String originalName, int colIndex) {

        if (originalName == null || originalName.trim().isEmpty()) {

            return "field_" + (colIndex + 1);

        }

        // 读取列名生成规范配置

        Map<String, Object> namingConf = configService.getNamingConvention();

        String prefix = (String) namingConf.getOrDefault("column_prefix", "field_");

        int threshold = namingConf.containsKey("initials_threshold")
                ? ((Number) namingConf.get("initials_threshold")).intValue()
                : 4;

        int maxLen = namingConf.containsKey("max_length") ? ((Number) namingConf.get("max_length")).intValue() : 50;

        String regex = (String) namingConf.getOrDefault("replace_regex", "[\\s\\[\\]\\(\\)（）【】]");

        String numericPrefix = (String) namingConf.getOrDefault("numeric_prefix", "col_");

        String pinyinSeparator = (String) namingConf.getOrDefault("pinyin_separator", "_");

        int bracketEngMinLen = namingConf.containsKey("bracket_eng_min_len")
                ? ((Number) namingConf.get("bracket_eng_min_len")).intValue()
                : 2;

        String dictMatchMode = (String) namingConf.getOrDefault("dict_match_mode", "contains");

        // 1. 尝试提取括号/方括号中的纯英文字段(例如 "[Create Time]")

        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("[\\[\\(（【]([a-zA-Z\\s_]+)[\\]\\)）】]")
                .matcher(originalName);

        if (m1.find()) {

            String eng = m1.group(1).trim();

            if (eng.length() >= bracketEngMinLen) {

                return toSnakeCase(eng);

            }

        }

        // 2. 如果字符串里本来就包含一段连续英连续两个字母以上)，优先使用这段英文提 // 例如 "创建时间 Create Time" ->
        // "create_time"

        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("([a-zA-Z][a-zA-Z\\s_]{1,}[a-zA-Z])")
                .matcher(originalName);

        if (m2.find()) {

            String eng = m2.group(1).trim();

            if (eng.length() >= bracketEngMinLen + 1) {

                return toSnakeCase(eng);

            }

        }

        // 3. 常见中文到英文的映射字典 (尽量缩写符合数仓规范)

        Map<String, String> dict = configService.getDwDict();

        if (dict.isEmpty()) {

            dict = new java.util.LinkedHashMap<>();

            dict.put("创建时间", "ctime");

            dict.put("添加时间", "ctime");

            dict.put("更新时间", "utime");

            dict.put("修改时间", "mtime");

            dict.put("创建", "creator");

            dict.put("修改", "modifier");

            dict.put("操作", "operator");

            dict.put("状", "status");

            dict.put("备注", "remark");

            dict.put("描述", "desc");

            dict.put("详情", "detail");

            dict.put("部门", "dept");

            dict.put("公司", "company");

            dict.put("企业", "company");

            dict.put("机构", "org");

            dict.put("组织", "org");

            dict.put("员工", "emp");

            dict.put("人员", "person");

            dict.put("姓名", "name");

            dict.put("名称", "name");

            dict.put("标题", "title");

            dict.put("电话", "phone");

            dict.put("手机", "mobile");

            dict.put("联系方式", "contact");

            dict.put("邮箱", "email");

            dict.put("金额", "amount");

            dict.put("价钱", "price");

            dict.put("价格", "price");

            dict.put("单价", "price");

            dict.put("花费", "cost");

            dict.put("成本", "cost");

            dict.put("数量", "qty");

            dict.put("数目", "count");

            dict.put("次数", "times");

            dict.put("日期", "date");

            dict.put("时间", "time");

            dict.put("总计", "total");

            dict.put("合计", "total");

            dict.put("总额", "total_amt");

            dict.put("订单", "order_no");

            dict.put("单号", "order_no");

            dict.put("序列", "serial_no");

            dict.put("编号", "no");

            dict.put("类型", "type");

            dict.put("类别", "category");

            dict.put("分类", "category");

            dict.put("级别", "level");

            dict.put("等级", "level");

            dict.put("地址", "address");

            dict.put("位置", "location");

            dict.put("密码", "password");

            dict.put("账号", "account");

            dict.put("用户", "user");

            dict.put("角色", "role");

            dict.put("权限", "permission");

            dict.put("省份", "province");

            dict.put("城市", "city");

            dict.put("区县", "district");

            dict.put("年份", "year");

            dict.put("月份", "month");

            dict.put("年龄", "age");

            dict.put("性别", "gender");

            dict.put("身份", "id_card");

            dict.put("比例", "ratio");

            dict.put("百分", "percent");

            dict.put("是否", "is_flag");

        }

        // 字典映射（已清理特殊字符后）

        String cleanName = originalName.replaceAll(regex, "");

        for (Map.Entry<String, String> entry : dict.entrySet()) {

            boolean matched = "exact".equalsIgnoreCase(dictMatchMode)
                    ? cleanName.equals(entry.getKey())
                    : cleanName.contains(entry.getKey());

            if (matched) {

                return entry.getValue();

            }

        }

        // 4. Fallback to Pinyin

        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();

        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);

        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

        StringBuilder sb = new StringBuilder();

        int chineseCharCount = 0;

        for (char c : originalName.toCharArray()) {

            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {

                chineseCharCount++;

            }

        }

        // 如果汉字大于等于阈值个，使用拼音首字母缩写

        boolean useInitials = chineseCharCount >= threshold;

        for (char c : originalName.toCharArray()) {

            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {

                try {

                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);

                    if (pinyinArray != null && pinyinArray.length > 0) {

                        if (useInitials) {

                            sb.append(pinyinArray[0].substring(0, 1));

                        } else {

                            sb.append(pinyinArray[0]).append(pinyinSeparator);

                        }

                    }

                } catch (Exception e) {

                    // ignore

                }

            } else if (Character.isLetterOrDigit(c)) {

                sb.append(Character.toLowerCase(c));

            } else if (c == '_' || c == ' ') {

                if (!useInitials && sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {

                    sb.append('_');

                }

            }

        }

        String result = sb.toString().replaceAll("_+", "_");

        if (result.endsWith("_")) {

            result = result.substring(0, result.length() - 1);

        }

        if (result.startsWith("_")) {

            result = result.substring(1);

        }

        if (result.isEmpty()) {

            return prefix + (colIndex + 1);

        }

        if (Character.isDigit(result.charAt(0))) {

            result = numericPrefix + result;

        }

        if (result.length() > maxLen) {

            result = result.substring(0, maxLen);

            if (result.endsWith("_")) {

                result = result.substring(0, result.length() - 1);

            }

        }

        return result;

    }

    private String toSnakeCase(String str) {

        if (str == null || str.isEmpty())
            return "";

        String s = str.trim().replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();

        s = s.replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_");

        if (s.isEmpty())
            return "";

        if (Character.isDigit(s.charAt(0))) {

            s = "col_" + s;

        }

        return s;

    }

    private String deriveJsonColumnName(String k, String v) {
        String sk = k.toLowerCase().replace("/territory", "").replace("#", "").replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_");
        String sv = v.toLowerCase().replace("/territory", "").replace("#", "").replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_");

        int minLen = Math.min(sk.length(), sv.length());
        int prefixLen = 0;
        for (int i = 0; i < minLen; i++) {
            if (sk.charAt(i) == sv.charAt(i))
                prefixLen++;
            else
                break;
        }
        String prefix = sk.substring(0, prefixLen);
        if (prefix.contains("_")) {
            prefix = prefix.substring(0, prefix.lastIndexOf("_") + 1);
        } else if (prefixLen < sk.length() && prefixLen < sv.length()) {
            prefix = "";
        }

        String result = sk;
        String vPart = sv.substring(prefix.length());
        if (!vPart.isEmpty()) {
            result += "_" + vPart;
        }
        return result.replaceAll("_+", "_").replaceAll("^_+|_+$", "") + "_json";
    }
}