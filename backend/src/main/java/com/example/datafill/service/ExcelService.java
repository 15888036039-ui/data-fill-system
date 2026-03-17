package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.mapper.DataFillFormMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.apache.poi.ss.usermodel.Cell;

import org.apache.poi.ss.usermodel.Row;

import org.apache.poi.ss.usermodel.Sheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.LinkedHashMap;

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

    private final DataFillFormMapper formMapper;

    private final DynamicDataDmlService dataDmlService;

    private final ObjectMapper objectMapper;

    private final JdbcTemplate jdbcTemplate;

    private final SystemConfigService configService;

    /**

     * 5. 生成当前表单对应Excel 填报模板

     */

    public void exportTemplate(String formId, OutputStream outputStream) throws IOException {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

            throw new RuntimeException("表单解析错误", e);

        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet(form.getName() != null ? form.getName() : form.getTableName());

            // 第一行：数据库列名（程序识别用，不要改）

            Row headerRow = sheet.createRow(0);

            // 第二行：中文显示名（给填报人看的）
            Row displayRow = sheet.createRow(1);

            int colIndex = 0;

            for (FieldDef field : fields) {

                Cell headerCell = headerRow.createCell(colIndex);

                headerCell.setCellValue(field.getColumnName());

                Cell displayCell = displayRow.createCell(colIndex);

                String display = field.getName();

                if (Boolean.TRUE.equals(field.getRequired())) {

                    display = display + "（必填）";

                }

                displayCell.setCellValue(display);

                // 自适应列宽

                sheet.autoSizeColumn(colIndex);

                colIndex++;

            }

            workbook.write(outputStream);

        }

    }

    /**

     * 6. 解析上传Excel，将每一行作为一条填报记录写入动态物理表

     */

    public int importData(String formId, MultipartFile file, String mode, String creator) throws IOException {

        DataFillForm form = formMapper.selectById(formId);

        if (form == null) {

            throw new RuntimeException("表单不存在");

        }

        String tableName = form.getTableName();

        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("表名称非 " + tableName);
        }

        if ("overwrite".equals(mode)) {

            // mode = overwrite. In the refactored soft-delete scheme, maybe we just delete them all.

            String sql = "UPDATE \"" + tableName + "\" SET is_deleted = 1";

            try {

                jdbcTemplate.update(sql);

            } catch (Exception e) {

                 // 退化处                 jdbcTemplate.update("DELETE FROM \"" + tableName + "\"");

            }

        }

        List<FieldDef> fields;

        try {

            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});

        } catch (JsonProcessingException e) {

             throw new RuntimeException("表单字段解析异常", e);

        }

        // 建立灵活的映射，允许通过 数据库字段名(Template) 中文原Excel) 来匹
        Map<String, String> headerToDbColumnMap = new java.util.HashMap<>();

        for (FieldDef f : fields) {

            if (f.getColumnName() != null) {

                headerToDbColumnMap.put(f.getColumnName().trim(), f.getColumnName());

            }

            if (f.getName() != null) {

                headerToDbColumnMap.put(f.getName().trim(), f.getColumnName());

                // 兼容有些换行/回车符差                headerToDbColumnMap.put(f.getName().replaceAll("[\\r\\n]+", "").trim(), f.getColumnName());

            }

        }

        int successCount = 0;

        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {

            Sheet sheet = workbook.getSheetAt(0);

            if (sheet == null) {

                return 0;

            }

            // 第一行作为“列名行
            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {

                return 0;

            }

            int lastColumn = headerRow.getLastCellNum();

            String[] headers = new String[lastColumn];

            boolean isTemplateMode = false;

            for (int i = 0; i < lastColumn; i++) {

                Cell cell = headerRow.getCell(i);

                if (cell != null) {

                    headers[i] = cell.getStringCellValue().trim();

                    final String headerName = headers[i];

                    // 如果表头直接命中了英文字段名，说明用户传的是系统生成Template（第1行英文，行中文）

                    if (fields.stream().anyMatch(f -> headerName.equals(f.getColumnName()))) {

                        isTemplateMode = true;

                    }

                } else {

                    headers[i] = null;

                }

            }

            int dataStartRow = isTemplateMode ? 2 : 1; // Template 从第3行取数据，原Excel从第2行取数据

            int lastRow = sheet.getLastRowNum();

            List<Map<String, Object>> rowsToInsert = new ArrayList<>();

            for (int r = dataStartRow; r <= lastRow; r++) { 

                Row row = sheet.getRow(r);

                if (row == null) continue;

                Map<String, Object> rowData = new LinkedHashMap<>();

                Map<String, Object> extraData = new LinkedHashMap<>();

                boolean allEmpty = true;

                // 专家增强：通用启发式键值对提取 (Heuristic KV Extraction)

                // 1. 按序号对列进行分
                Map<Integer, List<Integer>> groupedBySuffix = new java.util.HashMap<>();

                java.util.regex.Pattern suffixPattern = java.util.regex.Pattern.compile("(.*)(\\d+)$");

                for (int c = 0; c < lastColumn; c++) {

                    String h = headers[c];

                    if (h == null) continue;

                    java.util.regex.Matcher sm = suffixPattern.matcher(h.trim());

                    if (sm.find()) {

                        int suffix = Integer.parseInt(sm.group(2));

                        groupedBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(c);

                    }

                }

                // 2. 在每个分组内识别有序 Key-Value
                Set<Integer> consumedCols = new HashSet<>();

                Map<String, String> kwPairs = configService.getKwPairs();

                if (kwPairs.isEmpty()) {

                    kwPairs = new HashMap<>();

                    kwPairs.put("description", "amount");

                    kwPairs.put("desc", "amt");

                    kwPairs.put("name", "price");

                    kwPairs.put("type", "val");

                    kwPairs.put("key", "value");

                    kwPairs.put("item", "total");

                    kwPairs.put("label", "val");

                    kwPairs.put("msg", "count");

                }

                for (Map.Entry<Integer, List<Integer>> entry : groupedBySuffix.entrySet()) {

                    List<Integer> colIndices = entry.getValue();

                    if (colIndices.size() < 2) continue;

                    Integer keyColIdx = null;

                    Integer valColIdx = null;

                    // 精确配对逻辑：仅当组内同时存在 Key Value 关键词时才提取
                    outer:

                    for (Map.Entry<String, String> pair : kwPairs.entrySet()) {

                        String targetKey = pair.getKey().toLowerCase();

                        String targetVal = pair.getValue().toLowerCase();

                        Integer foundKey = null;

                        Integer foundVal = null;

                        for (Integer idx : colIndices) {

                            String colName = headers[idx].toLowerCase().replaceAll("\\d+$", "").trim();

                            if (colName.endsWith(targetKey)) foundKey = idx;

                            else if (colName.endsWith(targetVal)) foundVal = idx;

                        }

                        if (foundKey != null && foundVal != null) {

                            keyColIdx = foundKey;

                            valColIdx = foundVal;

                            break outer;

                        }

                    }

                    if (keyColIdx != null && valColIdx != null) {

                        Cell kCell = row.getCell(keyColIdx);

                        Cell vCell = row.getCell(valColIdx);

                        Object kVal = (kCell == null) ? null : kCell.toString().trim();

                        Object vVal = (vCell == null) ? null : (vCell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC ? vCell.getNumericCellValue() : vCell.toString().trim());

                        if (kVal != null && !kVal.toString().isEmpty() && vVal != null && !vVal.toString().isEmpty()) {

                            extraData.put(kVal.toString(), vVal);

                            consumedCols.add(keyColIdx);

                            consumedCols.add(valColIdx);

                        }

                    }

                    // 移除了之前的 else 兜底逻辑：非配对列将作为普通字段处理，不再强行塞入 JSON 归集单元

                }

                // 3. 处理常规
                for (int c = 0; c < lastColumn; c++) {

                    if (consumedCols.contains(c)) continue;

                    String columnName = headers[c];

                    if (columnName == null) continue;

                    Cell cell = row.getCell(c);

                    if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;

                    Object value = switch (cell.getCellType()) {

                        case STRING -> cell.getStringCellValue();

                        case NUMERIC -> {

                            if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) yield cell.getDateCellValue();

                            yield cell.getNumericCellValue();

                        }

                        case BOOLEAN -> cell.getBooleanCellValue();

                        default -> cell.toString();

                    };

                    if (value != null && !"".equals(value)) {

                        allEmpty = false;

                        String dbColumnName = headerToDbColumnMap.get(columnName);

                        if (dbColumnName == null) {

                            dbColumnName = headerToDbColumnMap.get(columnName.replaceAll("[\\r\\n]+", ""));

                        }

                        if (dbColumnName != null) {

                            rowData.put(dbColumnName, value);

                        } else {

                            String normalizedKey = generateDwColumnName(columnName, c);

                            extraData.put(normalizedKey, value);

                        }

                    }

                }

                if (allEmpty && extraData.isEmpty()) continue;

                if (!extraData.isEmpty()) rowData.put("extra_data", extraData);

                if (creator != null && !creator.isBlank()) rowData.put("creator", creator);

                rowsToInsert.add(rowData);

            }

            if (!rowsToInsert.isEmpty()) {

                dataDmlService.batchInsertRowData(formId, rowsToInsert);

                successCount = rowsToInsert.size();

            }

        }

        return successCount;

    }

    /**

     * 7. 解析上传Excel 表头，生成字段定义列(增强

     */

    public List<FieldDef> parseExcelHeaders(MultipartFile file, String mode, boolean smartType, boolean kvPairEnabled) throws IOException {

        List<FieldDef> fields = new ArrayList<>();

        boolean anyPairFound = false;

        if ("json".equalsIgnoreCase(mode)) {

            return fields; // JSON 归集模式下直接返回空列表，前端只处理表名即可

        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {

            Sheet sheet = workbook.getSheetAt(0);

            if (sheet == null) return fields;

            Row headerRow = sheet.getRow(0);

            if (headerRow == null) return fields;

            int lastColumn = headerRow.getLastCellNum();

            if (lastColumn <= 0) return fields;

            int scanLimit = Math.min(sheet.getLastRowNum(), 100); // 扫描100 行样            

            // 数据统计结构

            class ColumnStat {

                int colIndex;

                String headerName;

                int nonBlankCount = 0;

                boolean couldBeDate = true;

                boolean couldBeNumber = true;

                Set<String> uniqueValues = new HashSet<>();

                List<String> rawValues = new ArrayList<>();

                ColumnStat(int idx, String name) { this.colIndex = idx; this.headerName = name; }

            }

            List<ColumnStat> stats = new ArrayList<>();

            for (int c = 0; c < lastColumn; c++) {

                Cell cell = headerRow.getCell(c);

                String name = (cell == null) ? "" : cell.toString().trim();

                if (name.isEmpty()) name = "未命名字段" + (c + 1);

                stats.add(new ColumnStat(c, name));

            }

            // 采样扫描

            for (int r = 1; r <= scanLimit; r++) {

                Row row = sheet.getRow(r);

                if (row == null) continue;

                for (int c = 0; c < lastColumn; c++) {

                    Cell cell = row.getCell(c);

                    ColumnStat s = stats.get(c);

                    if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;

                    s.nonBlankCount++;

                    String val = cell.toString().trim();

                    s.uniqueValues.add(val);

                    if (s.rawValues.size() < 100) s.rawValues.add(val);

                    if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC) {

                        s.couldBeNumber = false;

                        s.couldBeDate = false;

                    } else if (!org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {

                        s.couldBeDate = false;

                    }

                }

            }

            // 模式统一：始终执行全量识别（上限 200 列），配合智能列拆分逻辑

            List<ColumnStat> targetColumns = stats;

            if (targetColumns.size() > 200) targetColumns = targetColumns.subList(0, 200);

            Set<String> usedColNames = new HashSet<>(java.util.Arrays.asList("id", "create_time", "is_deleted", "extra_data", "w_insert_dt", "w_update_dt", "load_user", "job_instance"));

            // 专家增强：精确一对一全量识别并跳过（仅在 kvPairEnabled 开启时生效）
            Map<String, String> kwPairs = new HashMap<>();

            Map<String, List<Integer>> groupedBySuffix = new HashMap<>();

            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("(.*)(\\d+)$");

            if (kvPairEnabled) {

                kwPairs = configService.getKwPairs();

                if (kwPairs.isEmpty()) {

                    kwPairs = new HashMap<>();

                    kwPairs.put("description", "amount");

                    kwPairs.put("desc", "amt");

                    kwPairs.put("name", "price");

                    kwPairs.put("type", "val");

                    kwPairs.put("key", "value");

                    kwPairs.put("item", "total");

                    kwPairs.put("label", "val");

                    kwPairs.put("msg", "count");

                }

                for (int i = 0; i < stats.size(); i++) {

                    String hHeader = stats.get(i).headerName.trim();

                    java.util.regex.Matcher m = numPattern.matcher(hHeader);

                    if (m.matches()) {

                        String suffix = m.group(2);

                        groupedBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(i);

                    }

                }

            }

            for (int i = 0; i < targetColumns.size(); i++) {

                ColumnStat s = targetColumns.get(i);

                // 只有当该列命中了定义的精确对，且组内确实存在“另一半”时，才视作技术列跳过

                java.util.regex.Matcher m = numPattern.matcher(s.headerName.trim());

                if (m.matches()) {

                    String baseName = m.group(1).toLowerCase().trim();

                    String suffix = m.group(2);

                    List<Integer> siblings = groupedBySuffix.get(suffix);

                    boolean isVerifiedPair = false;

                    for (Map.Entry<String, String> pair : kwPairs.entrySet()) {

                        String k = pair.getKey().toLowerCase();

                        String v = pair.getValue().toLowerCase();

                        if (baseName.endsWith(k) || baseName.endsWith(v)) {

                            // 检查伴随基因是否存在
                            String targetSibling = baseName.endsWith(k) ? v : k;

                            for (int siblingIdx : siblings) {

                                String sName = stats.get(siblingIdx).headerName.toLowerCase().replaceAll("\\d+$", "").trim();

                                if (sName.endsWith(targetSibling)) {

                                    isVerifiedPair = true;

                                    break;

                                }

                            }

                        }

                        if (isVerifiedPair) break;

                    }

                    if (isVerifiedPair) {

                        anyPairFound = true;

                        continue;

                    }

                }

                FieldDef def = new FieldDef();

                def.setName(s.headerName);

                // 列名生成

                String baseColName = generateDwColumnName(s.headerName, s.colIndex);

                String finalColName = baseColName;

                int suffix = 1;

                while (usedColNames.contains(finalColName)) {

                    finalColName = baseColName + "_" + suffix++;

                }

                usedColNames.add(finalColName);

                def.setColumnName(finalColName);

                // 类型探测

                def.setType("input");

                if (smartType && s.nonBlankCount > 0) {

                    if (s.couldBeDate) {

                        def.setType("datetime");

                    } else if (s.couldBeNumber) {

                        def.setType("number");

                    } else if (s.uniqueValues.size() > 1 && s.uniqueValues.size() <= 10 && (double)s.nonBlankCount / s.uniqueValues.size() > 1.5) {

                        // 如果去重值少0个且存在明显重复（平均每个值出现超.5次）

                        def.setType("select");

                        def.setOptions(new ArrayList<>(s.uniqueValues));

                    } else if (s.nonBlankCount > 0 && s.rawValues.stream().anyMatch(v -> v.length() > 50)) {

                        def.setType("textarea");

                    }

                }

                // 启发式：默认不设为必填（Excel 数据通常不均衡），仅前几个设为可筛                def.setRequired(false);

                def.setFilterable(i < 5 && s.nonBlankCount > 0);

                fields.add(def);

            }

        }

        if (anyPairFound) {

            FieldDef jsonDef = new FieldDef();

            jsonDef.setName("额外业务数据 (JSON)");

            jsonDef.setColumnName("extra_data");

            jsonDef.setType("textarea"); // 预设为大文本/JSON格式展示

            jsonDef.setRequired(false);

            jsonDef.setFilterable(false);

            fields.add(jsonDef);

        }

        return fields;

    }

    private String generateDwColumnName(String originalName, int colIndex) {

        if (originalName == null || originalName.trim().isEmpty()) {

            return "field_" + (colIndex + 1);

        }

        // 读取列名生成规范配置

        Map<String, Object> namingConf = configService.getNamingConvention();

        String prefix = (String) namingConf.getOrDefault("column_prefix", "field_");

        int threshold = namingConf.containsKey("initials_threshold") ? ((Number) namingConf.get("initials_threshold")).intValue() : 4;

        int maxLen = namingConf.containsKey("max_length") ? ((Number) namingConf.get("max_length")).intValue() : 50;

        String regex = (String) namingConf.getOrDefault("replace_regex", "[\\s\\[\\]\\(\\)（）【】]");

        String numericPrefix = (String) namingConf.getOrDefault("numeric_prefix", "col_");

        String pinyinSeparator = (String) namingConf.getOrDefault("pinyin_separator", "_");

        int bracketEngMinLen = namingConf.containsKey("bracket_eng_min_len") ? ((Number) namingConf.get("bracket_eng_min_len")).intValue() : 2;

        String dictMatchMode = (String) namingConf.getOrDefault("dict_match_mode", "contains");

        // 1. 尝试提取括号/方括号中的纯英文字段(例如 "[Create Time]")

        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("[\\[\\(（【]([a-zA-Z\\s_]+)[\\]\\)）】]").matcher(originalName);

        if (m1.find()) {

            String eng = m1.group(1).trim();

            if (eng.length() >= bracketEngMinLen) {

                return toSnakeCase(eng);

            }

        }

        // 2. 如果字符串里本来就包含一段连续英连续两个字母以上)，优先使用这段英文提        // 例如 "创建时间 Create Time" -> "create_time"

        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("([a-zA-Z][a-zA-Z\\s_]{1,}[a-zA-Z])").matcher(originalName);

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

        if (str == null || str.isEmpty()) return "";

        String s = str.trim().replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();

        s = s.replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_");

        if (s.isEmpty()) return "";

        if (Character.isDigit(s.charAt(0))) {

            s = "col_" + s;

        }

        return s;

    }

}

