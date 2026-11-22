# O05：部署只读冒烟与独立业务验收

## 旧入口已退役

`deploy/debian-native/` 和 `upload/scripts/` 下的 `08-smoke-test.sh`、`09-smoke-test-v2.sh` 均只向 stderr 输出退役说明并退出 `64`。即使传入旧参数、环境变量或强制选项，也不会登录、采集凭据、发请求或创建/清理数据，不会转调新探针。不要把旧退出码当作通过；删除原调用并按下述门禁分层迁移，不承诺原行为或输出兼容。

## HTTP 存活探针：仅对受控的后端监听

在候选根目录，使用 Python 3（仅标准库）。运维迁移时，先由验收启动方在专用测试容器/隔离环境中创建并确认持有后端监听，再将该监听端口显式传给探针。不从既有部署、生产转发、业务数据环境或已有服务端口取值。端口范围约束不是监听归属或环境隔离证明；无法确认监听由本次验收持有就不要运行。没有独立后端时，用下文自测在一次性桩上验证探针行为。

```bash
python3 -I -B deploy/debian-native/smoke-readonly.py --fixture-port "$O05_FIXTURE_PORT"
```

`O05_FIXTURE_PORT` 是上面示例命令的调用方变量，不是探针读取的环境配置。探针只接受一个 `--fixture-port PORT`，范围为 `49152..65535`，固定连接 `127.0.0.1`；没有默认端口、URL、主机、请求路径或凭据可配置。不使用代理、Cookie、授权头、重定向或重试。

唯一请求是 `GET /`，对应后端 `IndexController` 的非 SPA 欢迎语分支（隔离目标工作目录不要含 `app/dist/index.html`，直接指向后端而非前端/Nginx）。逐项检查 HTTP **200**、单一 `text/plain; charset=UTF-8`、未压缩且完整的响应体（最多 1024 字节），以及 `欢迎使用<应用名>后台管理框架，请通过前端地址访问。` 完整形状。应用名须为非空短标识（最多 128 字符，字母/数字/下划线/点/连字符/普通空格）。SPA、登录页、业务 JSON、重定向均不是通过；也不据此判断数据库或业务就绪。单次 I/O 超时 1 秒，探针总等待 3 秒；不记录响应内容。没有登录、验证码、健康管理端点或业务路由。

| exit | 结果 |
| --- | --- |
| `0` | 固定 GET 的所有校验成功 |
| `1` | 连接、超时、状态或响应契约失败；stderr `FAIL` |
| `2` | 参数拒绝，未连接；stderr `REFUSED` |
| `64` | 旧入口退役，未连接；stderr `REFUSED` |

## 登录身份与事务：独立真实 fixture，不是上述探针

在另行授权的隔离验收中，按 `integration-tests/README.md` 的专用 MySQL 和所有权约束使用 `integration-tests/run-refund-business.sh`（真实 `LoginHelper` / Sa-Token 的进程内合成登录身份、权限与财务事务）和 `integration-tests/run-trade-return-integration.sh`（真实服务/Mapper 的销售退货、退款、库存、订单联动及整链回滚）。这些 runner 不证明 Web `/auth/login` 用户名密码认证、生产身份或完整部署联通；若需 Web 登录验收，需要独立隔离认证 fixture，不要把登录塞回只读探针。O05 本次未运行这些 runner。

## 离线自测

```bash
python3 -I -B deploy/debian-native/test_smoke_readonly.py -v
```

标准库测试加 Bash 退役断言。测试桩只在测试进程中临时绑定独占的新高位回环端口、处理一次请求后关闭；不使用现有监听、不需启动 ERP 或数据库。参数拒绝用无网络断言，退役脚本先精确校验为惰性存根才执行。
