# MySQL8 单项增量补丁：销售退货退款账户列

## 版本与完整性

- 唯一版本：`V20261120_01`；文件：`V20261120_01__sale_return_refund_account_id.sql`。
- SHA-256（文件原始字节，UTF-8 / LF，无 BOM）：
  `2e2ebfb2330161943f9a392c3f46fc679b1246d8c4a129654d74a2a8dfcf41fa`。
- 同目录 `.sql.sha256` 是标准 `sha256sum` 格式。更改任何字节（含换行）都会变更校验和；
  先核验再执行，不应对已验收版本就地修改；后续修订另给版本。
- 这不是 Flyway/Liquibase 自动接入，也不创建/补齐整套历史迁移表。本次不修改初始化脚本、Java、
  容器挂载或业务配置。`patches/` 不会随已有数据卷自动执行；本次**未部署**。
- 有权限的未来变更窗口应在外部变更单记录：版本、预期/实际 SHA-256、目标标识、MySQL 版本、
  执行时间/人、退出码、验收结果；不是仅凭文件名宣称旧库已经升级。

## 变更与幂等契约

1. 前提：已明确选定目标 schema、MySQL >= 8.0.12、InnoDB 的 `biz_sale_return` 已存在。
   新库列已由主线 `deploy/mysql/init/04-erp-schema.sql` 提供，不再执行全量初始化。
2. 仅当缺列时追加 `refund_account_id BIGINT NULL DEFAULT NULL`，注释与新库相同。
   为适配较早 MySQL8 的 INSTANT 限制，旧库列追加在表尾，不要求与新库物理列顺序相同。
3. 列已是**有符号 BIGINT、可空、默认 NULL、普通可见非生成列**时完整脚本为无损 no-op；
   接受不影响语义的旧显示宽度，不强改已有注释或物理列序。
4. 列为 INT/VARCHAR、UNSIGNED/ZEROFILL、NOT NULL、非空默认值、生成列或 INVISIBLE 等均
   `SIGNAL SQLSTATE '45000'`（错误码 1644），拒绝静默转换/覆盖。缺表、视图、非 InnoDB 同样拒绝。
5. 没有 UPDATE/INSERT/DELETE、没有退款账户推断、没有关联收款表，也无历史回填。
   旧记录新增列读为 NULL；其余原有列（含 update_time）必须逐值保持不变。
6. 强制 `ALGORITHM=INSTANT`，不退回 COPY/INPLACE；表/版本/行版本限制不满足时直接失败，
   不以重建表替代。`lock_wait_timeout=10` 限制元数据锁等待，并在正常/异常出口恢复原值。
   不承诺无元数据锁、零延迟、大表/高并发兼容性；此类情况另需容量与窗口评估。

## 执行与失败边界（操作说明，不是本次部署授权）

- 必须逐语句遇错即停、串行、独立连接、明确选库、UTF-8；不要用 `mysql --force`、自动跳错或
  依赖框架事务回滚 DDL。文件含 mysql 客户端 `DELIMITER`，通用驱动需要按该分隔符拆分。
- 账户至少需目标表 ALTER、目标 schema 的 CREATE ROUTINE / ALTER ROUTINE / EXECUTE 及元数据
  可见性。过程声明 `SQL SECURITY INVOKER`，不写 DEFINER，不申请全局权限。
- 暂存过程 `erp_patch_v20261120_01_refund_account` 是本补丁保留名；脚本不会先 DROP 同名对象。
  若名称已占用，CREATE 即失败，必须调查，不能自动覆盖未知过程。
- 正常执行末尾会删除暂存过程。CALL 拒绝/失败且客户端遇错即停时，过程可能遗留；确认是该版本
  创建、定义/校验和符合后，才可在同一专用 schema 单独清理：
  `DROP PROCEDURE erp_patch_v20261120_01_refund_account;`。不要盲目删除同名未知过程。
- ALTER 是隐式提交且不可随事务回滚；即使客户端在 ALTER 成功后中断，也应先核查列属性/记录，
  清理确认属于本补丁的暂存过程后重跑。重复执行只检查列，不改写任何现存账户值。
- 不提供自动 DROP COLUMN 回滚：可能丢失升级后已录入的退款账户，需另行审批。

## 本次专项验收（仅隔离测试）

独立测试：`integration-tests/verify_refund_account_migration.py`，不启动 Spring/Redis，不运行 Maven。
它校验 SQL SHA-256 和语句形状后，**只替换目标表和暂存过程两个标识符**，映射至本次随机
`erp_audit_refund_<随机值>_...` 对象，过程正文/条件/DDL 不改；新表 DDL 只读提取主线初始化文件
中的这一个 CREATE TABLE，旧表仅删除目标列，绝不导入整个初始化脚本。所有创建对象登记所有权，
无条件 finally 仅清理该次登记对象，复核没有前缀遗留，不 DROP DATABASE/不停止共享隔离实例。

从工作区根目录（不是 work 根目录）运行：

```bash
python.exe .erp-remediation/2026-11-20/work/integration-tests/verify_refund_account_migration.py --static
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py status
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py exec -- python.exe .erp-remediation/2026-11-20/work/integration-tests/verify_refund_account_migration.py
```

先阅读工作区 `audit-tools/erp-remediation/environment/README.md` 与本副本
`integration-tests/README.md` 安全契约。动态测试仅接受包装器注入的
`ERP_AUDIT_REAL_MYSQL=1`、`127.0.0.1:43306`、`erp_audit_*` 专用 schema 和专用凭据，缺失即拒绝，
不读取环境外配置/线上地址/业务表，测试结果不包含用户名或密码。

实际结果及未覆盖项见 `integration-tests/refund-account-migration-acceptance.md` 和
`integration-tests/refund-account-migration-latest.json`；未生成通过证据前不能宣称动态验收通过。
