package com.example.datafill.service;

import com.example.datafill.dto.FieldDef;

import com.example.datafill.entity.DataFillForm;

import com.example.datafill.mapper.DataFillFormMapper;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.apache.poi.ss.usermodel.Cell;

import org.apache.poi.ss.usermodel.Row;

import org.apache.poi.ss.usermodel.Sheet;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.util.IOUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.OutputStream;
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

@Slf4j
@Service
@RequiredArgsConstructor

public class ExcelService {

    private final DataFillFormMapper formMapper;

    private final DynamicDataDmlService dataDmlService;

    private final ObjectMapper objectMapper;

    private final JdbcTemplate jdbcTemplate;

    private final SystemConfigService configService;

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

    /**
     * 6. 将 Excel 数据批量导入物理表
     * 优化：元数据计算外提，分片分批入库
     */
    @Transactional
    public int importData(String formId, MultipartFile file, String mode, String creator) throws IOException {
        DataFillForm form = formMapper.selectById(formId);
        if (form == null) throw new RuntimeException("表单不存在");

        String tableName = form.getTableName();
        if ("overwrite".equals(mode)) {
            jdbcTemplate.update("UPDATE \"" + tableName + "\" SET is_deleted = 1, w_update_dt = NOW()");
        }

        List<FieldDef> fields;
        try {
            fields = objectMapper.readValue(form.getForms(), new TypeReference<List<FieldDef>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("表单解析错误", e);
        }

        Map<String, String> headerMap = new HashMap<>();
        for (FieldDef f : fields) {
            if (f.getColumnName() != null) headerMap.put(f.getColumnName().trim(), f.getColumnName());
            if (f.getName() != null) {
                headerMap.put(f.getName().trim(), f.getColumnName());
                headerMap.put(f.getName().replaceAll("[\\r\\n]+", "").trim(), f.getColumnName());
            }
        }

        int totalCount = 0;
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return 0;
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return 0;

            int lastCol = headerRow.getLastCellNum();
            String[] headers = new String[lastCol];
            boolean isTemplate = false;
            for (int i = 0; i < lastCol; i++) {
                Cell c = headerRow.getCell(i);
                if (c != null) {
                    headers[i] = c.toString().trim();
                    final String hn = headers[i];
                    if (fields.stream().anyMatch(f -> hn.equals(f.getColumnName()))) isTemplate = true;
                }
            }

            Map<String, List<Integer>> groups = new HashMap<>();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(.*?)(\\d*)$");
            for (int c = 0; c < lastCol; c++) {
                if (headers[c] == null) continue;
                java.util.regex.Matcher m = pattern.matcher(headers[c].trim());
                if (m.matches()) {
                    String s = m.group(2); if (s.isEmpty()) s = "0";
                    groups.computeIfAbsent(s, k -> new ArrayList<>()).add(c);
                }
            }

            Map<String, String> kwPairs = new HashMap<>();
            kwPairs.put("description", "amount"); kwPairs.put("desc", "amt");
            kwPairs.put("name", "price"); kwPairs.put("type", "val");
            kwPairs.put("key", "value"); kwPairs.put("item", "total");
            kwPairs.put("label", "val"); kwPairs.put("msg", "count");
            kwPairs.putAll(configService.getKwPairs());

            int startRow = isTemplate ? 2 : 1;
            int lastRow = sheet.getLastRowNum();
            int BATCH_SIZE = 1000;
            List<Map<String, Object>> buffer = new ArrayList<>(BATCH_SIZE);

            for (int r = startRow; r <= lastRow; r++) {
                Row row = sheet.getRow(r); if (row == null) continue;
                Map<String, Object> rowData = new LinkedHashMap<>();
                Map<String, Object> extra = new LinkedHashMap<>();
                boolean empty = true;
                Set<Integer> consumed = new HashSet<>();

                for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                    List<Integer> idxs = entry.getValue(); if (idxs.size() < 2) continue;
                    Integer foundK = null, foundV = null;
                    outer:
                    for (Map.Entry<String, String> pair : kwPairs.entrySet()) {
                        String tk = pair.getKey().toLowerCase(), tv = pair.getValue().toLowerCase();
                        Integer fk = null, fv = null;
                        for (Integer idx : idxs) {
                            String name = headers[idx].toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\s]+$", "");
                            if (name.endsWith(tk)) fk = idx; else if (name.endsWith(tv)) fv = idx;
                        }
                        if (fk != null && fv != null) { foundK = fk; foundV = fv; break outer; }
                    }
                    if (foundK != null && foundV != null) {
                        Cell kc = row.getCell(foundK), vc = row.getCell(foundV);
                        String kv = (kc == null) ? null : kc.toString().trim();
                        Object vv = (vc == null) ? null : (vc.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC ? vc.getNumericCellValue() : vc.toString().trim());
                        if (kv != null && !kv.isEmpty() && vv != null && !"".equals(vv)) {
                            extra.put(kv, vv); consumed.add(foundK); consumed.add(foundV);
                        }
                    }
                }

                for (int c = 0; c < lastCol; c++) {
                    if (consumed.contains(c) || headers[c] == null) continue;
                    Cell cell = row.getCell(c); if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
                    Object val = switch (cell.getCellType()) {
                        case STRING -> cell.getStringCellValue();
                        case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell) ? cell.getDateCellValue() : cell.getNumericCellValue();
                        case BOOLEAN -> cell.getBooleanCellValue();
                        default -> cell.toString();
                    };
                    if (val != null && !"".equals(val)) {
                        empty = false;
                        String dbCol = headerMap.get(headers[c]);
                        if (dbCol == null) dbCol = headerMap.get(headers[c].replaceAll("[\\r\\n]+", ""));
                        if (dbCol != null) rowData.put(dbCol, val);
                        else extra.put(generateDwColumnName(headers[c], c), val);
                    }
                }
                if (empty && extra.isEmpty()) continue;
                if (!extra.isEmpty()) rowData.put("extra_data", extra);
                if (creator != null && !creator.isBlank()) rowData.put("creator", creator);
                buffer.add(rowData);

                if (buffer.size() >= BATCH_SIZE) {
                    dataDmlService.batchInsertRowData(formId, buffer);
                    totalCount += buffer.size(); buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                dataDmlService.batchInsertRowData(formId, buffer);
                totalCount += buffer.size(); buffer.clear();
            }
        }
        return totalCount;
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
            // 内置默认值始终加载，用户自定义配对覆盖/追加
            Map<String, String> kwPairs = new HashMap<>();

            Map<String, List<Integer>> groupedBySuffix = new HashMap<>();
            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("(.*?)(\\d*)$");

            if (kvPairEnabled) {
                kwPairs.put("description", "amount");
                kwPairs.put("desc", "amt");
                kwPairs.put("name", "price");
                kwPairs.put("type", "val");
                kwPairs.put("key", "value");
                kwPairs.put("item", "total");
                kwPairs.put("label", "val");
                kwPairs.put("msg", "count");
                kwPairs.putAll(configService.getKwPairs());

                log.info("[KV-Pair] 启用键值对模式, 有效配对规则: {}", kwPairs);

                for (int i = 0; i < stats.size(); i++) {
                    String hHeader = stats.get(i).headerName.trim();
                    java.util.regex.Matcher sm = numPattern.matcher(hHeader);
                    if (sm.matches()) {
                        String suffix = sm.group(2);
                        if (suffix.isEmpty()) suffix = "0";
                        groupedBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(i);
                    }
                }
                log.info("[KV-Pair] 按数字后缀分组结果: {} 组", groupedBySuffix.size());
            }

            for (int i = 0; i < targetColumns.size(); i++) {

                ColumnStat s = targetColumns.get(i);

                // 只有当该列命中了定义的精确对，且组内确实存在“另一半”时，才视作技术列跳过

                java.util.regex.Matcher m = numPattern.matcher(s.headerName.trim());
                if (m.matches()) {
                    String suffix = m.group(2);
                    if (suffix.isEmpty()) suffix = "0";
                    String baseName = m.group(1).toLowerCase().trim().replaceAll("[_\\s]+$", "");
                    
                    List<Integer> siblings = groupedBySuffix.get(suffix);
                    boolean isVerifiedPair = false;
                    if (siblings != null) {
                        for (Map.Entry<String, String> pair : kwPairs.entrySet()) {
                            String k = pair.getKey().toLowerCase();
                            String v = pair.getValue().toLowerCase();

                            if (baseName.endsWith(k) || baseName.endsWith(v)) {
                                // 检查伴随基因是否存在
                                String targetSibling = baseName.endsWith(k) ? v : k;
                                for (int siblingIdx : siblings) {
                                    String sName = stats.get(siblingIdx).headerName.toLowerCase().trim()
                                            .replaceAll("\\d+$", "")
                                            .replaceAll("[_\\s]+$", "");
                                    if (sName.endsWith(targetSibling)) {
                                        isVerifiedPair = true;
                                        break;
                                    }
                                }
                            }
                            if (isVerifiedPair) break;
                        }
                    }

                    if (isVerifiedPair) {
                        anyPairFound = true;
                        log.debug("[KV-Pair] 列 '{}' 被识别为键值对成员，将归集到 extra_data JSON", s.headerName);
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
                def.setDbType("VARCHAR(255)");

                if (smartType && s.nonBlankCount > 0) {
                    if (s.couldBeDate) {
                        def.setType("datetime");
                        def.setDbType("TIMESTAMP");
                    } else if (s.couldBeNumber) {
                        def.setType("number");
                        def.setDbType("INTEGER");
                    } else if (s.nonBlankCount > 0 && s.rawValues.stream().anyMatch(v -> v.length() > 50)) {
                        def.setType("textarea");
                        def.setDbType("TEXT");
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

