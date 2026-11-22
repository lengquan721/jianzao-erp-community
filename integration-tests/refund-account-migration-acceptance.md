# 退款账户列：增量迁移专项验收（独立短里程碑）

## 结论

**已验证：仅退款列的增量脚本在真实隔离 MySQL 8.0.44 上通过。未部署，未连接线上，未运行 Maven。**

- 版本 `V20261120_01`，SQL 原始字节 SHA-256：
  `2e2ebfb2330161943f9a392c3f46fc679b1246d8c4a129654d74a2a8dfcf41fa`。
- 最终动态验收 UTC `2026-11-20T03:32:19.645685+00:00`，`passed=true`，73 个布尔检查全部通过；
  完整脚本执行 16 次（4 次成功/无损 no-op、12 次预期拒绝）。
- 旧表先持久化 3 条 DRAFT/CONFIRMED/VOID 记录，执行两次后，新增列均为 NULL，
  原有每一个字段（含金额、状态、关联退款单、create_time/update_time、逻辑删除标记）及列属性不变。
- 主线新表 DDL 建表后也执行两次：列/表定义不变，3 条记录及既存账户 NULL、7007、
  `9007199254740993` 均保留；后者验证 BIGINT 不被测试器截成浮点数。
- 9 种异常列形态（VARCHAR、INT、UNSIGNED、ZEROFILL、NOT NULL、非 NULL 默认值、
  STORED/VIRTUAL 生成列、INVISIBLE）均实际返回 1644，行值和表定义不变；
  缺表、MyISAM、视图另有 3 项预期拒绝。`SQLSTATE '45000'` 由 SQL 声明静态核实，
  PyMySQL 动态断言错误码 1644 与该版本错误前缀，并非声称驱动提供了 SQLSTATE 字段。
- 本次所有权前缀 `erp_audit_refund_a6e140acfdfd`，自建表/视图/过程均清理，
  `cleanup_errors=[]`、`remaining_run_owned_objects=0`；正常/异常路径均恢复 session lock_wait_timeout。

## 文件与定位（绝对路径）

1. 新增 SQL：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/deploy/mysql/patches/V20261120_01__sale_return_refund_account_id.sql:41-59`
   检查有符号 BIGINT / 可空 / 默认 NULL / 普通列，不兼容即拒绝。
   同文件 `:61-65` 是唯一表变更：

   ```sql
   ALTER TABLE `biz_sale_return`
       ADD COLUMN `refund_account_id` BIGINT NULL DEFAULT NULL
           COMMENT '草稿及确认时选定的退款结算账户',
       ALGORITHM=INSTANT;
   ```

2. 新增校验和清单：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/deploy/mysql/patches/V20261120_01__sale_return_refund_account_id.sql.sha256:1`。
3. 新增版本/执行/失败边界说明：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/deploy/mysql/patches/README.md:5-44`。
   明确手工版本，不接入全套迁移框架；串行、选库、遇错即停，DDL 隐式提交；
   拒绝/中断后可能遗留专用暂存过程，须确认所有权后清理，不覆盖未知过程，不提供危险 DROP COLUMN 回滚。
4. 新增独立专项验收器：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/integration-tests/verify_refund_account_migration.py:54-80`
   校验 SHA-256、解析 4 条 SQL，仅读取主线的这一张表 DDL；`:83-107` 连接前端点拒绝；
   `:122-164` 随机前缀/创建成功后登记所有权；`:216-255` 只映射目标表/过程名称并执行实际 SQL；
   `:259-345` 正反场景；`:347-364` finally 清理及无遗留复核；`:397-417` 唯一连接工厂。
5. 新增最终动态证据：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/integration-tests/refund-account-migration-latest.json:2-81`
   版本、校验和、通过标志与 73 个检查；`:93-108` 旧/新表记录前后指纹相等；
   `:110-170` 12 项拒绝；`:172-178` 实际端点、MySQL8.0.44、16 次执行、零遗留。
6. 新增本验收报告：
   `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/integration-tests/refund-account-migration-acceptance.md:1`。

只读参照、**不是本子任务改动**：
`D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/deploy/mysql/init/04-erp-schema.sql:338-371`，
其 `:351` 已有主线新增列。旧表假设为这份 CREATE TABLE 仅移除该列，其他表结构不变；
不声称覆盖所有历史发行版的旧表差异。

记录指纹（非 SQL 文件校验和）：

- 旧表原列，迁移前 = 两次执行后：
  `583b2d8d96c2cf06ddfe339909a93294e4e48631ec0fbf9e453f67026d45f99f`。
- 新表全列，两次执行前后：
  `85364e4e3fda4611548924c8f07f8fb34f76df8c48726aedcce589efdf54ee83`。
- 判断先逐值直接比较，再输出规范化快照 SHA-256 供审计；不是仅比较行数。

## 安全范围及实际命令

工作目录 `D:/rosoft2023/DEAV-AI开发`（Git Bash）。已先读安全契约：

- `D:/rosoft2023/DEAV-AI开发/audit-tools/erp-remediation/environment/README.md:53-59,73-102`。
- `D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/integration-tests/README.md:24-39,49-66`。

所有动态连接均经既有 `isolated_mysql.py exec`；由包装器先核对实例/PID/数据目录/UUID，
环境仅注入专用凭据并剥离业务连接及代理，测试器再次验证字面量端点/库名前缀后才连接。
实际为 `127.0.0.1:43306 / erp_audit_d52d01c012b0ffe2 / PID 18192`；没有读 state.json、
打印凭据、导入业务配置/整套初始化 SQL，亦没有触碰隔离库内实际业务表。
仅在目标 SQL 的目标表和暂存过程两个名称处做前缀映射；逻辑/类型判定/INSTANT DDL 完全不变。

### 已执行命令与退出结果

```bash
# 前后各一次，均 exit 0：ready/running=true，同一 schema 和 PID 18192
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py status

# 初验及修正测试器后各一次，均 exit 0：SQL/sidecar SHA256、4 条语句、8 项非法端点拒绝
python.exe .erp-remediation/2026-11-20/work/integration-tests/verify_refund_account_migration.py --static

# 共执行两轮（说明见下文），最终 exit 0，16 次补丁执行，73 检查均通过，零遗留
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py exec -- python.exe .erp-remediation/2026-11-20/work/integration-tests/verify_refund_account_migration.py

# exit 0，输出 V20261120_01__sale_return_refund_account_id.sql: OK
cd .erp-remediation/2026-11-20/work/deploy/mysql/patches && sha256sum -c V20261120_01__sale_return_refund_account_id.sql.sha256
```

只读审查命令均 exit 0：

```bash
git -C .erp-remediation/2026-11-20/work status --short -- deploy/mysql integration-tests
git -C .erp-remediation/2026-11-20/work diff --check -- deploy/mysql/patches integration-tests
python.exe -c 'import hashlib,pathlib; p=pathlib.Path(".erp-remediation/2026-11-20/work/deploy/mysql/patches/V20261120_01__sale_return_refund_account_id.sql"); print(hashlib.sha256(p.read_bytes()).hexdigest()+"  "+p.name)'
```

`git diff --check` 针对 tracked 差异；本次新增文件尚未 stage，不将此命令当作新增 SQL 的动态验收。
本任务未 commit/stage。首次动态执行 exit 1 的原因是**验收器把 list 与 PyMySQL 的 tuple 比较**，
导致“原列属性未变”误报；该轮旧行逐值、NULL 新列检查本已通过，finally 清理成功。只修正验收器
为 tuple 对 tuple 后重跑通过，SQL 文件从生成到最终验收字节/校验和没有修改。最新 JSON 为最终轮结果，
不把首次未完成场景误记为通过。

## 副作用清单与未验证项

- 持久新增的交付文件仅上述 6 个，全部在 work 的 `deploy/mysql/patches/`、`integration-tests/`；
  不编辑 init 04、Java、既有 integration-tests/README、原仓或环境包装器源码。
- 两轮 exec 仅在既有专用隔离库创建随机前缀的测试表/视图/过程并插入人工 fixture，均已清理；
  包装器自动更新私有生命周期锁/临时目录与
  `D:/rosoft2023/DEAV-AI开发/audit-tools/erp-remediation/environment/.runtime/test-child-latest.json`
  子进程账本（最终 finished/exit 0），私有捕获输出日志按包装器约定删除，未读/导出私有状态或凭据。
- 复用中的隔离实例留运行，不 stop/destroy，避免影响主智能体其他专项测试；没有启动新的服务器、
  修改服务/防火墙、线上部署、真实业务数据写入，也没有 Maven/Spring/Redis 执行。
- **未验证**：线上升级、全套历史迁移链、除 MySQL 8.0.44 以外的具体补丁版、大表/MDL 争用/
  并发 DDL、锁等待超时故障注入、进程中断恢复、ERP 退款业务端到端/完整后端启动、最小权限矩阵。
  强制 INSTANT，不支持的表/版本应停机调查而非隐式 COPY；未来部署还需单独授权与实际环境验收。
