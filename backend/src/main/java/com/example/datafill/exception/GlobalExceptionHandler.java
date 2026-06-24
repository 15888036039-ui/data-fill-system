package com.example.datafill.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<?> handleAppException(AppException e) {
        return ResponseEntity.status(e.getCode()).body(java.util.Collections.singletonMap("message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralException(Exception e) {
        log.error("Unhandled system error", e);
        String rawMessage = e.getMessage() != null ? e.getMessage() : "未知系统错误";
        String finalMessage = rawMessage;

        // 针对 PostgreSQL 常见物理报错的中文转换
        if (rawMessage.contains("Duplicate entry") || rawMessage.contains("duplicate key")) {
            finalMessage = "数据冲突：该记录已存在（主键或唯一约束冲突）";
        } else if (rawMessage.contains("numeric field overflow")) {
            finalMessage = "数值超出允许范围（请确认数字精度或大小是否超限）";
        } else if (rawMessage.contains("too long for type character") || rawMessage.contains("value too long")) {
            finalMessage = "内容超长：填入的内容超出了数据库列定义的长度限额";
        } else if (rawMessage.contains("violates not-null constraint") || rawMessage.contains("NULL value")) {
            finalMessage = "必填字段缺失：请确保所有非空字段都有正确内容";
        } else if (rawMessage.contains("invalid input syntax") || rawMessage.contains("输入语法") || (rawMessage.contains("invalid input value") && rawMessage.contains("column"))) {
            String errorData = "未知内容";
            String lineNo = "未知";
            
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("invalid input syntax.*?\"([^\"]*)\"").matcher(rawMessage);
                if (m.find()) {
                    errorData = m.group(1);
                } else {
                    java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("invalid input value.*?\"([^\"]*)\"").matcher(rawMessage);
                    if (m2.find()) {
                        errorData = m2.group(1);
                    }
                }
                
                java.util.regex.Matcher mLine = java.util.regex.Pattern.compile("line (\\d+)").matcher(rawMessage);
                if (mLine.find()) {
                    lineNo = mLine.group(1);
                }
            } catch (Exception ignored) {}
            
            if (!"未知".equals(lineNo) && !"未知内容".equals(errorData)) {
                finalMessage = String.format("数据格式不匹配：检测到导入的 第 %s 行数据存在格式问题 (异常内容：\"%s\")。请检查对应列是否为正确的日期格式或常规文本，切勿混用。", lineNo, errorData);
            } else {
                finalMessage = "数据格式不匹配：检测到内容与所在列要求的格式不符（例如在数字列/日期列填入了常规文字）。请检查是否误将表头也选入，或尝试重新【下载并使用标准模板】填入数据。";
            }
        } else if (rawMessage.contains("relname") || rawMessage.contains("already exists")) {
            finalMessage = "物理表名冲突：该表名已被占用或包含非法字符";
        } else if (rawMessage.contains("relation") && rawMessage.contains("does not exist")) {
            finalMessage = "物理表不存在：关联数据库表已丢失或尚未创建";
        } else if (rawMessage.contains("Exception") || rawMessage.contains("java.lang") || rawMessage.contains("org.springframework")) {
            // 如果是没有被特殊处理的底层报错代码，不直白抛给用户，避免看不懂
            finalMessage = "系统异常，请联系管理员或稍后重试。";
        }

        return ResponseEntity.status(500).body(java.util.Collections.singletonMap("message", finalMessage));
    }
}
