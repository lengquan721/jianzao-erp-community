#!/usr/bin/env bash
# Credentials ONLY via isolated_mysql.py exec. Offline strips them before javac/Java self-tests.
set +x
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && $1 != --offline ) ]]; then
  printf '%s\n' 'Usage: stock-hotspot-run.sh [--offline]' >&2
  exit 2
fi
if [[ $# -eq 0 && ${ERP_AUDIT_REAL_MYSQL-} != 1 ]]; then
  printf '%s\n' 'REFUSED: use isolated_mysql.py exec.' >&2
  exit 2
fi
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TOOLSET="$(cd -- "$HERE/../../toolset" && pwd -P)"
source "$TOOLSET/build-env.sh"
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY no_proxy NO_PROXY ftp_proxy FTP_PROXY
while IFS= read -r name; do
  case "${name,,}" in proxy|*_proxy) unset "$name" ;; esac
done < <(compgen -e)
unset CLASSPATH JDK_JAVA_OPTIONS JDK_JAVAC_OPTIONS _JAVA_OPTIONS JAVA_OPTS
export JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=C:/Windows/Temp -Xms32m -Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -XX:+UseSerialGC'
exec python.exe -B -I "$(cygpath -w "$HERE/stock-hotspot-run.py")" "$(cygpath -w "$JAVA_HOME")" "$@"
