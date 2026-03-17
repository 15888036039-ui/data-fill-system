package com.example.datafill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("system_config")
public class SystemConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    // 配置键，如 dw_dict, key_keywords, val_keywords
    private String configKey;
    
    // 配置值，通常存 JSON 字符串
    private String configValue;
    
    private String remark;
}
