#!/usr/bin/env bash
# Disable tracing before any credential handling.
set +x
set -eu
backend_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
cd -- "$backend_dir"
command -v java >/dev/null || { printf 'Java 17 is required.\n' >&2; exit 1; }
command -v mvn >/dev/null || { printf 'Maven is required.\n' >&2; exit 1; }
# Optional override for a custom Maven cache; never put credentials in this path.
driver="${MYSQL_CONNECTOR_JAR:-$HOME/.m2/repository/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar}"
if [[ ! -r "$driver" ]]; then
  printf 'MySQL driver missing. Run mvn package first, or set MYSQL_CONNECTOR_JAR to the Connector/J JAR path.\n' >&2
  exit 1
fi
exec env -u DB_PASSWORD -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
  java -cp "$driver" "$backend_dir/scripts/BearPiMysqlDiag.java" --start
