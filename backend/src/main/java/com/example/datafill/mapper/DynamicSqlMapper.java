package com.example.datafill.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DynamicSqlMapper {
    
    // 执行建表 DDL, 删除表等 (DDL 不能用 SELECT 标签，必须用 UPDATE，因为没有返回值)
    @Update("${sql}")
    void executeDdl(@Param("sql") String sql);
}
