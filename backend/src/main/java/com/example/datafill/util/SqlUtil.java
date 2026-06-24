package com.example.datafill.util;

public class SqlUtil {
    public static String quoteTable(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            return "\"" + parts[0] + "\".\"" + parts[1] + "\"";
        }
        return "\"" + tableName + "\"";
    }
    
    public static String extractSchema(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            return tableName.split("\\.", 2)[0];
        }
        return null;
    }
    
    public static String extractTable(String tableName) {
        if (tableName == null) return null;
        if (tableName.contains(".")) {
            return tableName.split("\\.", 2)[1];
        }
        return tableName;
    }
}
