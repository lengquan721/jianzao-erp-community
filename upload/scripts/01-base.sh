#!/usr/bin/env bash
# ============================================================
# 简造ERP社区版 · Debian 12 原生部署 ① 基础环境
#
# 目标机器：2 核 2G / Debian 12 bookworm
# 不使用 Docker：2G 机器上 dockerd + containerd 额外吃 100MB+，
# 且 142MB 的 JAR 镜像层在 2G 机器构建易失败。
#
# 执行：bash 01-base.sh
# ============================================================
set -euo pipefail

# ---- 环境守卫：必须在服务器（Linux/root/已装基础环境）上执行 ----
_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check base

echo "==> [1/4] 安装基础包"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
  ca-certificates curl gnupg lsb-release apt-transport-https \
  openjdk-17-jre-headless nginx redis-server sqlite3 unzip

echo "==> [2/4] 创建 2G swap（2G 内存机器无 swap 时峰值即 OOM）"
if ! swapon --show | grep -q '/swapfile'; then
  if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "    swap 已启用"
else
  echo "    swap 已存在，跳过"
fi
# 内存吃紧时更积极换出，空闲时不换出
sysctl -qw vm.swappiness=30
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=30' >> /etc/sysctl.conf

echo "==> [3/4] 创建运行用户与目录"
id -u erp >/dev/null 2>&1 || useradd --system --home /opt/erp --shell /usr/sbin/nologin erp
mkdir -p /opt/erp/{app,sql,logs,temp,data}
chown -R erp:erp /opt/erp
mkdir -p /var/www/erp
chown -R www-data:www-data /var/www/erp

echo "==> [4/4] 环境自检"
java -version 2>&1 | head -1
echo "--- 内存 ---"
free -m | head -2
echo "--- swap ---"
swapon --show
echo "--- 磁盘 ---"
df -h / | tail -1

echo
echo "✅ 基础环境完成。下一步：bash 02-mysql.sh"
