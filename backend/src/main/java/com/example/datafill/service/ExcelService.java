package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;
import com.example.datafill.entity.DataFillForm;
import com.example.datafill.entity.UserFillLog;
import com.example.datafill.mapper.DataFillFormMapper;
import com.example.datafill.mapper.UserFillLogMapper;
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
    // 使用具有 LRU (Least Recently Used) 淘汰机制的线程安全 Map，防止 OOM。最多保留 50 个最近的报错报告。
    private final java.util.Map<String, String> errorReportCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                return size() > 50;
            }
        }
    );

    public String getErrorReport(String reportId) {
        return errorReportCache.get(reportId);
    }
    private static final String EXCEL_ROW_META_KEY = "__excel_row_num__";
    private static final java.util.regex.Pattern NUMBER_PATTERN = java.util.regex.Pattern
            .compile("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$");

    private static final java.util.Set<String> EXISTING_TABLE_SYSTEM_COLUMNS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "id", "load_user", "extra_data",
                    "w_insert_dt", "w_update_dt", "delete_flag",
                    "create_time", "update_time", "created_at", "updated_at",
                    "is_delete", "deleted", "del_flag", "insert_time"));

    private static final String[] INSERT_AUDIT_LEXICON = { "w_insert_dt", "create_time", "created_at",
            "insert_time" };
    private static final String[] UPDATE_AUDIT_LEXICON = { "w_update_dt", "update_time", "updated_at" };
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
    private final UserFillLogMapper userFillLogMapper;

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
        boolean hasCommas = false;
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

    private void processHeaderAndExpand(
            String headerOrCol,
            FieldDef field,
            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> kvPairsToExpand,
            List<String> templateHeaders,
            Map<String, String> displayNameByHeader) {

        String col = field != null ? field.getColumnName() : headerOrCol;
        if (col == null || col.trim().isEmpty()) return;

        boolean expanded = false;
        if (kvPairsToExpand != null && !kvPairsToExpand.isEmpty()) {
            // 注意：检查 excelHeader 还是 columnName？
            // 如果是 JSON 字段，columnName 一定包含 _json。
            // 只要物理列名匹配 KV 配置中的合并列名，就触发展开。
            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> matched = kvPairsToExpand.stream()
                    .filter(p -> col.equalsIgnoreCase(p.getSuggestedColumnName()))
                    .collect(java.util.stream.Collectors.toList());

            if (!matched.isEmpty()) {
                log.info("触发 KV 展开: 原始识别列={}, 合并列名={}", headerOrCol, col);
                for (com.example.datafill.dto.ExcelParseResult.DetectedPair p : matched) {
                    for (String suffix : p.getSuffixes()) {
                        String kb = p.getKeyBase() + suffix;
                        String vb = p.getValueBase() + suffix;
                        if (!templateHeaders.contains(kb)) {
                            templateHeaders.add(kb);
                            displayNameByHeader.put(kb, kb);
                        }
                        if (!templateHeaders.contains(vb)) {
                            templateHeaders.add(vb);
                            displayNameByHeader.put(vb, vb);
                        }
                    }
                }
                expanded = true;
            }
        }

        if (!expanded) {
            templateHeaders.add(headerOrCol);
            String displayName = (field != null && field.getName() != null && !field.getName().trim().isEmpty())
                    ? field.getName()
                    : headerOrCol;
            displayNameByHeader.put(headerOrCol, displayName);
        }
    }

    public void exportTemplate(String formId, OutputStream outputStream) throws IOException {
        com.example.datafill.entity.DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        // [Debug Log] 导出模板日志
        log.info("开始导出表单模板, formId={}, name={}", formId, form.getName());

        List<FieldDef> fields;
        try {
            String formsJson = form.getForms();
            fields = objectMapper.readValue(formsJson, new TypeReference<List<FieldDef>>() {
            });
            log.info("表单字段解析成功, count={}", fields.size());
        } catch (JsonProcessingException e) {
            log.error("表单解析错误, formId={}", formId, e);
            throw new RuntimeException("表单解析错误", e);
        }

        // 核心变更：下载模板时，排除掉被管理员标记为“在表单中隐藏”的业务字段
        fields.removeIf(f -> Boolean.TRUE.equals(f.getHideInForm()));

        List<String> templateHeaders = new ArrayList<>();
        Map<String, String> displayNameByHeader = new LinkedHashMap<>();

        // 加载 KV 配置，用于在导出时根据合并列名还原原始业务列名
        List<com.example.datafill.dto.ExcelParseResult.DetectedPair> savedPairs = new ArrayList<>();
        if (form.getKvConfig() != null && !form.getKvConfig().trim().isEmpty()) {
            try {
                savedPairs = objectMapper.readValue(form.getKvConfig(),
                        new TypeReference<List<com.example.datafill.dto.ExcelParseResult.DetectedPair>>() {
                        });
                log.info("加载 kvConfig 成功, pairCount={}", savedPairs.size());
            } catch (Exception e) {
                log.warn("解析 kvConfig 失败, formId={}", formId, e);
            }
        }
        final List<com.example.datafill.dto.ExcelParseResult.DetectedPair> kvPairsToExpand = savedPairs;

        // 核心逻辑梳理：将业务字段映射到模板表头
        // 1. 如果有参考模板，按其记录的 excelHeader 和顺序进行映射
        if (form.getReferenceTemplateConfig() != null && !form.getReferenceTemplateConfig().trim().isEmpty()) {
            log.info("进入参考模板导出逻辑...");
            try {
                Map<String, Object> referenceConfig = objectMapper.readValue(
                        form.getReferenceTemplateConfig(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object mappingsObj = referenceConfig.get("headerMappings");
                if (mappingsObj instanceof List<?>) {
                    List<Map<String, Object>> mappingRowsRaw = new ArrayList<>();
                    for (Object item : (List<?>) mappingsObj) {
                        if (item instanceof Map<?, ?>) {
                            Map<String, Object> m = new HashMap<>();
                            ((Map<?, ?>) item).forEach((k, v) -> m.put(String.valueOf(k), v));
                            mappingRowsRaw.add(m);
                        }
                    }
                    mappingRowsRaw.sort((a, b) -> {
                        Integer ai = asInteger(a.get("columnIndex"));
                        Integer bi = asInteger(b.get("columnIndex"));
                        if (ai == null && bi == null) return 0;
                        if (ai == null) return 1;
                        if (bi == null) return -1;
                        return Integer.compare(ai, bi);
                    });

                    Set<String> processedFieldColumnNames = new HashSet<>();
                    for (Map<String, Object> row : mappingRowsRaw) {
                        String excelHeader = asText(row.get("excelHeader"));
                        String columnName = asText(row.get("columnName"));
                        if (excelHeader == null || columnName == null) continue;

                        FieldDef targetField = fields.stream()
                                .filter(f -> columnName.equalsIgnoreCase(f.getColumnName()))
                                .findFirst()
                                .orElse(null);

                        if (targetField != null) {
                            processHeaderAndExpand(excelHeader, targetField, kvPairsToExpand, templateHeaders, displayNameByHeader);
                            processedFieldColumnNames.add(columnName.toLowerCase());
                        }
                    }

                    // 补全：追加 mappings 中未包含的业务列
                    for (FieldDef field : fields) {
                        String col = field.getColumnName();
                        if (col != null && !processedFieldColumnNames.contains(col.toLowerCase())) {
                            processHeaderAndExpand(col, field, kvPairsToExpand, templateHeaders, displayNameByHeader);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析 referenceTemplateConfig 失败, formId={}", formId, e);
            }
        }

        // 2. 如果没用参考模板（或者解析失败），直接按字段列表生成
        if (templateHeaders.isEmpty()) {
            log.info("未识别到参考模板配置，进入常规导出逻辑...");
            for (FieldDef field : fields) {
                processHeaderAndExpand(field.getColumnName(), field, kvPairsToExpand, templateHeaders, displayNameByHeader);
            }
        }
        log.info("模板生成完成, 最终表头数量={}", templateHeaders.size());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet(form.getName() != null ? form.getName() : form.getTableName());

            // 仅保留一行：业务表头
            Row headerRow = sheet.createRow(0);
            int colIndex = 0;

            for (String header : templateHeaders) {
                Cell headerCell = headerRow.createCell(colIndex);
                
                // 优先使用显示名称（字段中文名）
                String display = displayNameByHeader.getOrDefault(header, header);

                // 如果该 header 本身就是从 referenceTemplateConfig 里读出来的原始 excelHeader，则优先使用原始头，保证兼容性
                // 如果是新追加的字段，则显示其中文名（display）
                headerCell.setCellValue(display);

                // 自适应列宽
                sheet.autoSizeColumn(colIndex);
                colIndex++;
            }

            workbook.write(outputStream);

        }

    }

    @Transactional(value = "dynamicTransactionManager", readOnly = true)
    public void exportData(String formId, Map<String, String> filters, String userEmail, boolean isAdmin, OutputStream outputStream) throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("[ExportData] 开始导出表单数据, formId={}, userEmail={}, filters={}", formId, userEmail, filters);

        com.example.datafill.entity.DataFillForm form = formMapper.selectById(formId);
        if (form == null) {
            log.error("[ExportData] 表单不存在, formId={}", formId);
            throw new RuntimeException("表单不存在");
        }
        
        List<FieldDef> fields;
        try {
            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
        } catch (JsonProcessingException e) {
            log.error("[ExportData] 表单解析错误, formId={}", formId, e);
            throw new RuntimeException("表单解析错误", e);
        }
        
        // 构建所需查询的物理列（仅选择表单中定义的列 and 必要的系统审计列，减少宽表多余列的网络传输延迟）
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        java.util.Map<String, String> physicalColumns = dataDmlService.loadPhysicalColumns(schema, form.getTableName());
        List<String> selectColumns = new ArrayList<>();
        if (physicalColumns.containsKey("id")) selectColumns.add("id");
        if (physicalColumns.containsKey("load_user")) selectColumns.add("load_user");
        if (physicalColumns.containsKey("w_insert_dt")) selectColumns.add("w_insert_dt");
        if (physicalColumns.containsKey("w_update_dt")) selectColumns.add("w_update_dt");
        for (FieldDef f : fields) {
            String col = f.getColumnName();
            if (col != null) {
                String lowerCol = col.toLowerCase();
                if (physicalColumns.containsKey(lowerCol) && !selectColumns.contains(lowerCol)) {
                    selectColumns.add(lowerCol);
                }
            }
        }
        log.info("[ExportData] 表单物理列解析完成, fieldsCount={}, selectColumnsCount={}", fields.size(), selectColumns.size());

        // 构建底层执行的 SQL 语句和参数集合，以进行游标流式分批读取（FetchSize），避免将全部记录一次性载入 JVM 内存导致的内存溢出（OOM）
        String fullTableName = schema + "." + form.getTableName();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (physicalColumns.containsKey("delete_flag")) {
            where.append(" AND \"delete_flag\" = FALSE ");
        }
        if (!isAdmin && userEmail != null && physicalColumns.containsKey("load_user")) {
            where.append(" AND (\"load_user\" = ? OR \"load_user\" IS NULL) ");
            args.add(userEmail);
        }

        dataDmlService.appendFilterConditions(filters, physicalColumns, where, args);

        String order = physicalColumns.containsKey("w_insert_dt") ? " ORDER BY w_insert_dt DESC" : "";
        
        String selectClause = "SELECT *";
        if (!selectColumns.isEmpty()) {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int i = 0; i < selectColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(selectColumns.get(i)).append("\"");
            }
            selectClause = sb.toString();
        }
        
        String sql = selectClause + " FROM " + com.example.datafill.util.SqlUtil.quoteTable(fullTableName) + where + order;
        log.info("[ExportData] 构建 SQL 完成: {}, 参数: {}", sql, args);
        
        // 预定义内部结构以存储需要导出的列位置映射，彻底避免在每行循环中重复进行字符串查找和列名转换
        int fieldsSize = fields.size();
        int[] rsColIndices = new int[fieldsSize];
        for (int c = 0; c < fieldsSize; c++) {
            FieldDef f = fields.get(c);
            String col = f.getColumnName();
            if (col != null) {
                String lowerCol = col.toLowerCase();
                int idx = selectColumns.indexOf(lowerCol);
                if (idx != -1) {
                    rsColIndices[c] = idx + 1; // ResultSet 列索引从 1 开始
                } else {
                    rsColIndices[c] = 0;
                }
            } else {
                rsColIndices[c] = 0;
            }
        }

        // 复用格式化器以避免在嵌套循环中频繁创建数百个格式化器对象造成 GC 负担
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        log.info("[ExportData] 开始流式执行 SQL 查询并写入 Excel...");
        long queryStartTime = System.currentTimeMillis();

        // 使用 SXSSFWorkbook 开启流式导出（仅在内存保留 100 行，其余缓存入临时磁盘文件），可节省 90% 以上 of JVM heap memory
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("数据导出");
            
            // 写入表头
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < fieldsSize; i++) {
                FieldDef f = fields.get(i);
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(f.getName() != null ? f.getName() : f.getColumnName());
            }
            
            // 写入数据行（使用 RowCallbackHandler 流式抓取，数据库每返回一行就写入并释放一行，内存中仅保存 1 行，彻底实现零内存积压）
            final int[] rowIdx = { 1 };
            final long[] lastLogTime = { System.currentTimeMillis() };
            final long[] totalCallbackTimeNano = { 0 };
            final long[] dbFetchTimeNano = { 0 };
            final long[] excelWriteTimeNano = { 0 };

            jdbcTemplate.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(sql);
                    // 开启游标分批读取，每次拉取 1000 条
                    ps.setFetchSize(1000);
                    for (int i = 0; i < args.size(); i++) {
                        ps.setObject(i + 1, args.get(i));
                    }
                    return ps;
                },
                rs -> {
                    long cbStart = System.nanoTime();
                    
                    long t0 = System.nanoTime();
                    Row row = sheet.createRow(rowIdx[0]++);
                    long t1 = System.nanoTime();
                    excelWriteTimeNano[0] += (t1 - t0);

                    for (int c = 0; c < fieldsSize; c++) {
                        int rsColIdx = rsColIndices[c];
                        if (rsColIdx > 0) {
                            long t2 = System.nanoTime();
                            Object val = rs.getObject(rsColIdx); // 极速：基于数字索引 of 寻址定位
                            long t3 = System.nanoTime();
                            dbFetchTimeNano[0] += (t3 - t2);

                            if (val != null) {
                                long t4 = System.nanoTime();
                                Cell cell = row.createCell(c);
                                if (val instanceof Number) {
                                    cell.setCellValue(((Number) val).doubleValue());
                                } else if (val instanceof Boolean) {
                                    cell.setCellValue((Boolean) val);
                                } else if (val instanceof java.time.LocalDateTime) {
                                    cell.setCellValue(((java.time.LocalDateTime) val).format(dtf));
                                } else if (val instanceof java.util.Date) {
                                    cell.setCellValue(sdf.format((java.util.Date) val));
                                } else {
                                    cell.setCellValue(val.toString());
                                }
                                long t5 = System.nanoTime();
                                excelWriteTimeNano[0] += (t5 - t4);
                            }
                        }
                    }
                    // 每 5000 行打印一次速度 and 进度日志
                    int currentIdx = rowIdx[0] - 1;
                    if (currentIdx % 5000 == 0) {
                        long nowTime = System.currentTimeMillis();
                        long elapsed = nowTime - lastLogTime[0];
                        lastLogTime[0] = nowTime;
                        log.info("[ExportData] 进度日志: 已流式导出数据 {} 条, 最近 5000 条数据处理耗时 {} ms", currentIdx, elapsed);
                    }

                    totalCallbackTimeNano[0] += (System.nanoTime() - cbStart);
                }
            );
            
            long queryEndTime = System.currentTimeMillis();
            long queryTotalTimeMs = queryEndTime - queryStartTime;
            long callbackTimeMs = totalCallbackTimeNano[0] / 1_000_000;
            long dbOverheadMs = queryTotalTimeMs - callbackTimeMs;
            long dbFetchMs = dbFetchTimeNano[0] / 1_000_000;
            long excelWriteMs = excelWriteTimeNano[0] / 1_000_000;

            log.info("[ExportData] 性能细分分析 -> 总查询与填充时间: {} ms", queryTotalTimeMs);
            log.info("[ExportData] 性能细分分析 -> 数据库行读取/网络传输等待时间 (rs.next): {} ms", dbOverheadMs);
            log.info("[ExportData] 性能细分分析 -> JDBC 值类型转化时间 (getObject): {} ms", dbFetchMs);
            log.info("[ExportData] 性能细分分析 -> Excel 单元格创建与数据写入(含SXSSF硬盘写)时间: {} ms", excelWriteMs);
            log.info("[ExportData] 数据流式读取并填入 Excel 完成, 共 {} 条记录", rowIdx[0] - 1);
            
            // 设置默认列宽为 20 个字符（避免在大数据量下执行 AWT 字体宽度计算导致极高耗时）
            sheet.setDefaultColumnWidth(20);
            
            log.info("[ExportData] 开始写入输出文件流...");
            long writeStartTime = System.currentTimeMillis();
            workbook.write(outputStream);
            log.info("[ExportData] 输出文件流写入完成, 耗时: {} ms", (System.currentTimeMillis() - writeStartTime));
            
            // 主动清理流式导出的临时磁盘文件缓存
            workbook.dispose();
        }
        log.info("[ExportData] 导出流程彻底完成, 总耗时: {} ms", (System.currentTimeMillis() - startTime));
    }

    
	/**
     * 
     * 6. 解析上传Excel，将每一行作为一条填报记录写入动态物理表
     * 
     */

    /**
     * 6. 将 Excel 数据批量导入物理表
     * 优化：硬盘暂存缓冲区 + 原子事务 COPY
     */
    @Transactional(value = "dynamicTransactionManager", rollbackFor = Exception.class)
    public Map<String, Object> importData(String formId, MultipartFile file, String mode, String creator, boolean isAdmin)
            throws IOException {
        if (file.getSize() > (long) maxFileSizeMb * 1024 * 1024) {
            throw new RuntimeException("上传文件过大（超过 " + maxFileSizeMb + "MB），为了确保系统稳定性，请将数据分批进行导入。");
        }
        com.example.datafill.entity.DataFillForm form = formMapper.selectById(formId);
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

        // --- 增强：识别物理列属性，用于处理自增主键 ---
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        java.util.Map<String, String> physicalColumns = dataDmlService.loadPhysicalColumns(schema, form.getTableName());
        String pk = (form.getPkColumn() != null && !form.getPkColumn().isEmpty()) ? form.getPkColumn() : "id";
        String pkType = physicalColumns.get(pk.toLowerCase());
        boolean isNumericPk = dataDmlService.isNumericType(pkType);
        boolean hasPkCol = physicalColumns.containsKey(pk.toLowerCase());

        // 检测主键是否为数据库自增主键，非自增则不排除，允许显式导入
        boolean isAutoIncrement = dataDmlService.isColumnAutoIncrement(schema, form.getTableName(), pk);
        boolean shouldExcludePk = hasPkCol && isNumericPk && isAutoIncrement;

        // 动态补齐缺失的系统审计列（load_user 等），确保 Excel 导入时写入物理表
        String[] auditCols = {"load_user", "w_insert_dt", "w_update_dt", "delete_flag"};
        for (String auditCol : auditCols) {
            boolean hasInPhysical = physicalColumns.containsKey(auditCol.toLowerCase());
            boolean hasInFields = fields.stream().anyMatch(f -> auditCol.equalsIgnoreCase(f.getColumnName()));
            if (hasInPhysical && !hasInFields) {
                FieldDef f = new FieldDef();
                f.setColumnName(auditCol);
                f.setOriginalColumnName(auditCol);
                f.setName(auditCol);
                f.setSystemLocked(true);
                fields.add(f);
            }
        }

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
                // 保留旧退行行为，但输出日志方便排查“有数据却映射不到列”的问题
                log.warn("解析 referenceTemplateConfig.headerMappings 失败, formId={}", form.getId(), e);
            }
        }

        int totalCount = 0;
        java.util.Set<String> unresolvedHeaders = new java.util.LinkedHashSet<>();
        int skippedUnmappedRowCount = 0;
        Integer firstSkippedUnmappedRowNum = null;
        List<String> validationErrors = new ArrayList<>();
        int maxErrorsToCollect = 100;
        Map<String, String> sqlResultCache = new HashMap<>();

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

            class KVPairConfig {
                private final int fk;
                private final int fv;
                private final String targetJsonCol;
                KVPairConfig(int fk, int fv, String targetJsonCol) {
                    this.fk = fk;
                    this.fv = fv;
                    this.targetJsonCol = targetJsonCol;
                }
                int fk() { return fk; }
                int fv() { return fv; }
                String targetJsonCol() { return targetJsonCol; }
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
			java.io.File tempFile = null;
            java.io.BufferedWriter writer = null;
            Set<Integer> consumed = new HashSet<>();
            try {
                tempFile = java.io.File.createTempFile("import_staging_", ".tsv");
                writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(tempFile), StandardCharsets.UTF_8));

                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    if (row == null) 
                        continue;
                    
                    if (!checkedTemplate && row.getRowNum() == 1) {
                        checkedTemplate = true;
                        int matchCount = 0;
                        for (int c = 0; c < lastCol; c++) {
                            Cell cell = row.getCell(c);
                            if (cell != null) {
                                String val = dataFormatter.formatCellValue(cell).trim();
                                if (!val.isEmpty() && fields.stream().anyMatch(f -> val.equalsIgnoreCase(f.getName()) || val.equalsIgnoreCase(f.getColumnName()))) {
                                matchCount++;
                            }
                        }
                    }
                    // 只有当超过 1 个单元格匹配到字段名（中文或英文）时，才认为它是第二个表头行。
                    // 仅 1 个匹配极其容易与真实数据冲突（如某个单元格恰好填了与字段名相同的单词）。

					if (matchCount > 1) {
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
                    consumed.clear();

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
                                if (isDateColumn[vCol]) {
                                    vvObj = vc.getDateCellValue();
                                } else {
                                    double dVal = vc.getNumericCellValue();
                                    vvObj = new java.math.BigDecimal(String.valueOf(dVal)).stripTrailingZeros().toPlainString();
                                }
                            } else {
                                String s = dataFormatter.formatCellValue(vc).trim();
                                // Handle formatted numbers like "1,875.00"
                                if (s.contains(",") && s.replace(",", "").matches("-?\\d*\\.?\\d+")) {
                                    try {
                                        vvObj = new java.math.BigDecimal(s.replace(",", "")).stripTrailingZeros().toPlainString();
                                    } catch (Exception e) {
                                        vvObj = s;
                                    }
                                } else {
                                    vvObj = s;
                                }
                            }
                        }

                        if (kvStr != null && !kvStr.isEmpty() && vvObj != null && !isBlankValue(vvObj)) {
                            Map<String, Object> extraMap = dynamicExtras.computeIfAbsent(pc.targetJsonCol(), k -> new LinkedHashMap<>());
                            extraMap.put(kvStr, mergeValues(extraMap.get(kvStr), vvObj));
                            consumed.add(pc.fk());
                            consumed.add(pc.fv());
                        }
                    }

                    boolean hasMappedBusinessValue = false;
                    for (int c = 0; c < lastCol; c++) {
                        if (consumed.contains(c) || (c < cachedPinyinHeaders.length && cachedPinyinHeaders[c] == null))
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
                                // 避免使用 DataFormatter，因为它极易受到服务器 Excel locale 的影响而输出如 "3/1/26 5:45"
                                // 的奇葩文本导致后期入库解析报错。
                                // 返回原生 Date 可以保证系统底层使用统一的 "yyyy-MM-dd HH:mm:ss" 进行高质量存表。
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
                                double dVal = cell.getNumericCellValue();
                                val = new java.math.BigDecimal(String.valueOf(dVal)).stripTrailingZeros().toPlainString();
                            }
                        } else {
                            String s = dataFormatter.formatCellValue(cell).trim();
                            // Handle formatted numbers like "1,875.00"
                            if (s.contains(",") && s.replace(",", "").matches("-?\\d*\\.?\\d+")) {
                                try {
                                    val = new java.math.BigDecimal(s.replace(",", "")).stripTrailingZeros().toPlainString();
                                } catch (Exception e) {
                                    val = s;
                                }
                            } else {
                                val = s;
                            }
                        }

                        if (val != null && !isBlankValue(val)) {
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
                                
                            } else {
                                defaultExtra.put(cachedPinyinHeaders[c], val);
                            }
                        } else {
                            // 即使值为空，如果表头未映射，也要记录到 unresolvedHeaders 中用于报错提示
                            String dbCol = cachedDbCols[c];
                            if (hasReferenceMappings && dbCol == null && headers[c] != null
                                    && !headers[c].trim().isEmpty()) {
                                unresolvedHeaders.add(headers[c].trim());
                            }
                        }
                        
                    }


                    if (empty && dynamicExtras.isEmpty() && defaultExtra.isEmpty())
                        continue;


                    // 合并所有其它的到 extra_data
                    // 若模板已定义了业务 JSON 字段 (如 xxx_json)，默认不再把剩余列强行并入 extra_data.
                    // 只有当表单中显式存在 extra_data 时，才把 defaultExtra 合并进去。
                    if (!defaultExtra.isEmpty() && fieldColumnNames.contains("extra_data")) {
                        dynamicExtras.computeIfAbsent("extra_data", k -> new LinkedHashMap<>()).putAll(defaultExtra);
                    }
                    // 将所有的 JSON 列表转换为字符串并存入 rowData
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

                    hasMappedBusinessValue = hasMappedBusinessValue || !rowData.isEmpty();
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
                        if (!missingRequired.isEmpty())
                            throw new RuntimeException(
                                    "导入失败: 第 " + (row.getRowNum() + 1) + " 行缺少必填字段: " + String.join(", ", missingRequired));
                    }

                    for (FieldDef f : fields) {
                        if (f.getColumnName() == null || Boolean.TRUE.equals(f.getHideInForm())) {
                            continue;
                        }
                        Object val = rowData.get(f.getColumnName());
                        String displayName = (f.getName() == null || f.getName().trim().isEmpty()) ? f.getColumnName()
                                : f.getName();
                        if (isBlankValue(val)) {
                            continue;
                        }
                        String valStr = val.toString();
                        validateFieldFormat(row, f, valStr, displayName, validationErrors);
                        if (f.getValidationSql() != null && !f.getValidationSql().trim().isEmpty()) {
                            validateSqlDimension(f, valStr, displayName, row.getRowNum() + 1, validationErrors,
                                    sqlResultCache);
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
                    
                    // 如果是手动管理的主键（非自增），且 Excel 中没填，自动为新插入行生成唯一主键值，防止报错
                    if (hasPkCol && !isAutoIncrement) {
                        Object providedPk = null;
                        for (String key : rowData.keySet()) {
                            if (key.equalsIgnoreCase(pk)) {
                                providedPk = rowData.get(key);
                                break;
                            }
                        }
                        if (providedPk == null || providedPk.toString().trim().isEmpty()) {
                            if (isNumericPk) {
                                rowData.put(pk, System.currentTimeMillis() * 1000L + (long)(Math.random() * 1000L));
                            } else {
                                rowData.put(pk, java.util.UUID.randomUUID().toString().replace("-", ""));
                            }
                        }
                    }

                    writeRowToStaging(writer, rowData, fields, pk, shouldExcludePk, hasPkCol, creator, row.getRowNum() + 1);
                    totalCount++;
                    if (validationErrors.size() >= maxErrorsToCollect) {
                        break;
                    }
                }

                writer.flush();
                writer.close();

                if (!validationErrors.isEmpty()) {
                    return generateValidationErrorReport(formId, validationErrors);
                }

                if (totalCount == 0 && skippedUnmappedRowCount > 0) {
                    String unresolvedHint = unresolvedHeaders.isEmpty() ? ""
                            : (": 未匹配表头示例: " + String.join(", ",
                                    unresolvedHeaders.stream().limit(8).collect(java.util.stream.Collectors.toList())));
                    throw new RuntimeException("导入失败：检测到 " + skippedUnmappedRowCount
                            + " 行数据未映射到业务字段（首行: 第 " + firstSkippedUnmappedRowNum + " 行）"
                            + "，请确认上传文件表头与模板一致" + unresolvedHint);
                }

                if (totalCount > 0) {
                    executeAtomicCopy(formId, tempFile, fields, pk, shouldExcludePk, hasPkCol, creator, isAdmin);

                    try {
                        UserFillLog fillLog = new UserFillLog();
                        fillLog.setFormId(formId);
                        fillLog.setUserEmail(creator);
                        fillLog.setSubmitTime(LocalDateTime.now());
                        fillLog.setDataId("IMPORT " + java.util.UUID.randomUUID().toString().substring(0, 8));
                        userFillLogMapper.insert(fillLog);
                    } catch (Exception e) {
                        log.warn("写入填报日志失败, formId={}, user={}", formId, creator, e);
                    }
                } else {
                    throw new RuntimeException("导入失败：未识别到有效业务数据");
                }
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {
                    }
                }
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
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
    }

    private Object mergeValues(Object oldVal, Object newVal) {
        if (oldVal == null) return newVal;
        if (newVal == null) return oldVal;
        String s1 = oldVal.toString().trim();
        String s2 = newVal.toString().trim();
        if (NUMBER_PATTERN.matcher(s1).matches() && NUMBER_PATTERN.matcher(s2).matches()) {
            try {
                java.math.BigDecimal d1 = new java.math.BigDecimal(s1);
                java.math.BigDecimal d2 = new java.math.BigDecimal(s2);
                return d1.add(d2).stripTrailingZeros().toPlainString();
            } catch (Exception ignored) {}
        }
        return s1 + ", " + s2;
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
                            throw new RuntimeException("导入失败：Excel 第 " + excelRow + " 行入库失败，原始错误：" + msg, e);
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
     * 7. 解析上传Excel 表头，生成字段定义序列(增强
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

        com.example.datafill.dto.ReferenceTemplateParseResult result = new com.example.datafill.dto.ReferenceTemplateParseResult();

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
        } else {
            // [强约束] 如果参考模板中物理列里已经写了 id，说明导入时会冲突（系统要自己管 id）
            result.setHasIdConflict(true);
            result.setConflictMessage("检测到物理表参考区块中已包含 id 字段。为保证系统自动分配主键，请管理员先在参考模板中移除该列，然后再次识别并点击‘一键补齐’。");
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
            
            // --- 核心增强：识别 Excel 中是否包含 id 字段 ---
            boolean hasIdInExcel = originalHeaders.stream()
                .anyMatch(h -> h != null && "id".equalsIgnoreCase(h.trim()));
            if (hasIdInExcel) {
                result.setHasIdConflict(true);
                result.setConflictMessage("检测到 Excel 中已包含 id 字段。为了由系统统一管理主键并确保 COPY 导入性能，请管理员先删除 Excel 中的 id 列，然后再次上传识别。");
            }

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
                        String originalText = cell.getStringCellValue();
                        if (originalText.contains(",")) {
                            s.hasCommas = true;
                        }
                        String text = originalText.trim().replace(",", "");
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

            Set<String> usedColNames = new HashSet<>(java.util.Collections.singletonList("id"));

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
                "c.column_default, c.is_identity, " +
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

        // 查找物理表的主键列
        String pkSql = "SELECT a.attname AS pk_column " +
                       "FROM pg_index i " +
                       "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
                       "JOIN pg_catalog.pg_namespace ns ON ns.oid = (SELECT relnamespace FROM pg_class WHERE oid = i.indrelid) " +
                       "JOIN pg_catalog.pg_class cls ON cls.oid = i.indrelid " +
                       "WHERE ns.nspname = ? AND cls.relname = ? AND i.indisprimary";
        List<String> pkColumns = jdbcTemplate.query(pkSql, (rs, rowNum) -> rs.getString("pk_column"), schemaName, tableName);
        boolean isComposite = pkColumns.size() > 1;
        result.setCompositePrimaryKey(isComposite);
        String detectedPk = pkColumns.isEmpty() ? null : (isComposite ? String.join(", ", pkColumns) : pkColumns.get(0));
        result.setDetectedPrimaryKey(detectedPk);

        List<String> missing = new ArrayList<>();
        if (detectedPk != null) {
            // 已有物理主键，但若物理表中完全不包含 id 列，依然提供补齐为系统默认主键的机会
            if (!physicalColumns.contains("id")) {
                missing.add("id");
            }
        } else {
            // 没有物理主键时，检查是否已经有 id 列，否则标记缺失
            if (physicalColumns.contains("id")) {
                // [强约束] 只要物理表有 id，就触发冲突标记，引导用户去重命名/删除以让出主键控制权
                result.setHasIdConflict(true);
                result.setConflictMessage("检测到物理表已存在 id 字段。为保证系统自动分配主键，请管理员先在数据库中删除或重命名该列，然后再次识别并点击‘一键补齐’。");
                
                boolean isStandardId = false;
                for (Map<String, Object> r : rows) {
                    if ("id".equalsIgnoreCase(asText(r.get("column_name")))) {
                        String udtLabel = lower(asText(r.get("udt_name")));
                        String isIdent = asText(r.get("is_identity"));
                        String colDef = asText(r.get("column_default"));
                        if ("int4".equals(udtLabel)) {
                            if ("YES".equalsIgnoreCase(isIdent) || (colDef != null && colDef.contains("nextval"))) {
                                isStandardId = true;
                            }
                        }
                        break;
                    }
                }
                if (!isStandardId) {
                    missing.add("id");
                }
            } else {
                missing.add("id");
            }
        }

        String dInsert = detectRole(physicalColumns, INSERT_AUDIT_LEXICON);
        String dUpdate = detectRole(physicalColumns, UPDATE_AUDIT_LEXICON);
        String dDelete = detectRole(physicalColumns, DELETE_FLAG_LEXICON);

        if (dInsert == null) missing.add("w_insert_dt");
        if (dUpdate == null) missing.add("w_update_dt");
        if (dDelete == null) missing.add("delete_flag");

        // 增加 load_user 及索引检测
        // 增加 load_user 检测 (索引的管理由后台自动处理，不再作为缺失列提示)
        if (!physicalColumns.contains("load_user")) {
            missing.add("load_user");
        }

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
        
        // 核心增强：识别 Excel 中是否出现了除 ID 以外的系统预留字段名
        boolean isSystemReserved = EXISTING_TABLE_SYSTEM_COLUMNS.contains(baseColName.toLowerCase());
        boolean canDirectUseSystemName = isSystemReserved && !"id".equalsIgnoreCase(baseColName);

        String finalColName = baseColName;
        if (canDirectUseSystemName && !usedColNames.contains(finalColName.toLowerCase())) {
            // 如果是系统预留字段（如 w_insert_dt），且之前【在这个 Excel 里】还没出现过，则直接占用
        } else {
            // 普通字段或重复的系统字段，进入避让逻辑
            int suffixVal = 1;
            while (usedColNames.contains(finalColName.toLowerCase()) || EXISTING_TABLE_SYSTEM_COLUMNS.contains(finalColName.toLowerCase())) {
                finalColName = baseColName + "_" + suffixVal++;
            }
        }

        usedColNames.add(finalColName.toLowerCase());
        def.setColumnName(finalColName);

        boolean isSystem = EXISTING_TABLE_SYSTEM_COLUMNS.contains(finalColName.toLowerCase());
        def.setSystemLocked(isSystem);

        if (isSystem) {
            def.setRequired(false);
            def.setFilterable(false);
            // 除了 extra_data 这种允许填写的，其余系统列默认隐藏
            if (!"extra_data".equalsIgnoreCase(finalColName)) {
                def.setHideInForm(true);
                def.setHideInList(true);
            }
        }

        def.setType("input");
        def.setDbType("varchar(255)");
        if (smartType && s.nonBlankCount > 0) {
            if (s.couldBeDate) {
                def.setType("datetime");
                def.setDbType("timestamp");
            } else if (s.couldBeNumber) {
                def.setType("number");
                // 仅当存在真实小数时建议 numeric，带千分位的整数依然建议 int4，避免物理列类型非预期变更
                def.setDbType(s.hasDecimal ? "numeric" : "int4");
            } else if (s.rawValues.stream().anyMatch(val -> val.length() > 50)) {
                def.setType("textarea");
                def.setDbType("text");
            }
        }
        
        if (!isSystem) {
            def.setRequired(false);
            def.setFilterable(colIndex < 3 && s.nonBlankCount > 0);
        }
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

    private boolean checkIndexExists(String schema, String table, String columnName) {
        try {
            String sql = "SELECT count(1) FROM pg_indexes WHERE schemaname = ? AND tablename = ? AND indexdef LIKE ?";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, schema, table, "%(\"" + columnName + "\")%");
            if (count == null || count == 0) {
                // 兼容不带引号的情况
                count = jdbcTemplate.queryForObject(sql, Integer.class, schema, table, "%(" + columnName + ")%");
            }
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
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

    private void parseRowToMap(Row row, Map<String, Object> rowData, int lastCol, String[] cachedDbCols, 
                               boolean[] cachedIsJsonCol, String[] cachedJsonKeys, String[] cachedPinyinHeaders, 
                               Set<String> businessFieldColumns, Set<String> fieldColumnNames, 
                               DataFormatter dataFormatter, boolean[] dateColumnChecked, boolean[] isDateColumn, 
                               List<KVPairConfig> activeKVPairs, Set<Integer> consumed, 
                               Map<String, String> headerMap, Map<String, String> normalizedHeaderMap, 
                               Map<String, Integer> actualHeaderIndexMap) {
        Map<String, Map<String, Object>> dynamicExtras = new LinkedHashMap<>();
        Map<String, Object> defaultExtra = new LinkedHashMap<>();
        boolean empty = true;
        consumed.clear();

        for (KVPairConfig pc : activeKVPairs) {
            Cell kc = row.getCell(pc.fk()), vc = row.getCell(pc.fv());
            String kvStr = (kc == null) ? null : dataFormatter.formatCellValue(kc).trim();
            Object vvObj = parseCellValue(vc, pc.fv(), dateColumnChecked, isDateColumn, dataFormatter);
            if (kvStr != null && !kvStr.isEmpty() && vvObj != null && !"".equals(vvObj)) {
                Map<String, Object> extraMap = dynamicExtras.computeIfAbsent(pc.targetJsonCol(), k -> new LinkedHashMap<>());
                extraMap.put(kvStr, mergeValues(extraMap.get(kvStr), vvObj));
                consumed.add(pc.fk()); consumed.add(pc.fv());
            }
        }

        for (int c = 0; c < lastCol; c++) {
            if (consumed.contains(c) || (c < cachedPinyinHeaders.length && cachedPinyinHeaders[c] == null)) continue;
            Cell cell = row.getCell(c);
            Object val = parseCellValue(cell, c, dateColumnChecked, isDateColumn, dataFormatter);
            if (val != null && !"".equals(val)) {
                empty = false;
                String dbCol = cachedDbCols[c];
                if (dbCol != null) {
                    if (cachedIsJsonCol[c]) {
                        dynamicExtras.computeIfAbsent(dbCol, k -> new LinkedHashMap<>()).put(cachedJsonKeys[c], val);
                    } else {
                        rowData.put(dbCol, val);
                    }
                } else {
                    defaultExtra.put(cachedPinyinHeaders[c], val);
                }
            }
        }

        if (empty && dynamicExtras.isEmpty() && defaultExtra.isEmpty()) return;

        if (!defaultExtra.isEmpty() && fieldColumnNames.contains("extra_data")) {
            dynamicExtras.computeIfAbsent("extra_data", k -> new LinkedHashMap<>()).putAll(defaultExtra);
        }

        serializeJsonColumns(rowData, dynamicExtras);
    }

    private Object parseCellValue(Cell cell, int colIdx, boolean[] dateColumnChecked, boolean[] isDateColumn, DataFormatter dataFormatter) {
        if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) return null;
        org.apache.poi.ss.usermodel.CellType type = cell.getCellType();
        if (type == org.apache.poi.ss.usermodel.CellType.STRING) return cell.getStringCellValue();
        if (type == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
            double dVal = cell.getNumericCellValue();
            if (!dateColumnChecked[colIdx]) {
                isDateColumn[colIdx] = org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell);
                dateColumnChecked[colIdx] = true;
            }
            if (isDateColumn[colIdx]) return cell.getDateCellValue();
            return new java.math.BigDecimal(dVal).stripTrailingZeros().toPlainString();
        }
        if (type == org.apache.poi.ss.usermodel.CellType.BOOLEAN) return cell.getBooleanCellValue();
        return dataFormatter.formatCellValue(cell).trim();
    }

    private void serializeJsonColumns(Map<String, Object> rowData, Map<String, Map<String, Object>> dynamicExtras) {
        for (Map.Entry<String, Map<String, Object>> exEntry : dynamicExtras.entrySet()) {
            Map<String, Object> extraMap = exEntry.getValue();
            if (extraMap.isEmpty()) { rowData.put(exEntry.getKey(), "{}"); continue; }
            StringBuilder jsonSb = new StringBuilder();
            jsonSb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : extraMap.entrySet()) {
                if (!first) jsonSb.append(","); first = false;
                jsonSb.append("\"").append(entry.getKey().replace("\"", "\\\"")).append("\":");
                Object v = entry.getValue();
                if (v == null) jsonSb.append("null");
                else if (v instanceof Number || v instanceof Boolean) jsonSb.append(v.toString());
                else jsonSb.append("\"").append(v.toString().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")).append("\"");
            }
            jsonSb.append("}");
            rowData.put(exEntry.getKey(), jsonSb.toString());
        }
    }

    private void validateFieldFormat(Row row, FieldDef f, String valStr, String displayName, List<String> validationErrors) {
        if (f.getMin() != null || f.getMax() != null) {
            try {
                double dVal = Double.parseDouble(valStr);
                if (f.getMin() != null && dVal < f.getMin()) validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") 需 ≥ " + f.getMin());
                if (f.getMax() != null && dVal > f.getMax()) validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") 需 ≤ " + f.getMax());
            } catch (Exception e) {
                validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") 格式错误，应为数字");
            }
        }
        if (f.getMinLength() != null && valStr.length() < f.getMinLength()) validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") 长度需 ≥ " + f.getMinLength());
        if (f.getMaxLength() != null && valStr.length() > f.getMaxLength()) validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") 长度需 ≤ " + f.getMaxLength());
        if (f.getPattern() != null && !f.getPattern().trim().isEmpty()) {
            try {
                if (!valStr.matches(f.getPattern())) {
                    String msg = (f.getPatternMsg() != null && !f.getPatternMsg().trim().isEmpty()) ? f.getPatternMsg() : "格式不符合要求";
                    validationErrors.add("第 " + (row.getRowNum() + 1) + " 行 [" + displayName + "] (内容: \"" + valStr + "\") " + msg);
                }
            } catch (Exception e) {
                log.warn("正则失败: pattern={}, val={}", f.getPattern(), valStr);
            }
        }
    }

    private void validateSqlDimension(FieldDef f, String valStr, String displayName, int rowNum, List<String> validationErrors, Map<String, String> cache) {
        String cacheKey = f.getColumnName() + "_sql_val_" + valStr;
        String cached = cache.get(cacheKey);
        if ("VALID".equals(cached)) return;
        if (cached != null && cached.startsWith("INVALID:")) {
            validationErrors.add("第 " + rowNum + " 行 [" + displayName + "] " + cached.substring(8));
            return;
        }
        
        String sql = f.getValidationSql() != null ? f.getValidationSql().trim() : "";
        if (sql.contains(";")) {
            validationErrors.add("第 " + rowNum + " 行 [" + displayName + "] 校验 SQL 不允许包含分号 (;)");
            return;
        }
        
        try {
            String limitedSql = "SELECT * FROM (" + sql + ") AS tmp LIMIT 1";
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedJdbcTemplate = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate);
            Map<String, Object> params = new HashMap<>();
            params.put("val", valStr);
            List<Map<String, Object>> rows = namedJdbcTemplate.queryForList(limitedSql, params);
            
            if (rows != null && !rows.isEmpty()) {
                cache.put(cacheKey, "VALID");
            } else {
                String msg = (f.getValidationSqlMsg() != null && !f.getValidationSqlMsg().trim().isEmpty()) ? f.getValidationSqlMsg() : "不在合法的维度范围内";
                String fullMsg = "(内容: \"" + valStr + "\") " + msg;
                cache.put(cacheKey, "INVALID:" + fullMsg);
                validationErrors.add("第 " + rowNum + " 行 [" + displayName + "] " + fullMsg);
            }
        } catch (Exception e) {
            log.warn("SQL校验失败: col={}, val={}", f.getColumnName(), valStr, e);
            validationErrors.add("第 " + rowNum + " 行 [" + displayName + "] 维度校验系统异常");
        }
    }

    private void writeRowToStaging(java.io.BufferedWriter writer, Map<String, Object> rowData, List<FieldDef> fields, String pk, boolean isNumericPk, boolean hasPkCol, String creator, int rowNum) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (FieldDef f : fields) {
            String colName = f.getColumnName();
            if (colName == null || colName.trim().isEmpty()) continue;
            
            // 如果是自增数字主键，则不写入，由数据库生成
            if (hasPkCol && isNumericPk && colName.equalsIgnoreCase(pk)) continue;

            if (!first) sb.append("\t"); first = false;
            
            Object val = rowData.get(colName);
            if (val == null) {
                if ("load_user".equalsIgnoreCase(colName) || "creator".equalsIgnoreCase(colName)) val = creator;
                else if ("w_insert_dt".equalsIgnoreCase(colName) || "create_time".equalsIgnoreCase(colName)) val = LocalDateTime.now();
                else if ("w_update_dt".equalsIgnoreCase(colName) || "update_time".equalsIgnoreCase(colName)) val = LocalDateTime.now();
                else if ("delete_flag".equalsIgnoreCase(colName)) val = false;
            }
            
            if (val == null) {
                sb.append("\\N");
            } else {
                String s;
                if (val instanceof LocalDateTime) {
                    s = ((LocalDateTime) val).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } else if (val instanceof java.util.Date) {
                    s = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
                } else if (val instanceof Double || val instanceof Float || val instanceof java.math.BigDecimal) {
                    s = new java.math.BigDecimal(val.toString()).stripTrailingZeros().toPlainString();
                } else {
                    s = val.toString();
                }
                
                // 转义特殊字符
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '\\') sb.append("\\\\");
                    else if (c == '\t') sb.append("\\t");
                    else if (c == '\n') sb.append("\\n");
                    else if (c == '\r') sb.append("\\r");
                    else sb.append(c);
                }
            }
        }
        writer.write(sb.toString());
        writer.newLine();
    }

    private Map<String, Object> generateValidationErrorReport(String formId, List<String> errors) {
        String reportId = java.util.UUID.randomUUID().toString();
        StringBuilder sb = new StringBuilder("数据填报导入错误报告\n时间: " + LocalDateTime.now() + "\n表单ID: " + formId + "\n总计错误数: " + errors.size() + "\n--------------------------------------------------\n\n");
        for (String err : errors) sb.append(err).append("\n");
        errorReportCache.put(reportId, sb.toString());
        Map<String, Object> res = new HashMap<>();
        res.put("success", false); res.put("hasValidationErrors", true); res.put("errorCount", errors.size());
        res.put("reportId", reportId); res.put("message", "检测到共 " + errors.size() + " 处数据不合规。为了保障数据一致性，本次导入已全部安全回滚（未写入任何记录）。请下载详细错误清单，修改后重新上传。");
        return res;
    }

    private void executeAtomicCopy(String formId, java.io.File tempFile, List<FieldDef> fields, String pk, boolean isNumericPk, boolean hasPkCol, String creator, boolean isAdmin) {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");
        String schema = (form.getSchemaName() != null && !form.getSchemaName().trim().isEmpty()) ? form.getSchemaName() : "public";
        String tableName = schema + "." + form.getTableName();
        String quotedTableName = com.example.datafill.util.SqlUtil.quoteTable(tableName);

        List<String> cols = new ArrayList<>();
        for (FieldDef f : fields) {
            String colName = f.getColumnName();
            if (colName != null && !colName.trim().isEmpty()) {
                // 如果是自增数字主键，则不加入 COPY 列名列表
                if (hasPkCol && isNumericPk && colName.equalsIgnoreCase(pk)) continue;
                cols.add("\"" + colName + "\"");
            }
        }
        String sql = "COPY " + quotedTableName + " (" + String.join(",", cols) + ") FROM STDIN WITH (FORMAT text, DELIMITER '\t', NULL '\\N', ENCODING 'UTF8')";
        
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Object>) con -> {
            try {
                org.postgresql.PGConnection pgCon = con.unwrap(org.postgresql.PGConnection.class);
                try (java.io.InputStream in = new java.io.FileInputStream(tempFile)) {
                    pgCon.getCopyAPI().copyIn(sql, in);
                }
                return null;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("duplicate key value")) {
                    throw new RuntimeException("导入失败：数据中存在重复的主键，或与数据库中已有的主键发生冲突，请检查后再试。");
                }
                if (errorMsg != null && errorMsg.contains("out of range for type integer") && errorMsg.toLowerCase().contains("column " + pk.toLowerCase())) {
                    throw new RuntimeException("导入失败：主键没有设置自增属性。");
                }
                throw new RuntimeException("数据库原子写入失败: " + errorMsg, e);
            }
        });
    }

    private void logSubmitActivity(String formId, String user, int count) {
        try {
            UserFillLog fillLog = new UserFillLog();
            fillLog.setFormId(formId); fillLog.setUserEmail(user); fillLog.setSubmitTime(LocalDateTime.now());
            fillLog.setDataId("IMPORT_" + java.util.UUID.randomUUID().toString().substring(0, 8));
            userFillLogMapper.insert(fillLog);
            
            // 同步受影响用户的填报快照
            dataDmlService.refreshUserCompletionSnapshot(user, formId);
        } catch (Exception e) { log.warn("日志或快照同步失败", e); }
    }

    private static record KVPairConfig(int fk, int fv, String targetJsonCol) {}




}