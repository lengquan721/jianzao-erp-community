#!/usr/bin/env bash
# ============================================================
# 修复 CORS：ERP 端口变更后必须同步 Origin 白名单
#
# 根因：ResourcesConfig.corsFilter() 用 addAllowedOriginPattern 做精确匹配，
#       Origin 含端口（http://IP:8081）与白名单里的 http://IP 不相等即 403。
#       表现：登录页弹「系统接口403异常」，写请求全部失败。
#
# 用法：bash 06-fix-cors.sh [端口，默认 8081]
# ============================================================
set -euo pipefail

_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

PORT="${1:-8081}"

echo "==> [1/4] 探测公网 IP / 域名"
PUBLIC_IP=$(curl -sS -m 8 https://api.ipify.org 2>/dev/null || true)
[ -z "$PUBLIC_IP" ] && PUBLIC_IP=$(hostname -I | awk '{print $1}')
echo "    公网 IP：${PUBLIC_IP}"

NGINX_PORT=$(grep -oP '^\s*listen\s+\K[0-9]+' /etc/nginx/sites-available/erp-8081 2>/dev/null | head -1 || true)
[ -n "$NGINX_PORT" ] && PORT="$NGINX_PORT"
echo "    ERP 端口：${PORT}"

echo
echo "==> [2/4] 重写 security.cors.allowed-origins"
# 同时放行带端口与不带端口两种形式：
#   浏览器访问 http://IP:8081 时 Origin 带端口；走 80 反代时不带端口。
# 端口用通配 *，以后换端口不必再改配置。
ORIGINS="http://${PUBLIC_IP},http://${PUBLIC_IP}:${PORT},http://${PUBLIC_IP}:*"
if [ -f /etc/default/erp ]; then
  if grep -q '^SECURITY_CORS_ALLOWED_ORIGINS=' /etc/default/erp; then
    sed -i "s|^SECURITY_CORS_ALLOWED_ORIGINS=.*|SECURITY_CORS_ALLOWED_ORIGINS=${ORIGINS}|" /etc/default/erp
  else
    echo "SECURITY_CORS_ALLOWED_ORIGINS=${ORIGINS}" >> /etc/default/erp
  fi
else
  echo "❌ /etc/default/erp 不存在，请先执行 03-app.sh" >&2
  exit 1
fi
echo "    新值：${ORIGINS}"
echo "    本地回环也放行（安全：仅绑 127.0.0.1 可访问）"
if ! grep -q '^SECURITY_CORS_ALLOWED_ORIGINS=' /etc/default/erp; then
  true
fi
# 追加 localhost 形式，便于服务器上用 curl 自测
sed -i "s|^SECURITY_CORS_ALLOWED_ORIGINS=.*|&,http://localhost,http://localhost:${PORT},http://127.0.0.1,http://127.0.0.1:${PORT}|" /etc/default/erp
grep '^SECURITY_CORS_ALLOWED_ORIGINS=' /etc/default/erp | sed 's/^/    /'

echo
echo "==> [3/4] 重启应用"
systemctl restart erp
echo -n "    等待重启"
OK=0
for i in $(seq 1 60); do
  if ! systemctl is-active --quiet erp; then
    echo
    echo "❌ 进程已退出："
    journalctl -u erp -n 40 --no-pager
    exit 1
  fi
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "http://127.0.0.1:8080/auth/tenant/list" 2>/dev/null || echo "000")
  if [ "$CODE" = "200" ]; then echo " ✅"; OK=1; break; fi
  echo -n "."
  sleep 2
done
[ "$OK" -eq 1 ] || { echo; echo "❌ 启动超时"; journalctl -u erp -n 60 --no-pager; exit 1; }

echo
echo "==> [4/4] 验证 CORS"
check_origin() {
  local origin="$1"
  local code
  code=$(curl -sS -o /dev/null -w '%{http_code}' -m 10 \
    -H "Origin: ${origin}" "http://127.0.0.1:8080/auth/tenant/list" 2>/dev/null || echo "000")
  if [ "$code" = "200" ]; then
    echo "    ✅ Origin: ${origin}  → ${code}"
  else
    echo "    ❌ Origin: ${origin}  → ${code}"
  fi
}
check_origin "http://${PUBLIC_IP}"
check_origin "http://${PUBLIC_IP}:${PORT}"
check_origin "http://localhost:${PORT}"

echo
echo "    --- 经 nginx 的真实链路 ---"
curl -sS -m 10 -H "Origin: http://${PUBLIC_IP}:${PORT}" \
  -H 'clientid: community-client-id-000001' \
  "http://127.0.0.1:${PORT}/prod-api/auth/tenant/list" -w "\n    HTTP %{http_code}\n" | head -c 300
echo
echo
echo "✅ 完成。浏览器请硬刷新（Ctrl+F5）后再登录：http://${PUBLIC_IP}:${PORT}/"
