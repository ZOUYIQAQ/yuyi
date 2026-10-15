#!/usr/bin/env bash
# 只准备构建所需的运行时库：sherpa-onnx 1.13.8 的静态链接 AAR（约 37 MB）。
# 语音识别与 VAD 模型不由本脚本下载或打包：APK 不内置任何模型，
# 模型统一在应用「模型库」中按需下载、校验后启用。
#   ./setup.sh
# 需要代理时可设置 HTTPS_PROXY，例如：
#   HTTPS_PROXY=http://127.0.0.1:7890 ./setup.sh
set -euo pipefail
cd "$(dirname "$0")"

SHERPA=1.13.8
GH=https://github.com/k2-fsa/sherpa-onnx/releases/download
AAR="app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA.aar"
# 固定版本的文件字节数与 SHA-256，用于确认下载结果与官方 release 一致
AAR_SIZE=38691998
AAR_SHA256=b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471

# 计算单个文件的 SHA-256：macOS 使用 shasum，Linux 与 Git Bash 使用 sha256sum
sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    sha256sum "$1" | cut -d' ' -f1
  fi
}

# 校验文件是否与给定的字节数、SHA-256 完全一致
verify_file() {
  [ "$(wc -c < "$1" | tr -d ' ')" = "$2" ] && [ "$(sha256_of "$1")" = "$3" ]
}

# 下载到 .part 临时文件（连接与总时长都有上限，失败自动重试），
# 校验通过后再转正为目标文件；校验失败保留临时文件便于排查并返回非零状态
fetch_verified() {
  local url=$1 dest=$2 size=$3 sha=$4 part="$2.part"
  echo "↓ $dest"
  curl -fL --connect-timeout 15 --max-time 900 --retry 5 --retry-delay 3 --retry-all-errors -o "$part" "$url"
  if ! verify_file "$part" "$size" "$sha"; then
    echo "✗ $dest：文件字节数或 SHA-256 与固定值不一致，临时文件保留在 $part" >&2
    return 1
  fi
  mv "$part" "$dest"
}

mkdir -p app/libs
if [ -s "$AAR" ] && verify_file "$AAR" "$AAR_SIZE" "$AAR_SHA256"; then
  echo "运行时库已存在且校验通过：$AAR"
else
  fetch_verified "$GH/v$SHERPA/$(basename "$AAR")" "$AAR" "$AAR_SIZE" "$AAR_SHA256"
  echo "运行时库下载完成并校验通过：$AAR"
fi

echo "提示：本脚本不下载、也不打包语音识别与 VAD 模型，模型请在应用的「模型库」中下载。"
