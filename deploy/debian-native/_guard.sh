#!/usr/bin/env bash
# ============================================================
# 环境守卫：被 01/02/03 脚本 source，防止在本机（Windows/WSL）误执行
#
# 调用方式：source _guard.sh 之后调用 _guard_check <阶段名>
#   _guard_check base   → 只查 Linux + root（01-base.sh 负责建 /opt/erp）
#   _guard_check full   → 额外要求 /opt/erp 已存在（02/03 前置）
# ============================================================

_guard_check() {
  local stage="${1:-full}"
  local fatal=0

  # 1) 必须 Linux
  if [ "$(uname -s 2>/dev/null)" != "Linux" ]; then
    echo "❌ 当前系统不是 Linux（uname=$(uname -s 2>/dev/null)）" >&2
    fatal=1
  fi

  # 2) 必须是 root
  if [ "$(id -u 2>/dev/null)" != "0" ]; then
    echo "❌ 需要用 root 执行（当前 uid=$(id -u 2>/dev/null)，用户=$(whoami 2>/dev/null)）" >&2
    fatal=1
  fi

  # 3) full 阶段要求 01-base.sh 已完成
  if [ "$stage" = "full" ] && [ ! -d /opt/erp ]; then
    echo "❌ /opt/erp 不存在，请先执行 bash 01-base.sh" >&2
    fatal=1
  fi

  if [ "$fatal" -eq 1 ]; then
    cat >&2 <<'HINT'

────────────────────────────────────────────────────────
这些脚本必须在【华为云服务器】上执行，不是在本地电脑。

正确的做法：
  1. 先在本地 PowerShell 登录服务器：
         ssh root@113.45.68.193
  2. 看到提示符变成 root@host-...:~# 后，才执行脚本：
         cd /opt/erp/scripts && bash 02-mysql.sh '密码1' '密码2'

判断自己在哪：
  PS D:\...              → 本地 Windows，不能跑这些脚本
  root@host-...:~#       → 服务器，可以跑
────────────────────────────────────────────────────────
HINT
    exit 1
  fi
}
