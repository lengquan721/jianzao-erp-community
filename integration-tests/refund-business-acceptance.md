# 受控退款：独立真实 MySQL 业务集成验收

**通过：10/10，无失败、无跳过。** 2026-11-20 04:47:26 UTC，Windows JBR 21.0.11 / Connector/J 9.7.0 / MySQL 8.0.44 InnoDB，实例端点仅 `127.0.0.1:43306`。`javac=0`、Java=0、包装器=0；固定 fixture 表创建及所有权清理均为 7/7，无遗留、无清理错误。没有运行 Maven，也不把 JDBC 冒烟或退款列迁移专项当业务验收。

## 实际验证结果

| 检查 | 本次结果 |
|---|---|
| 固定表已存在 | 在 7 张 fixture 已存在时，以另一个 runner 所有权重新走预检：检出全部 7 个名称，拒绝创建/删除，原有 fixture 未变。主运行的初始预检没有冲突。 |
| `MANDATORY` | 对有效待退款单发起退款、对有效已关联退货单发起撤回，分别在无外层事务时由 Spring 抛 `IllegalTransactionStateException`；无业务写入或取号。 |
| 受控权限 | 合成同租户身份但不给确认/撤回菜单权限，真实 `ConfirmPermission` + `SaPermissionImpl` + Sa-Token 拒绝两入口，未动账、未取号。 |
| 合法退款原子提交 | 原 `SALE` 应收已核销 `25.00 → 0.00`，状态变 `OPEN`；正数面额 + `negative=1` 的 `CONFIRMED` 单、恰好一条指向原 `SALE` 应收的分配、负数退货 AR 与退货链接同事务提交。提交前独立连接看不到这些变更。 |
| 正确关联撤回 | 原 `SALE` 已核销 `100.00 → 75.00 → 100.00`、最终 `SETTLED`；退款收款单 `VOID`，保留分配和审计字段，负数退货 AR 作废，fixture 链接清空。另用同客户、账号、金额但异 `SALE` 的错误链接，及普通收款撤回入口验证拒绝、无改动。 |
| 超额退款 | 请求 `50.00`，退货总额 `80.00`，原单已核销 `40.00`；实际拒绝原因为超过**已核销金额**。事务内先看到 `DRAFT` 及分配；即使调用方捕获业务异常，外层仍被标记 rollback-only，尝试提交抛 `UnexpectedRollbackException`。业务全表快照相同，含逻辑删除行检查，不残留草稿/分配。 |
| 财务修改后的注错 | 退款和撤回各一次：同事务已观察到原应收、红字收款/分配、负数台账、外部链接全部已变更，才在调用方注入异常；独立会话提交前不可见，失败后六张业务表全行/全列快照与事前一致。退款故障后同一来源可正常重试。 |
| 同一退货单并发退款 | 独立事务连接 57、59，观察连接 20。持有首笔未提交事务时，两次在 MySQL `SHOW FULL PROCESSLIST` 看到竞争者正在执行生产的退货来源 `SELECT ... FOR UPDATE`。提交后竞争者在锁内重读以 ERP `40902` 拒绝，不接受 SQL 超时/死锁当通过。`70.00 → 50.00` 仅一次，收款/分配/退货台账各仅新增一行。 |
| 同一退货单并发撤回 | 独立事务连接 63、64、观察 20，同样观测到两次生产锁定读。仅一次回补 `50.00 → 70.00`、红字收款单 `VOID`，竞争者因不再有可撤回的已确认关联以 `40902` 拒绝。金额刻意保留重复退款/回补的数值空间，不靠余额不足制造拒绝。 |
| 跨租户 | B 可真实退款且可读自己的来源、账户、台账；读 A 来源/账户/台账/红字收款和分配不可见，生产 mapper 对 A 来源/账户/台账试写返回 0 行；尝试跨租户退款、撤回、引用 A 账号或 A 原销售、直接反核销 A 台账均拒绝，业务表不变；无租户线程也 fail closed。 |

生产 `SequenceService` 未替换：`sys_sequence` 实际响应 `REQUIRES_NEW`，取得 `SK` 单号并验证可在外层提交前见到。失败退款会消耗一个号，这是生产独立编号事务的预期行为，不是业务表部分提交；六表全量快照特意不含 `sys_sequence`，另有独立计数断言。

## 真实与替代的边界

- Java 程序化创建 `MybatisSqlSessionFactoryBean`/`SqlSessionTemplate`，`SpringManagedTransactionFactory`，同一数据源的 `DataSourceTransactionManager`；CGLIB `ProxyFactory` + `TransactionInterceptor` + `AnnotationTransactionAttributeSource` 调用当前工作树的 `FinReceiptServiceImpl`、`FinArApServiceImpl`、`SequenceService`。生产 `PlusTenantLineHandler`，真实 `TenantHelper.dynamic` 在线程显式设置并自动恢复。没有 mock 账务、mapper、DB 或事务。
- 使用真实 Sa-Token JWT 逻辑、生产 `LoginHelper`、`SaPermissionImpl` 及服务权限校验；**用户、菜单权限、请求上下文和会话 DAO 是本进程 fixture**。不覆盖生产用户认证、HTTP/Controller、权限下发、Redis 或分布式会话。
- 只合成客户、账户、退货来源和原销售 ID；用实际 `FinArApServiceImpl` 创建/核销原 `SALE` 台账、创建/作废 `SALE_RETURN` 台账。外部退货状态和链接由测试调用方编排，经实际 `ErpSaleReturnMapper` 更新。**未调用 `ErpSaleReturnServiceImpl`，未验证库存、可退数量、订单进度、完整 trade 链路、银行出款、账户余额查询、全量 Schema/迁移或部署，不是端到端验收。**

## 可复现命令与安全范围

从 `D:/rosoft2023/DEAV-AI开发` 的 Git Bash 执行：

```bash
# 本次公开状态检查 exit 0：专用实例 ready/running，不启动或停机
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py status

# Bash 语法检查 exit 0；缺 opt-in 的 runner 自身 exit 2，未从 build-env 启动任何子测试
S=.erp-remediation/2026-11-20/work/integration-tests/run-refund-business.sh
bash -n "$S"
(unset ERP_AUDIT_REAL_MYSQL; /usr/bin/bash "$S"; code=$?; test "$code" -eq 2)

# 实际业务运行 exit 0：10/10 PASS；退出时清理 7/7
python.exe audit-tools/erp-remediation/environment/isolated_mysql.py exec -- bash .erp-remediation/2026-11-20/work/integration-tests/run-refund-business.sh
```

补证：`python.exe -B -I -c` 以 `runpy.run_path` 仅调用 runner 的纯 `contract()`，对 11 组不合规值全部拒绝（opt-in、DNS 别名、端口、库名、URL 别名、URL 额外代理参数、驱动、用户名、空密码、代理环境变量、JVM 覆盖变量）；exit 0，无 JVM/网络。独立脚本只读复核最终报告，46 个输入 SHA-256 与当前文件都相同、无 runner 编译临时目录。首次用 `env -u ... bash` 做外层 opt-in 检查未收到 runner 拒绝且返回 0，外层断言 exit 1，后续 `&& exec` 未执行；以明确的 `/usr/bin/bash` 复核为 exit 2，不把首次探针计为通过。

包装器 `exec` 发送 env-only 凭据；自身正常核对专用实例身份。验收器不直接读取私密 state、bootstrap 或私密日志，不打印密码、用户名、Java classpath 或 Surefire 全量属性。Shell、Python、Java 按层 fail closed；驱动连接前校验 opt-in、字面量端点、专用库/账号形态、完整 URL 和驱动名，连接后再核对服务身份、库与 schema-only grants。source 工作区 `build-env.sh` 后立即清理代理，保留 `-Djava.io.tmpdir=C:/Windows/Temp -Xms32m -Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC`，不加载 app profile。程序化读取 fin 最新 `TEST-org.erp.fin.service.impl.FinReceiptServiceImplTest.xml` 中唯一的 `java.class.path` 属性用于现有依赖，再以 `javac` 将选定的生产当前源码编入独立临时输出优先加载；运行前后核对源码指纹。不写 Maven 或原业务仓的目标目录。

仅预置 7 张经对照审阅的最小 InnoDB 表，不载入业务库、整份初始化 SQL、样本备份。预检所有固定名（含视图，任一冲突就拒绝整个 fixture 初始化）；`CREATE TABLE` 不用 `IF NOT EXISTS`；成功创建即登记拥有的表及随机所有权标记，finally 只删除标记仍一致的已登记表并复查。`exec` 负责串行化同类受控运行；该流程不宣称能处理外部进程绕过包装器恶意并发 DDL 或强杀 JVM，下一次发现固定同名对象也会拒绝，而不是盲删。

## 产物与副作用

目录（绝对路径）：`D:/rosoft2023/DEAV-AI开发/.erp-remediation/2026-11-20/work/integration-tests/`。

- [RefundBusinessMySql.java](RefundBusinessMySql.java)：`:124-238` 为程序化 MyBatis、事务代理和身份 fixture；`:278-295` 为退货调用方编排；`:406-729` 为所有实际断言及锁等待观测。
- [RefundAuditDatabase.java](RefundAuditDatabase.java)：`:34-49` 完整连接合同；`:75-110` 最小 fixture DDL；`:143-203` 预检/创建及授权核验；`:219-242` 所有权清理。
- [run-refund-business.sh](run-refund-business.sh)、[run_refund_business.py](run_refund_business.py)：安全启动、classpath/当前源码、脱敏与报告；作用范围只在验收目录临时编译/写报告。没有改任何业务 main/test 源码、POM、依赖、配置或原仓，也未 stage/commit。
- [refund-business-latest.json](refund-business-latest.json)：`:7-34` 环境及零遗留，`:36-134` 10 项结果，`:161-215` classpath 来源、全部源码指纹、退出码与 UTC；不含凭据或真实库名。
- 本文件 `refund-business-acceptance.md`：复现命令、结果与范围。官方包装器按原合同维护自身私有生命周期锁/子进程记录与临时捕获输出；本次只复用已有隔离实例，未 stop/destroy，未修改包装器或读取其私有文件。
