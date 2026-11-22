#!/usr/bin/env bash
# ============================================================
# 修复：把 80 端口还给原有站点，ERP 改挂 8081
#
# 背景：03-app.sh 误将 ERP 配成 listen 80 default_server，
#       并删除了 sites-enabled/default 软链接，抢占了原有应用端口。
#
# 本脚本：
#   1. 备份当前 nginx 全部配置
#   2. 摘掉占用 80 的 ERP 站点配置
#   3. 恢复 default 软链接（原文件未删，只是链接没了）
#   4. 把 ERP 改挂 8081，不设 default_server
#   5. 双向验证：80 → 原应用，8081 → ERP
# ============================================================
set -euo pipefail

_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

STAMP=$(date +%Y%m%d-%H%M%S)
BK="/root/nginx-backup-${STAMP}"

echo "==> [0/5] 备份 nginx 配置到 ${BK}"
mkdir -p "$BK"
cp -a /etc/nginx/sites-available "$BK"/ 2>/dev/null || true
cp -a /etc/nginx/sites-enabled   "$BK"/ 2>/dev/null || true
cp -a /etc/nginx/conf.d          "$BK"/ 2>/dev/null || true
echo "    备份完成"

echo
echo "    当前站点配置清单："
ls -l /etc/nginx/sites-available/ 2>/dev/null | sed 's/^/      /'
echo "    ---- sites-enabled ----"
ls -l /etc/nginx/sites-enabled/ 2>/dev/null | sed 's/^/      /'
echo "    ---- conf.d ----"
ls -l /etc/nginx/conf.d/ 2>/dev/null | sed 's/^/      /'
echo "    ---- /var/www ----"
ls -la /var/www/ 2>/dev/null | sed 's/^/      /'

echo
echo "==> [1/5] 摘掉占用 80 端口的 ERP 配置"
rm -f /etc/nginx/sites-enabled/erp
rm -f /etc/nginx/sites-available/erp
rm -f /etc/nginx/sites-enabled/erp-8081
rm -f /etc/nginx/sites-available/erp-8081
echo "    已移除 erp / erp-8081 站点"

echo
echo "==> [2/5] 恢复原有站点"
RESTORED=0
if [ -f /etc/nginx/sites-available/default ] && [ ! -e /etc/nginx/sites-enabled/default ]; then
  ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default
  echo "    ✅ 已恢复 sites-enabled/default 软链接"
  RESTORED=1
fi
# 其他被遗留的配置文件也一并启用（若原本就在 enabled 里，cp -a 已保留）
for f in /etc/nginx/sites-available/*; do
  [ -e "$f" ] || continue
  base=$(basename "$f")
  [ "$base" = "default" ] && continue
  [ "$base" = "erp" ] && continue
  [ "$base" = "erp-8081" ] && continue
  if [ ! -e "/etc/nginx/sites-enabled/$base" ]; then
    echo "    发现未启用的站点 ${base}（未自动启用，仅提示）"
  fi
done

nginx -t
systemctl reload nginx
echo "    nginx 已重载"

echo
echo "==> [3/5] 验证 80 端口是否已还给原应用"
CODE80=$(curl -sS -o /dev/null -w '%{http_code}' -m 10 http://127.0.0.1/ || echo "000")
echo "    http://127.0.0.1/ → HTTP ${CODE80}"
echo
echo "    80 端口当前生效的 server 配置："
nginx -T 2>/dev/null | awk '/^\s*server\s*\{/{s=1} s&&/listen|server_name|root /{print "      " $0} /^\s*\}/{s=0}' | head -20

if [ "$CODE80" = "000" ]; then
  echo
  echo "    ⚠️ 80 端口无响应。可能原应用配置不在 sites-available/default，"
  echo "       请在备份目录 ${BK} 中确认原配置文件名，然后手工恢复："
  echo "         ln -s /etc/nginx/sites-available/<原文件名> /etc/nginx/sites-enabled/"
fi

echo
echo "==> [4/5] 把 ERP 挂到 8081（不动 80）"
cat > /etc/nginx/sites-available/erp-8081 <<'EOF'
# 简造ERP社区版 —— 独立端口，不占用 80
limit_req_zone $binary_remote_addr zone=erp_login:10m rate=20r/m;
limit_req_status 429;

server {
    listen 8081;
    listen [::]:8081;
    server_name _;

    root /var/www/erp;
    index index.html;

    client_max_body_size 20m;

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

    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    location = /prod-api/auth/login {
        limit_req zone=erp_login burst=10 nodelay;
        proxy_pass http://127.0.0.1:8080/auth/login;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    location /prod-api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache, no-store, must-revalidate" always;
    }
}
EOF

ln -sf /etc/nginx/sites-available/erp-8081 /etc/nginx/sites-enabled/erp-8081
nginx -t
systemctl reload nginx

echo
echo "==> [5/5] 双向验证"
echo "    --- 原有应用（80）---"
curl -sS -o /dev/null -w "    http://127.0.0.1/       → %{http_code}\n" -m 10 http://127.0.0.1/ || true
echo "    --- 简造ERP（8081）---"
curl -sS -o /dev/null -w "    http://127.0.0.1:8081/  → %{http_code}\n" -m 10 http://127.0.0.1:8081/ || true
echo "    --- ERP 应用本体（8080，仅供本机）---"
curl -sS -o /dev/null -w "    http://127.0.0.1:8080/  → %{http_code}\n" -m 10 http://127.0.0.1:8080/ || true

echo
echo "    当前所有监听端口："
ss -tlnp 2>/dev/null | grep -E ":(80|8080|8081)\b" | sed 's/^/      /' || true

echo
echo "============================================================"
echo "完成。"
echo
echo "  原有应用： http://113.45.68.193/        （80，未改动）"
echo "  简造ERP ： http://113.45.68.193:8081/   （需在华为云安全组放行 8081）"
echo
echo "  备份目录： ${BK}"
echo "  若 80 端口仍未恢复，从备份里取回原配置文件后执行："
echo "    ls ${BK}/sites-available/"
echo "    nginx -t && systemctl reload nginx"
echo "============================================================"
