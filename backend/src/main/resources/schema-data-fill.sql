-- 数据填报系统元数据及邮件/审批相关表结构初始化脚本（PostgreSQL）
-- 会在应用启动时自动执行（见 application.yml 中 spring.sql.init 配置）

-- 1. 表单元数据表：data_fill_form
CREATE TABLE IF NOT EXISTS data_fill_form (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255),
    table_name      VARCHAR(255),
    table_comment   TEXT,
    folder_id       VARCHAR(64),
    forms           TEXT,

    -- 新增字段：状态 / 截止时间 / 提醒天数 / 提醒策略 / 每月日 / 每周几 / 提醒时间 / 收件人邮箱 / 填报周期（天）/ 允许填报用户
    status          VARCHAR(32) DEFAULT 'ACTIVE',
    deadline        TIMESTAMP,
    reminder_days   NUMERIC(5,2),
    reminder_mode   VARCHAR(32),
    monthly_day     INTEGER,
    weekly_day_of_week INTEGER,
    reminder_time   VARCHAR(8),
    reminder_date_time TIMESTAMP,
    deadline_monthly_day INTEGER,
    deadline_weekly_day_of_week INTEGER,
    deadline_time   VARCHAR(10),
    recipient_emails  TEXT,
    cycle_days        INTEGER,
    fill_user_emails  TEXT,
    fill_departments  TEXT,
    reference_template_config TEXT,
    creator         VARCHAR(100),
    hard_delete     BOOLEAN DEFAULT FALSE,

    schema_name     VARCHAR(100) DEFAULT 'public',
    group_tag       VARCHAR(255),
    is_external     BOOLEAN DEFAULT FALSE,
    description     TEXT,
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    insert_dt_column VARCHAR(100),
    update_dt_column VARCHAR(100),
    delete_flag_column VARCHAR(100),
    allow_add       BOOLEAN DEFAULT TRUE,
    allow_delete    BOOLEAN DEFAULT TRUE,
    allow_export    BOOLEAN DEFAULT TRUE,
    default_filter_policy VARCHAR(50) DEFAULT 'FIRST_THREE',
    UNIQUE (schema_name, table_name)
);

-- 兼容已有老表：为缺失字段补列
ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'ACTIVE';

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS deadline TIMESTAMP;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS reminder_days NUMERIC(5,2);
    
-- 强制执行：将旧版本的数据表设计中已经生成的 INTEGER 类型强制转为浮点，否则界面虽然传3.6，到层底会自动被 PGSQL 隐式进位转成4
ALTER TABLE data_fill_form ALTER COLUMN reminder_days TYPE NUMERIC(5,2);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS reminder_mode VARCHAR(32);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS monthly_day INTEGER;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS weekly_day_of_week INTEGER;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS reminder_time VARCHAR(8);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS reminder_date_time TIMESTAMP;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS deadline_monthly_day INTEGER;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS deadline_weekly_day_of_week INTEGER;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS deadline_time VARCHAR(10);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS recipient_emails TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS creator VARCHAR(100);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS cycle_days INTEGER;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS fill_user_emails TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS fill_departments TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS kv_config TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS table_comment TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS reference_template_config TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS folder_id VARCHAR(64);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS schema_name VARCHAR(100) DEFAULT 'public';

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS hard_delete BOOLEAN DEFAULT FALSE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS group_tag VARCHAR(255);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS is_external BOOLEAN DEFAULT FALSE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS description TEXT;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS pk_column VARCHAR(100) DEFAULT 'id';

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS insert_dt_column VARCHAR(100);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS update_dt_column VARCHAR(100);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS delete_flag_column VARCHAR(100);

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS allow_add BOOLEAN DEFAULT TRUE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS allow_edit BOOLEAN DEFAULT TRUE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS allow_delete BOOLEAN DEFAULT TRUE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS allow_export BOOLEAN DEFAULT TRUE;

ALTER TABLE data_fill_form
    ADD COLUMN IF NOT EXISTS default_filter_policy VARCHAR(50) DEFAULT 'FIRST_THREE';

-- 修正唯一性约束：从单一 table_name 改为 (schema_name, table_name) 复合约束
-- 1. 移除旧的单列唯一约束
ALTER TABLE data_fill_form DROP CONSTRAINT IF EXISTS data_fill_form_table_name_key;
-- 2. 创建复合唯一索引（IF NOT EXISTS 保证幂等，等效于复合唯一约束）
CREATE UNIQUE INDEX IF NOT EXISTS idx_data_fill_form_schema_table_unique ON data_fill_form (schema_name, table_name);

CREATE INDEX IF NOT EXISTS idx_data_fill_form_folder_id ON data_fill_form(folder_id);


-- 1.5 模板目录表：data_fill_folder
CREATE TABLE IF NOT EXISTS data_fill_folder (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    parent_id       VARCHAR(64),
    sort_order      INTEGER DEFAULT 0,
    creator         VARCHAR(100),
    create_time     TIMESTAMP,
    update_time     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_data_fill_folder_parent_id ON data_fill_folder(parent_id);


-- 2. 邮件通知表：email_notification（对应 EmailNotification 实体）
CREATE TABLE IF NOT EXISTS email_notification (
    id               VARCHAR(64) PRIMARY KEY,
    form_id          VARCHAR(64),
    recipient_email  VARCHAR(255),
    notification_type VARCHAR(64),    -- REMINDER / DEADLINE_WARNING / APPROVAL_REQUEST
    subject          VARCHAR(255),
    content          TEXT,
    status           VARCHAR(32),     -- PENDING / SENT / FAILED
    scheduled_time   TIMESTAMP,
    sent_time        TIMESTAMP,
    error_message    TEXT,

    create_time      TIMESTAMP,
    update_time      TIMESTAMP
);


-- 3. 管理员审批表：admin_approval（对应 AdminApproval 实体）
CREATE TABLE IF NOT EXISTS admin_approval (
    id               VARCHAR(64) PRIMARY KEY,
    form_id          VARCHAR(64),
    data_id          VARCHAR(64),
    applicant_email  VARCHAR(255),
    applicant_name   VARCHAR(255),
    reason           TEXT,
    status           VARCHAR(32),      -- PENDING / APPROVED / REJECTED
    approver_email   VARCHAR(255),
    approval_comment TEXT,
    applied_time     TIMESTAMP,
    approved_time    TIMESTAMP,

    create_time      TIMESTAMP,
    update_time      TIMESTAMP
);

-- 4. 用户填报日志表：user_fill_log（记录谁在什么时间填过哪条数据）
CREATE TABLE IF NOT EXISTS user_fill_log (
    id           VARCHAR(64) PRIMARY KEY,
    form_id      VARCHAR(64),
    data_id      VARCHAR(64),
    user_email   VARCHAR(255),
    submit_time  TIMESTAMP,

    create_time  TIMESTAMP,
    update_time  TIMESTAMP
);

-- 4.6 用户填报状态快照表：user_completion_snapshot（用于加速任务列表查询）
CREATE TABLE IF NOT EXISTS user_completion_snapshot (
    user_email      VARCHAR(255),
    form_id         VARCHAR(64),
    last_submit_time TIMESTAMP,
    count           INTEGER DEFAULT 0,
    update_time     TIMESTAMP,
    PRIMARY KEY (user_email, form_id)
);
CREATE INDEX IF NOT EXISTS idx_ucs_user_email ON user_completion_snapshot(user_email);




-- 4.5 零侵入操作日志表：operation_log（专门记录单行增删改与批量导入）
CREATE TABLE IF NOT EXISTS operation_log (
    id             VARCHAR(64) PRIMARY KEY,
    form_id        VARCHAR(64),
    user_email     VARCHAR(255),
    operation_type VARCHAR(64), -- ADD, UPDATE, DELETE, UPLOAD
    operation_desc TEXT,
    create_time    TIMESTAMP
);


-- 5. 系统全局配置表：system_config（用于存储数仓字典、识别关键字等）
CREATE TABLE IF NOT EXISTS system_config (
    id           SERIAL PRIMARY KEY,
    config_key   VARCHAR(100) UNIQUE,
    config_value TEXT,
    remark       VARCHAR(255)
);

-- 初始化默认配置数据
INSERT INTO system_config (config_key, config_value, remark) VALUES 
('dw_dict', '{"创建时间":"ctime","添加时间":"ctime","更新时间":"utime","修改时间":"mtime","创建人":"creator","修改人":"modifier","操作人":"operator","状态":"status","备注":"remark","描述":"desc","详情":"detail","部门":"dept","公司":"company","企业":"company","机构":"org","组织":"org","员工":"emp","人员":"person","姓名":"name","名称":"name","标题":"title","电话":"phone","手机":"mobile","联系方式":"contact","邮箱":"email","金额":"amount","价钱":"price","价格":"price","单价":"price","花费":"cost","成本":"cost","数量":"qty","数目":"count","次数":"times","日期":"date","时间":"time","总计":"total","合计":"total","总额":"total_amt","订单号":"order_no","单号":"order_no","序列号":"serial_no","编号":"no","类型":"type","类别":"category","分类":"category","级别":"level","等级":"level","地址":"address","位置":"location","密码":"password","账号":"account","用户":"user","角色":"role","权限":"permission","省份":"province","城市":"city","区县":"district","年份":"year","月份":"month","年龄":"age","性别":"gender","身份证":"id_card","比例":"ratio","百分比":"percent","是否":"is_flag"}', '数仓中英文映射字典'),
('kw_pairs', '{"description":"amount","desc":"amt","name":"price","type":"val","key":"value","item":"total"}', '精确一对一配对特征库'),
('naming_convention', '{"column_prefix":"field_","initials_threshold":4,"max_length":50,"replace_regex":"[\\\\s\\\\[\\\\]\\\\(\\\\)（）【】]"}', '数仓列名生成规范配置')
ON CONFLICT (config_key) DO NOTHING;

-- 强制将 allow_export 默认值设为 TRUE，确保后续不管是系统内还是数据库自动创建记录均默认为 TRUE
ALTER TABLE data_fill_form ALTER COLUMN allow_export SET DEFAULT TRUE;
