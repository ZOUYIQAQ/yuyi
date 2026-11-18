#!/usr/bin/env bash
# 在唯一的现有模拟器安装优化预览包；测试仍使用Debug，发布仍使用正式签名Release。
set -euo pipefail

if [ "$#" -gt 1 ] || { [ "$#" -eq 1 ] && [[ ! "$1" =~ ^emulator-[0-9]+$ ]]; }; then
  echo "用法：bash tools/run-android-preview.sh [模拟器编号，例如 emulator-5580]" >&2
  exit 2
fi

task_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_repo_dir="$(cd -- "$task_script_dir/.." && pwd)"
source "$task_script_dir/android-env.sh"
task_adb="$ANDROID_HOME/platform-tools/adb"

if [ "$#" -eq 1 ]; then
  task_device="$1"
else
  task_devices=()
  while IFS= read -r task_candidate; do
    task_devices+=("$task_candidate")
  done < <("$task_adb" devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  if [ "${#task_devices[@]}" -ne 1 ]; then
    echo "需要恰好一台已启动的模拟器，或手动指定模拟器编号；本脚本不启动新设备。" >&2
    exit 2
  fi
  task_device="${task_devices[0]}"
fi

if [ "$("$task_adb" -s "$task_device" get-state)" != "device" ]; then
  echo "指定模拟器尚未就绪：$task_device" >&2
  exit 2
fi

cd -- "$task_repo_dir"
echo "正在构建正式版优化的预览包，并覆盖安装到 ${task_device}。"
# 构建退出后释放编译进程，避免它长期占用内存影响模拟器交互。
./gradlew :app:assemblePreview --no-daemon --console=plain
if ! "$task_adb" -s "$task_device" install -r -t app/build/outputs/apk/preview/app-preview.apk; then
  echo "覆盖安装失败，请核对当前应用签名；现有应用和数据已保留。" >&2
  exit 1
fi
"$task_adb" -s "$task_device" shell am start -W -n io.github.zouyiqaq.yuyi/io.github.korenkrita.simulsub.MainActivity
