#!/usr/bin/env bash
#
# 一次构建全部支持的 Minecraft 版本。
#
#   ./build-all.sh                  # 构建全部版本
#   ./build-all.sh 1.21.4 1.21.8    # 只构建指定版本
#
# 产物在 build/libs/hongshikaikai-<mc 版本>-0.1.0.jar。
#
# 注意:Loom 每次切版本都要重新准备该版本的 Minecraft(下载 + 重映射),
# 第一次构建某个版本会明显偏慢,之后有缓存就快了。

set -uo pipefail

cd "$(dirname "$0")"

ALL=(1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.10 1.21.11)

if [ "$#" -gt 0 ]; then
    VERSIONS=("$@")
else
    VERSIONS=("${ALL[@]}")
fi

failed=()

for v in "${VERSIONS[@]}"; do
    echo
    echo "============================== $v =============================="
    if ./gradlew build -Pmc="$v" --console=plain; then
        echo ">>> 成功: build/libs/hongshikaikai-$v-0.1.0.jar"
    else
        echo ">>> 失败: $v"
        failed+=("$v")
    fi
done

echo
if [ "${#failed[@]}" -eq 0 ]; then
    echo "全部构建成功。"
else
    echo "以下版本构建失败: ${failed[*]}"
    exit 1
fi
