#!/usr/bin/env bash
set -euo pipefail
# Maven 3.8+，JDK 17+；源码路径可通过环境变量覆盖，不绑定开发者本机路径。
script_dir="$(cd "$(dirname "$0")" && pwd)"
example_dir="$(cd "$script_dir/.." && pwd)"
sdk_root="$(cd "$example_dir/.." && pwd)"
source_parent="$(cd "$sdk_root/.." && pwd)"
sdk_dir="${AGENT_GUARD_SOURCE:-$sdk_root}"
lc_dir="${LANGCHAIN4J_SOURCE:-$source_parent/langchain4j}"
maven="${MAVEN_CMD:-mvn}"
"$maven" -B -ntp -f "$sdk_dir/pom.xml" install
# 此处只安装本地框架制品；不运行整个框架仓库的测试和覆盖率门禁。
# 本地 fork 新增了公开护栏 API，此安装不执行与上游发行版的 Revapi 比较。
# 进入源码目录以沿用 .mvn 配置。
(
    cd "$lc_dir"
    "$maven" -B -ntp -pl langchain4j,langchain4j-open-ai,http-clients/langchain4j-http-client-jdk \
        -am -DskipTests -DskipITs -Djacoco.skip=true -Dspotless.skip=true -Drevapi.skip=true install
)
