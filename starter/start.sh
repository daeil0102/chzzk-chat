#!/bin/sh
set -eu

script_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
cd "$script_dir"

jar=server.jar
if [ ! -f "$jar" ] && [ -f build/libs/server.jar ]; then
    jar=build/libs/server.jar
fi
if [ ! -f "$jar" ]; then
    printf '%s\n' 'server.jar를 찾을 수 없습니다. 실행 파일과 같은 폴더에 넣거나 Gradle로 먼저 빌드하세요.' >&2
    exit 1
fi

java_cmd=java
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_cmd=$JAVA_HOME/bin/java
elif ! command -v java >/dev/null 2>&1; then
    printf '%s\n' 'Java 21 이상을 설치하고 PATH 또는 JAVA_HOME을 설정하세요.' >&2
    exit 1
fi

exec "$java_cmd" -jar "$jar" config.yaml
