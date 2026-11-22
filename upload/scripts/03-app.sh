#!/usr/bin/env bash
# ============================================================
# 简造ERP社区版 · Debian 12 原生部署 ③ 应用 + Nginx
#
# 前置：/opt/erp/app.jar、/opt/erp/frontend-dist/ 已上传
#
# 执行：bash 03-app.sh '<ERP_DB_PASSWORD>' '<JWT_SECRET>' '<PUBLIC_ORIGIN>'
#   JWT_SECRET   : openssl rand -hex 32
#   PUBLIC_ORIGIN: http://113.45.68.193
# ============================================================
set -euo pipefail

# ---- 环境守卫：必须在服务器（Linux/root/已装基础环境）上执行 ----
_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

ERP_DB_PASSWORD="${1:?用法: bash 03-app.sh <erp数据库密码> <JWT密钥> <公网IP或域名>}"
JWT_SECRET="${2:?缺少 JWT 密钥，用 openssl rand -hex 32 生成}"
PUBLIC_ORIGIN="${3:?缺少公网访问地址，如 http://113.45.68.193}"

# 监控账号：SecurityConfig.getSaServletFilter() 对 /actuator/** 做 HTTP Basic 鉴权，
# 用户名口令取自 spring.boot.admin.client.username/password。
# 仓库默认 ruoyi/123456 会随包发布，公网部署必须覆盖。
MONITOR_USER="erpmon"
MONITOR_PASS="$(openssl rand -hex 16)"

[ -f /opt/erp/app.jar ] || { echo "❌ /opt/erp/app.jar 不存在" >&2; exit 1; }
[ -d /opt/erp/frontend-dist ] || { echo "❌ /opt/erp/frontend-dist 不存在" >&2; exit 1; }
[ ${#JWT_SECRET} -ge 32 ] || { echo "❌ JWT 密钥太短，至少 32 字符" >&2; exit 1; }

echo "==> [1/6] 配置 Redis"
REDIS_PASS=$(openssl rand -hex 24)
cat > /etc/redis/redis-erp.conf <<EOF
# 简造ERP 专用配置
include /etc/redis/redis.conf
bind 127.0.0.1
protected-mode yes
port 6379
requirepass ${REDIS_PASS}
maxmemory 64mb
maxmemory-policy allkeys-lru
save ""
appendonly no
EOF
chown redis:redis /etc/redis/redis-erp.conf
# Debian 默认 redis 实例与 ERP 实例抢 6379，先停掉
systemctl disable --now redis-server >/dev/null 2>&1 || true

cat > /etc/systemd/system/redis-erp.service <<EOF
[Unit]
Description=Redis for Jianzao ERP
After=network.target

[Service]
Type=notify
User=redis
Group=redis
ExecStart=/usr/bin/redis-server /etc/redis/redis-erp.conf --supervised systemd
ExecStop=/usr/bin/redis-cli -a '${REDIS_PASS}' --no-auth-warning shutdown
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable redis-erp >/dev/null
systemctl restart redis-erp
sleep 2
redis-cli -a "${REDIS_PASS}" --no-auth-warning ping

echo "==> [2/6] 配置应用环境变量"
cat > /etc/default/erp <<EOF
# 简造ERP 应用环境变量
SPRING_PROFILES_ACTIVE=prod

# ---- 数据源（覆盖 prod 内置的 localhost:3306/ry-vue）----
SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL=jdbc:mysql://127.0.0.1:3306/erp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false&rewriteBatchedStatements=true&nullCatalogMeansCurrent=true
SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME=erp
SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD=${ERP_DB_PASSWORD}

# ---- Redis ----
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASS}

# ---- 安全基线 ----
# prod profile 不提供默认值，缺失直接启动失败
SA_TOKEN_JWT_SECRET_KEY=${JWT_SECRET}
SECURITY_CORS_ALLOWED_ORIGINS=${PUBLIC_ORIGIN}
# /actuator/** 的 HTTP Basic 账号（默认 ruoyi/123456 必须覆盖）
SPRING_BOOT_ADMIN_CLIENT_USERNAME=${MONITOR_USER}
SPRING_BOOT_ADMIN_CLIENT_PASSWORD=${MONITOR_PASS}

# ---- 文件上传临时目录 ----
# prod profile 硬编码 /jianzao/server/temp，目录不存在时上传直接抛异常
SPRING_SERVLET_MULTIPART_LOCATION=/opt/erp/temp

# ---- 2C2G 内存约束：堆 448M + Metaspace 128M ≈ 650M 常驻 ----
JAVA_TOOL_OPTIONS=-Xms256m -Xmx448m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -Xss512k

# ---- 关闭用不到的模块 ----
SPRING_BOOT_ADMIN_CLIENT_ENABLED=false
SPRINGDOC_API_DOCS_ENABLED=false
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health
MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never
CAPTCHA_ENABLE=false
API_DECRYPT_ENABLED=false

# ---- 线程池收敛 ----
SERVER_UNDERTOW_THREADS_IO=2
SERVER_UNDERTOW_THREADS_WORKER=16
SPRING_DATASOURCE_DYNAMIC_HIKARI_MAXPOOLSIZE=6
SPRING_DATASOURCE_DYNAMIC_HIKARI_MINIDLE=2
REDISSON_THREADS=2
REDISSON_NETTYTHREADS=4
REDISSON_SINGLESERVERCONFIG_CONNECTIONMINIMUMIDLESIZE=2
REDISSON_SINGLESERVERCONFIG_CONNECTIONPOOLSIZE=8
REDISSON_SINGLESERVERCONFIG_SUBSCRIPTIONCONNECTIONPOOLSIZE=4

TZ=Asia/Shanghai
EOF
chmod 600 /etc/default/erp

cat > /etc/systemd/system/erp.service <<'EOF'
[Unit]
Description=Jianzao ERP Application
After=network.target mysql.service redis-erp.service
Wants=mysql.service redis-erp.service

[Service]
Type=simple
User=erp
Group=erp
WorkingDirectory=/opt/erp
EnvironmentFile=/etc/default/erp
ExecStart=/usr/bin/java $JAVA_TOOL_OPTIONS -Djava.io.tmpdir=/opt/erp/temp -jar /opt/erp/app.jar
# OOM 时直接退出交 Restart，不留半死进程占端口
Restart=always
RestartSec=10
LimitNOFILE=65535
TimeoutStartSec=300
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable erp >/dev/null
echo "    监控账号：${MONITOR_USER} / ${MONITOR_PASS}（健康检查用）"

echo "==> [3/6] 配置 Nginx"

# ---- 端口必须避开已有服务 ----
# 服务器上可能已有其他 Web 应用占用 80。直接 listen 80 default_server 会抢占它们，
# 且删 sites-enabled/default 会连带干掉原有站点。这里改为：
#   1) 自动探测一个空闲端口（默认 8081）
#   2) 绝不设置 default_server
#   3) 绝不删除或改动任何已有的站点配置
ERP_HTTP_PORT="${ERP_HTTP_PORT:-8081}"
port_busy() { ss -tln 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }

if port_busy "$ERP_HTTP_PORT"; then
  echo "    端口 ${ERP_HTTP_PORT} 已被占用，自动另选"
  for p in $(seq 8081 8099); do
    if ! port_busy "$p"; then ERP_HTTP_PORT="$p"; break; fi
  done
fi
echo "    ERP 将监听 ${ERP_HTTP_PORT}（80 端口及其他站点保持不动）"

if [ "$ERP_HTTP_PORT" = "80" ]; then
  echo "❌ 不允许占用 80：那会与其他站点冲突。请显式指定其他端口。" >&2
  exit 1
fi

# 站点配置写到独立文件名，不与已有配置混淆
cat > /etc/nginx/sites-available/erp-8081 <<EOF
# 简造ERP社区版 —— 独立端口 :${ERP_HTTP_PORT}，不占用 80，不设 default_server
limit_req_zone \$binary_remote_addr zone=erp_login:10m rate=20r/m;
limit_req_status 429;

server {
    listen ${ERP_HTTP_PORT};
    listen [::]:${ERP_HTTP_PORT};
    server_name _;

    root /var/www/erp;
    index index.html;

    client_max_body_size 20m;

    # 2M 带宽下 gzip 是体感关键：主包 2.6MB → 约 761KB
    gzip on;
    gzip_comp_level 6;
    gzip_min_length 1024;
    gzip_vary on;
    gzip_proxied any;
    gzip_types text/plain text/css text/xml application/json application/javascript
               application/xml application/xml+rss image/svg+xml;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    # 带 contenthash 的静态资源长缓存
    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
        try_files \$uri =404;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
        add_header Cache-Control "no-cache, no-store, must-revalidate" always;
    }

    location = /prod-api/auth/login {
        limit_req zone=erp_login burst=10 nodelay;
        proxy_pass http://127.0.0.1:8080/auth/login;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }

    location /prod-api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
    }
}
EOF

# 只启用 ERP 自己的配置；已有站点一律不动
ln -sf /etc/nginx/sites-available/erp-8081 /etc/nginx/sites-enabled/erp-8081
nginx -t

echo "==> [4/6] 部署前端静态文件"
mkdir -p /var/www/erp
rm -rf /var/www/erp/*
cp -r /opt/erp/frontend-dist/* /var/www/erp/
chown -R www-data:www-data /var/www/erp
echo "    文件数：$(find /var/www/erp -type f | wc -l)"

echo "==> [5/6] 启动应用"
systemctl restart erp

echo -n "    等待启动（2G 机器冷启动约 60~90s）"
OK=0
for i in $(seq 1 70); do
  if curl -fsS -u "${MONITOR_USER}:${MONITOR_PASS}" \
       http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo " ✅"
    OK=1
    break
  fi
  # 进程已死就不要空等
  if ! systemctl is-active --quiet erp; then
    echo
    echo "❌ 应用进程已退出，日志："
    journalctl -u erp -n 60 --no-pager
    exit 1
  fi
  echo -n "."
  sleep 3
done
if [ "$OK" -ne 1 ]; then
  echo
  echo "❌ 启动超时。最近日志："
  journalctl -u erp -n 80 --no-pager
  exit 1
fi

echo "==> [6/6] 启动 Nginx 并验收"
systemctl restart nginx

echo "    --- 静态首页 ---"
curl -sS -o /dev/null -w "    GET /           → %{http_code}\n" http://127.0.0.1/
echo "    --- 登录接口连通性（不依赖任何真实口令）---"
# 用一个运行时生成的随机口令探测：只要返回 HTTP 200 + JSON，就说明
# Nginx → 应用 → 数据库 整条链路是通的（RuoYi 对密码错误也回 200 + 业务错误码）。
# 注意 clientId 必须在 JSON body 里（LoginBody.clientId 带 @NotBlank），
# 不是 HTTP header：header 里的 clientid 只在登录成功后才用于比对 token。
PROBE_PWD="probe-$(date +%s)-$$"
PROBE=$(curl -sS -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"clientId\":\"community-client-id-000001\",\"username\":\"admin\",\"password\":\"${PROBE_PWD}\",\"grantType\":\"password\",\"tenantId\":\"000000\"}" \
  -w '\nHTTP_CODE=%{http_code}')
echo "$PROBE" | head -c 300
echo
if echo "$PROBE" | grep -q 'HTTP_CODE=200'; then
  echo "    ✅ 链路正常（返回业务错误是预期的，说明应用与数据库都在工作）"
else
  echo "    ⚠️ 登录接口未返回 200，请检查：journalctl -u erp -n 50 --no-pager"
fi

echo "==> 内存占用"
free -m | head -2
echo "==> 磁盘占用"
df -h / | tail -1
echo
echo "✅ 部署完成"
echo "   访问：http://<服务器IP>:${ERP_HTTP_PORT}/"
echo "   账号：admin（默认口令见仓库 deploy/README.md，登录后立即改密）"
echo "   ⚠️ 需在华为云安全组放行 TCP ${ERP_HTTP_PORT}"
echo
echo "   健康检查（需 Basic 认证）："
echo "   curl -u ${MONITOR_USER}:${MONITOR_PASS} http://127.0.0.1:8080/actuator/health"
echo
echo "   ⚠️ 请把下面这行抄到密码管理器里，脚本重跑会换新值："
echo "   监控账号 ${MONITOR_USER} / ${MONITOR_PASS}"
