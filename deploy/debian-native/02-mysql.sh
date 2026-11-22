#!/usr/bin/env bash
# ============================================================
# 简造ERP社区版 · Debian 12 原生部署 ② MySQL 8.0
#
# Debian 12 官方源只有 MariaDB 10.11；本项目用 MySQL 8：
#   - 连接器 mysql-connector-j 9.7.0 对 caching_sha2_password 处理最稳
#   - 04-erp-schema.sql 用到 GENERATED ALWAYS ... STORED 生成列
#   - MariaDB 的 JSON 列是 LONGTEXT 别名，MyBatis-Plus 映射会有差异
#
# 执行：bash 02-mysql.sh '<ROOT_PASSWORD>' '<ERP_PASSWORD>'
# 密码要求：≥12 位，含大小写与数字，避免特殊字符（会写进 systemd 与 shell）
#
# 注意：Oracle 的 apt 源 GPG 密钥会到期续期（同一指纹换新文件）。
#       2023 版已于 2025-10-22 过期，现用 2025 版（有效至 2027-10-23）。
#       若日后再次 EXPKEYSIG，去 https://repo.mysql.com/ 找最新 RPM-GPG-KEY-mysql-YYYY。
# ============================================================
set -euo pipefail

# ---- 环境守卫：必须在服务器（Linux/root/已装基础环境）上执行 ----
_GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "${_GUARD_DIR}/_guard.sh" ] && source "${_GUARD_DIR}/_guard.sh" && _guard_check full

MYSQL_ROOT_PASSWORD="${1:?用法: bash 02-mysql.sh <root密码> <erp用户密码>}"
ERP_DB_PASSWORD="${2:?用法: bash 02-mysql.sh <root密码> <erp用户密码>}"

if [ ${#MYSQL_ROOT_PASSWORD} -lt 12 ] || [ ${#ERP_DB_PASSWORD} -lt 12 ]; then
  echo "❌ 密码长度必须 ≥12 位" >&2
  exit 1
fi

echo "==> [1/5] 添加 MySQL 官方 apt 源（bookworm）"
export DEBIAN_FRONTEND=noninteractive

# 已存在且失效就重建：密钥续期时指纹不变，重新下载即恢复有效
install -d -m 0755 /etc/apt/keyrings
KEY_OK=0
if [ -f /etc/apt/keyrings/mysql.gpg ]; then
  if gpg --show-keys --with-colons /etc/apt/keyrings/mysql.gpg 2>/dev/null | grep -q "^pub:[^:]*:[^:]*:[^:]*:[^:]*:[0-9]*:[0-9]*"; then
    EXP=$(gpg --show-keys --with-colons /etc/apt/keyrings/mysql.gpg 2>/dev/null | grep "^pub" | cut -d: -f7)
    if [ -n "${EXP:-}" ] && [ "$EXP" -gt "$(date +%s)" ]; then KEY_OK=1; fi
  fi
fi

if [ "$KEY_OK" -eq 0 ]; then
  echo "    下载 GPG 密钥（2025 版，有效至 2027-10-23）"
  for k in RPM-GPG-KEY-mysql-2025 RPM-GPG-KEY-mysql-2023; do
    if curl -fsSL "https://repo.mysql.com/${k}" | gpg --dearmor -o /etc/apt/keyrings/mysql.gpg 2>/dev/null; then
      EXP=$(gpg --show-keys --with-colons /etc/apt/keyrings/mysql.gpg 2>/dev/null | grep "^pub" | cut -d: -f7)
      if [ -n "${EXP:-}" ] && [ "$EXP" -gt "$(date +%s)" ]; then
        echo "    使用 ${k}（到期 $(date -d @"$EXP" +%F)）"
        break
      fi
    fi
  done
fi
chmod 0644 /etc/apt/keyrings/mysql.gpg

cat > /etc/apt/sources.list.d/mysql.list <<'EOF'
deb [signed-by=/etc/apt/keyrings/mysql.gpg] http://repo.mysql.com/apt/debian/ bookworm mysql-8.0
EOF

apt-get update -qq 2>&1 | grep -vE "^W:|^N:|^$" || true

echo "==> [2/5] 预置 debconf，避免安装时交互式设密码"
debconf-set-selections <<EOF
mysql-community-server mysql-community-server/root-pass password ${MYSQL_ROOT_PASSWORD}
mysql-community-server mysql-community-server/re-root-pass password ${MYSQL_ROOT_PASSWORD}
mysql-community-server mysql-server/default-auth-override select Use Strong Password Encryption (RECOMMENDED)
EOF

echo "==> [3/5] 安装 mysql-server（约 300MB，2M 带宽需 5-10 分钟）"
apt-get install -y --no-install-recommends mysql-server

echo "==> [4/5] 写 2C2G 调优配置"
cat > /etc/mysql/mysql.conf.d/zz-erp-lowmem.cnf <<'EOF'
[mysqld]
# ---- 字符集与时区 ----
character-set-server        = utf8mb4
collation-server            = utf8mb4_general_ci
default-time-zone           = +08:00

# ---- 内存（2G 机器核心约束）----
innodb_buffer_pool_size     = 96M
innodb_log_buffer_size      = 8M
innodb_redo_log_capacity    = 64M
# 临时表超过阈值直接落磁盘，不向系统要内存
# （一次对账/成本报表的 GROUP BY 能瞬间申请 1G，是 2G 机器最常见的 OOM 源）
temptable_max_ram           = 32M
temptable_max_mmap          = 0

# ---- 连接 ----
max_connections             = 30
table_open_cache            = 256

# ---- 关闭用不到的重组件 ----
performance_schema          = OFF
mysqlx                      = OFF
skip-log-bin

# ---- 慢查询 ----
slow_query_log              = 1
slow_query_log_file         = /var/log/mysql/slow.log
long_query_time             = 2

[client]
default-character-set       = utf8mb4
EOF

systemctl enable mysql >/dev/null
systemctl restart mysql

echo "==> [5/5] 建库建用户并导入初始化 SQL"
for i in $(seq 1 60); do
  if mysqladmin ping -uroot -p"${MYSQL_ROOT_PASSWORD}" --silent >/dev/null 2>&1; then break; fi
  sleep 2
  if [ "$i" -eq 60 ]; then
    echo "❌ MySQL 启动超时。排查："
    echo "   journalctl -u mysql -n 50 --no-pager"
    echo "   tail -50 /var/log/mysql/error.log"
    exit 1
  fi
done

# 8.0 首次安装 root 走 socket 认证，用 socket 设密码更稳；失败再回落密码登录
if mysql -uroot -e "SELECT 1" >/dev/null 2>&1; then
  echo "    root 为 socket 认证，设置密码"
  mysql -uroot <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '${MYSQL_ROOT_PASSWORD}';
FLUSH PRIVILEGES;
SQL
fi

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS erp
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER IF NOT EXISTS 'erp'@'127.0.0.1' IDENTIFIED BY '${ERP_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'erp'@'localhost' IDENTIFIED BY '${ERP_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON erp.* TO 'erp'@'127.0.0.1';
GRANT ALL PRIVILEGES ON erp.* TO 'erp'@'localhost';
FLUSH PRIVILEGES;
SQL

SQL_DIR="${SQL_DIR:-/opt/erp/sql}"
echo "    导入顺序：01 系统表 → 04 业务表 → 05 菜单种子 → 06 图标"
for f in 01-ruoyi-system.sql 04-erp-schema.sql 05-erp-seed.sql 06-menu-icons-tdesign.sql; do
  if [ ! -f "${SQL_DIR}/${f}" ]; then
    echo "❌ 缺少 ${SQL_DIR}/${f}，请先上传 deploy/mysql/init/ 内容" >&2
    exit 1
  fi
  echo "    -> ${f}"
  mysql --default-character-set=utf8mb4 -uroot -p"${MYSQL_ROOT_PASSWORD}" erp < "${SQL_DIR}/${f}"
done

echo "==> 校验"
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "
SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema='erp';
SELECT user, host FROM mysql.user WHERE user='erp';
SELECT COUNT(*) AS erp_menus FROM erp.sys_menu WHERE menu_id >= 3200;
" 2>/dev/null

echo
echo "✅ MySQL 完成。期望表数约 63（25 系统 + 38 业务）"
echo "下一步：上传 erp.jar 后执行 bash 03-app.sh"
