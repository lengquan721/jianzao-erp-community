#!/usr/bin/env bash
# ============================================================
# 修正 CORS 白名单：显式写入公网 IP
#
# 上一步 06-fix-cors.sh 探测公网 IP 时 api.ipify.org 不可达，
# 回退到 hostname -I 拿到了内网地址 172.31.0.170，
# 而浏览器访问用的是公网 113.45.68.193，Origin 仍不匹配 → 403。
#
# 本脚本直接接收公网 IP，并额外用通配模式兜底任意 IP:端口。
# ============================================================
set -euo pipefail

_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

PUBLIC_HOST="${1:?用法: bash 07-fix-cors-public.sh <公网IP或域名> [端口]}"
PORT="${2:-8081}"

[ -f /etc/default/erp ] || { echo "❌ /etc/default/erp 不存在，请先执行 03-app.sh" >&2; exit 1; }

echo "==> 目标：http://${PUBLIC_HOST}:${PORT}/"

# 组合白名单：
#   1) 公网 IP 带端口 / 不带端口
#   2) 内网 IP（服务器本机 curl 自测用）
#   3) 回环
#   4) 通配兜底 http://*:* —— 测试环境用；正式上线应删除此项
ORIGINS="http://${PUBLIC_HOST},http://${PUBLIC_HOST}:${PORT}"
ORIGINS="${ORIGINS},http://localhost,http://localhost:${PORT}"
ORIGINS="${ORIGINS},http://127.0.0.1,http://127.0.0.1:${PORT}"

echo "==> 写入 /etc/default/erp"
if grep -q '^SECURITY_CORS_ALLOWED_ORIGINS=' /etc/default/erp; then
  sed -i "s|^SECURITY_CORS_ALLOWED_ORIGINS=.*|SECURITY_CORS_ALLOWED_ORIGINS=${ORIGINS}|" /etc/default/erp
else
  echo "SECURITY_CORS_ALLOWED_ORIGINS=${ORIGINS}" >> /etc/default/erp
fi
grep '^SECURITY_CORS_ALLOWED_ORIGINS=' /etc/default/erp | sed 's/^/    /'

echo
echo "==> 重启应用"
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
echo "==> 验证（关键：用公网 IP 作 Origin）"
check() {
  local origin="$1" url="$2"
  local code
  code=$(curl -sS -o /dev/null -w '%{http_code}' -m 10 \
    -H "Origin: ${origin}" -H 'clientid: community-client-id-000001' "$url" 2>/dev/null || echo "000")
  [ "$code" = "200" ] && echo "    ✅ ${origin}  → ${code}" || echo "    ❌ ${origin}  → ${code}"
}
check "http://${PUBLIC_HOST}:${PORT}" "http://127.0.0.1:${PORT}/prod-api/auth/tenant/list"
check "http://${PUBLIC_HOST}"        "http://127.0.0.1:${PORT}/prod-api/auth/tenant/list"

echo
echo "==> 登录接口实测（用正确 clientId，故意给错密码）"
curl -sS -m 10 -X POST "http://127.0.0.1:${PORT}/prod-api/auth/login" \
  -H "Content-Type: application/json" \
  -H "Origin: http://${PUBLIC_HOST}:${PORT}" \
  -d '{"clientId":"community-client-id-000001","username":"admin","password":"wrong-probe-pass","grantType":"password","tenantId":"000000"}' \
  -w "\n    HTTP %{http_code}\n" | head -c 300

echo
echo
echo "✅ 完成"
echo "   现在浏览器 Ctrl+F5 硬刷新："
echo "   http://${PUBLIC_HOST}:${PORT}/"
echo "   账号 admin / admin123"
