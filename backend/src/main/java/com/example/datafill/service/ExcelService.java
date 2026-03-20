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
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.github.pjfanning.xlsx.StreamingReader;
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
        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(1000)
                .bufferSize(131072)
                .open(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return 0;

            java.util.Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) return 0;

            Row headerRow = rowIterator.next();
            if (headerRow == null) return 0;

            int lastCol = headerRow.getLastCellNum();
            String[] headers = new String[lastCol];
            boolean isTemplate = false;
            org.apache.poi.ss.usermodel.DataFormatter dataFormatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 0; i < lastCol; i++) {
                Cell c = headerRow.getCell(i);
                if (c != null) {
                    headers[i] = dataFormatter.formatCellValue(c).trim();
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

            List<com.example.datafill.dto.ExcelParseResult.DetectedPair> savedPairs = new ArrayList<>();
            if (form.getKvConfig() != null && !form.getKvConfig().isBlank()) {
                try {
                    savedPairs = objectMapper.readValue(form.getKvConfig(), new TypeReference<List<com.example.datafill.dto.ExcelParseResult.DetectedPair>>() {});
                } catch (Exception e) {}
            }



            int startRow = isTemplate ? 2 : 1;
            int BATCH_SIZE = 2000;
            List<Map<String, Object>> buffer = new ArrayList<>(BATCH_SIZE);

            String[] cachedPinyinHeaders = new String[lastCol];
            String[] cachedDbCols = new String[lastCol];
            boolean[] isDateColumn = new boolean[lastCol];
            boolean[] dateColumnChecked = new boolean[lastCol];
            for (int c = 0; c < lastCol; c++) {
                if (headers[c] != null) {
                    cachedPinyinHeaders[c] = generateDwColumnName(headers[c], c);
                    String dbCol = headerMap.get(headers[c]);
                    if (dbCol == null) dbCol = headerMap.get(headers[c].replaceAll("[\\r\\n]+", ""));
                    cachedDbCols[c] = dbCol;
                }
            }

            record KVPairConfig(int fk, int fv, String targetJsonCol) {}
            List<KVPairConfig> activeKVPairs = new ArrayList<>();

            for (Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                List<Integer> idxs = entry.getValue(); if (idxs.size() < 2) continue;
                String suffix = entry.getKey();
                Integer foundK = null, foundV = null;
                String targetJsonCol = "extra_data";

                if (!savedPairs.isEmpty()) {
                    for (com.example.datafill.dto.ExcelParseResult.DetectedPair sp : savedPairs) {
                        if (sp.getSuffixes().contains(suffix)) {
                            Integer fk = null, fv = null;
                            for (Integer idx : idxs) {
                                String h = headers[idx];
                                if (headerMap.containsKey(h.trim()) || headerMap.containsKey(h.replaceAll("[\\r\\n]+", "").trim())) continue;
                                String name = h.toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\s]+$", "");
                                if (name.equals(sp.getKeyBase().toLowerCase())) fk = idx;
                                else if (name.equals(sp.getValueBase().toLowerCase())) fv = idx;
                            }
                            if (fk != null && fv != null) { 
                                foundK = fk; foundV = fv; 
                                targetJsonCol = sp.getSuggestedColumnName() != null ? sp.getSuggestedColumnName() : "extra_data";
                                break; 
                            }
                        }
                    }
                }

                if (foundK != null && foundV != null) {
                    activeKVPairs.add(new KVPairConfig(foundK, foundV, targetJsonCol));
                }
            }

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                if (row == null || row.getRowNum() < startRow) continue;
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
                            vvObj = dataFormatter.formatCellValue(vc).trim();
                        }
                    }
                    if (kvStr != null && !kvStr.isEmpty() && vvObj != null && !"".equals(vvObj)) {
                        dynamicExtras.computeIfAbsent(pc.targetJsonCol(), k -> new LinkedHashMap<>()).put(kvStr, vvObj);
                        consumed.add(pc.fk()); consumed.add(pc.fv());
                    }
                }

                for (int c = 0; c < lastCol; c++) {
                    if (consumed.contains(c) || headers[c] == null) continue;
                    Cell cell = row.getCell(c); if (cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK) continue;
                    Object val;
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        val = cell.getStringCellValue();
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                        if (!dateColumnChecked[c]) {
                            isDateColumn[c] = org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell);
                            dateColumnChecked[c] = true;
                        }
                        val = isDateColumn[c] ? cell.getDateCellValue() : cell.getNumericCellValue();
                    } else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BOOLEAN) {
                        val = cell.getBooleanCellValue();
                    } else {
                        val = dataFormatter.formatCellValue(cell).trim();
                    }
                    if (val != null && !"".equals(val)) {
                        empty = false;
                        String dbCol = cachedDbCols[c];
                        if (dbCol != null) rowData.put(dbCol, val);
                        else defaultExtra.put(cachedPinyinHeaders[c], val);
                    }
                }
                
                if (empty && dynamicExtras.isEmpty() && defaultExtra.isEmpty()) continue;
                
                // 合并所有其它的到 extra_data
                if (!defaultExtra.isEmpty()) {
                    dynamicExtras.computeIfAbsent("extra_data", k -> new LinkedHashMap<>()).putAll(defaultExtra);
                }
                
                // 将所有 JSON 列转换为字符串并加入 rowData
                for (Map.Entry<String, Map<String, Object>> exEntry : dynamicExtras.entrySet()) {
                    try {
                        rowData.put(exEntry.getKey(), objectMapper.writeValueAsString(exEntry.getValue()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        rowData.put(exEntry.getKey(), "{}");
                    }
                }
                
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

    public com.example.datafill.dto.ExcelParseResult parseExcelHeaders(MultipartFile file, String mode, boolean smartType, boolean kvPairEnabled) throws IOException {
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
            if (sheet == null) return result;

            java.util.Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) return result;

            Row headerRow = rowIterator.next();
            if (headerRow == null) return result;

            int lastColumn = headerRow.getLastCellNum();
            if (lastColumn <= 0) return result;

            int scanLimit = 100;

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

            List<String> originalHeaders = new ArrayList<>();
            List<ColumnStat> stats = new ArrayList<>();
            for (int c = 0; c < lastColumn; c++) {
                Cell cell = headerRow.getCell(c);
                String name = (cell == null) ? "" : cell.toString().trim();
                originalHeaders.add(name);
                if (name.isEmpty()) name = "未命名字段" + (c + 1);
                stats.add(new ColumnStat(c, name));
            }
            result.setOriginalHeaders(originalHeaders);

            int rowCount = 0;
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                rowCount++;
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
                if (rowCount >= scanLimit) break;
            }

            result.setTotalColumns(stats.size());
            List<ColumnStat> targetColumns = stats;
            if (targetColumns.size() > 1000) {
                targetColumns = targetColumns.subList(0, 1000);
                result.setTruncated(true);
            }

            Set<String> usedColNames = new HashSet<>(java.util.Arrays.asList("id", "create_time", "is_deleted", "extra_data", "w_insert_dt", "w_update_dt", "load_user", "job_instance"));

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

                for (int i = 0; i < stats.size(); i++) {
                    String hHeader = stats.get(i).headerName.trim();
                    java.util.regex.Matcher sm = numPattern.matcher(hHeader);
                    if (sm.matches()) {
                        String suffix = sm.group(2);
                        groupedBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(i);
                    }
                }
            }

            for (int i = 0; i < targetColumns.size(); i++) {
                ColumnStat s = targetColumns.get(i);
                java.util.regex.Matcher m = numPattern.matcher(s.headerName.trim());
                if (m.matches()) {
                    String suffix = m.group(2);
                    String baseName = m.group(1).toLowerCase().trim().replaceAll("[_\\s]+$", "");
                    
                    List<Integer> siblings = groupedBySuffix.get(suffix);
                    boolean isVerifiedPair = false;
                    String matchedK = null; String matchedV = null;

                    if (kvPairEnabled && siblings != null) {
                        for (Map.Entry<String, String> pair : kwPairs.entrySet()) {
                            String k = pair.getKey().toLowerCase();
                            String v = pair.getValue().toLowerCase();

                            if (baseName.endsWith(k) || baseName.endsWith(v)) {
                                String targetBase = baseName.endsWith(k) ? k : v;
                                String otherBase = baseName.endsWith(k) ? v : k;
                                String prefix = baseName.substring(0, baseName.length() - targetBase.length());
                                
                                for (int siblingIdx : siblings) {
                                    String sName = stats.get(siblingIdx).headerName.toLowerCase().trim()
                                            .replaceAll("\\d+$", "")
                                            .replaceAll("[_\\s]+$", "");
                                    if (sName.equals(prefix + otherBase)) {
                                        isVerifiedPair = true;
                                        matchedK = baseName.endsWith(k) ? baseName : sName;
                                        matchedV = baseName.endsWith(v) ? baseName : sName;
                                        break;
                                    }
                                }
                            }
                            if (isVerifiedPair) break;
                        }
                    }

                    if (isVerifiedPair) {
                        final String kBase = matchedK; final String vBase = matchedV; final String suff = suffix;
                        com.example.datafill.dto.ExcelParseResult.DetectedPair existing = potentialPairs.stream()
                            .filter(p -> p.getKeyBase().equals(kBase) && p.getValueBase().equals(vBase))
                            .filter(p -> {
                                boolean existingIsNoNum = p.getSuffixes().isEmpty() || p.getSuffixes().get(0).isEmpty();
                                boolean currentIsNoNum = suff.isEmpty();
                                return existingIsNoNum == currentIsNoNum;
                            })
                            .findFirst().orElse(null);
                        
                        if (existing == null) {
                            existing = new com.example.datafill.dto.ExcelParseResult.DetectedPair();
                            existing.setKeyBase(kBase); existing.setValueBase(vBase);
                            existing.setKeyIndices(new ArrayList<>()); existing.setValueIndices(new ArrayList<>());
                            existing.setSuffixes(new ArrayList<>());
                            existing.setDisplayName(kBase + "/" + vBase);
                            existing.setSuggestedColumnName(deriveJsonColumnName(kBase, vBase));
                            potentialPairs.add(existing);
                        }
                        if (!existing.getSuffixes().contains(suff)) existing.getSuffixes().add(suff);
                        String bName = s.headerName.toLowerCase().trim().replaceAll("\\d+$", "").replaceAll("[_\\s]+$", "");
                        if (bName.endsWith(kBase)) existing.getKeyIndices().add(i);
                        else if (bName.endsWith(vBase)) existing.getValueIndices().add(i);
                        continue; 
                    }
                }

                FieldDef def = new FieldDef();
                def.setName(s.headerName);
                String baseColName = generateDwColumnName(s.headerName, s.colIndex);
                String finalColName = baseColName;
                int suffixVal = 1;
                while (usedColNames.contains(finalColName)) {
                    finalColName = baseColName + "_" + suffixVal++;
                }
                usedColNames.add(finalColName);
                def.setColumnName(finalColName);
                def.setType("input"); def.setDbType("VARCHAR(255)");
                if (smartType && s.nonBlankCount > 0) {
                    if (s.couldBeDate) { def.setType("datetime"); def.setDbType("TIMESTAMP"); }
                    else if (s.couldBeNumber) { def.setType("number"); def.setDbType("INTEGER"); }
                    else if (s.rawValues.stream().anyMatch(val -> val.length() > 50)) { def.setType("textarea"); def.setDbType("TEXT"); }
                }
                def.setRequired(false);
                def.setFilterable(i < 5 && s.nonBlankCount > 0);
                fields.add(def);
            }
        }
        // 5. 最终名称完善 (增加范围提示)
        for (com.example.datafill.dto.ExcelParseResult.DetectedPair p : potentialPairs) {
            String range = formatSuffixRange(p.getSuffixes());
            String suffixDesc = range.isEmpty() ? "无编号" : range;
            // 此时 p.getKeyBase() 已经是带前缀的全名了，如 tracking_id_charge_description
            p.setDisplayName(p.getKeyBase() + "/" + p.getValueBase().replace(p.getKeyBase().replaceAll("[^_]+$", ""), "") + " (" + suffixDesc + ")");
        }

        return result;
    }

    private String formatSuffixRange(List<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty()) return "";
        if (suffixes.size() == 1 && suffixes.get(0).isEmpty()) return "";
        
        List<Integer> nums = new ArrayList<>();
        boolean hasEmpty = false;
        for (String s : suffixes) {
            if (s.isEmpty()) { hasEmpty = true; continue; }
            try { nums.add(Integer.parseInt(s)); } catch (Exception e) {}
        }
        if (nums.isEmpty()) return hasEmpty ? "无编号" : "";
        
        java.util.Collections.sort(nums);
        int start = nums.get(0);
        int end = nums.get(nums.size() - 1);
        String range = (start == end) ? String.valueOf(start) : start + "-" + end;
        if (hasEmpty) range = "无编号, " + range;
        return range;
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

    private String deriveJsonColumnName(String k, String v) {
        String sk = k.toLowerCase().replace("/territory", "").replace("#", "").replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_");
        String sv = v.toLowerCase().replace("/territory", "").replace("#", "").replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_");

        int minLen = Math.min(sk.length(), sv.length());
        int prefixLen = 0;
        for (int i = 0; i < minLen; i++) {
            if (sk.charAt(i) == sv.charAt(i)) prefixLen++;
            else break;
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

