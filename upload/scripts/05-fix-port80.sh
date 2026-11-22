#!/usr/bin/env bash
# ============================================================
# 诊断并修复 80 端口归属
#
# 背景：04 脚本把 sites-enabled/default 恢复了，但服务器上真正的应用是
#       sites-available/admin（/var/www/admin）。若 default 抢到
#       default_server，裸 IP 访问就会变成 nginx 欢迎页。
#
# 本脚本只做判断与最小改动，不碰任何应用文件：
#   1. 打印 admin / default 配置的关键指令
#   2. 探测 80 端口实际返回内容
#   3. 若 default 只是欢迎页且 admin 才是应用 → 停用 default
#   4. 复验
# ============================================================
set -euo pipefail

_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

echo "==> [1/3] 现有配置的 listen / root"
for f in /etc/nginx/sites-available/*; do
  [ -e "$f" ] || continue
  base=$(basename "$f")
  enabled="未启用"
  [ -e "/etc/nginx/sites-enabled/$base" ] && enabled="已启用"
  echo "  ── ${base}（${enabled}）"
  grep -nE "^\s*(listen|server_name|root|proxy_pass)" "$f" | sed 's/^/       /' || true
  echo
done

echo "==> [2/3] 80 端口实际返回内容"
HEAD80=$(curl -sS -m 10 http://127.0.0.1/ | head -c 400 || true)
echo "$HEAD80" | sed 's/^/    /' | head -20
echo

IS_WELCOME=0
echo "$HEAD80" | grep -qi "Welcome to nginx" && IS_WELCOME=1

ADMIN_IS_APP=0
if [ -d /var/www/admin ] && [ -n "$(ls -A /var/www/admin 2>/dev/null)" ]; then
  ADMIN_IS_APP=1
fi
echo "    判定：WELCOME_PAGE=${IS_WELCOME}  ADMIN_IS_APP=${ADMIN_IS_APP}"

echo
echo "==> [3/3] 按判定结果处置"
if [ "$IS_WELCOME" = "1" ] && [ "$ADMIN_IS_APP" = "1" ] && [ -e /etc/nginx/sites-enabled/admin ]; then
  echo "    default 是欢迎页且抢占了 80，admin 才是你的应用 → 停用 default"
  STAMP=$(date +%Y%m%d-%H%M%S)
  mkdir -p /root/nginx-backup-${STAMP}
  cp -a /etc/nginx/sites-enabled/default /root/nginx-backup-${STAMP}/ 2>/dev/null || true
  rm -f /etc/nginx/sites-enabled/default
  nginx -t
  systemctl reload nginx
  echo "    已停用 default（原文件保留在 /etc/nginx/sites-available/default）"
elif [ "$IS_WELCOME" = "1" ]; then
  echo "    ⚠️ 80 返回欢迎页，但未找到可用的 admin 应用，未自动改动。"
  echo "       请人工确认应用目录与配置。"
else
  echo "    ✅ 80 端口返回的不是欢迎页，无需改动"
fi

echo
echo "==> 复验"
curl -sS -o /dev/null -w "    http://127.0.0.1/       → %{http_code}\n" -m 10 http://127.0.0.1/ || true
curl -sS -o /dev/null -w "    http://127.0.0.1:8081/  → %{http_code}\n" -m 10 http://127.0.0.1:8081/ || true
echo
echo "    80 端口返回内容前 200 字符："
curl -sS -m 10 http://127.0.0.1/ | head -c 200 | sed 's/^/      /'
echo
echo
echo "    当前站点启用状态："
ls -l /etc/nginx/sites-enabled/ | sed 's/^/      /'
