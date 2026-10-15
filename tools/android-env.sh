#!/usr/bin/env bash
# Android 构建环境准备脚本，只影响当前 shell 会话，可重复加载：
#   source tools/android-env.sh
# 具体行为：
#   1) 已存在可用的 JAVA_HOME 时直接沿用，否则回退到 Homebrew 的 openjdk@17；
#   2) ANDROID_HOME / ANDROID_SDK_ROOT 默认指向 $HOME/Library/Android/sdk；
#   3) 把 SDK 的 platform-tools、emulator、cmdline-tools/latest/bin 追加到 PATH 末尾；
#   4) 任何一项缺失时打印中文提示，但不改写用户的 shell 配置，也不改动 HOME。
# 注意：脚本刻意不设置 set -e，避免影响加载它的 shell 后续行为。

# 返回 Homebrew openjdk@17 的默认安装路径
_simulsub_default_jdk() {
  printf '%s\n' "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
}

# 判断给定目录是否是可用 JDK（存在可执行的 bin/java）
_simulsub_is_jdk() {
  [ -n "${1:-}" ] && [ -x "$1/bin/java" ]
}

# 把目录追加到 PATH 末尾；目录不存在或已在 PATH 中时不做改动
_simulsub_append_path() {
  local dir=$1
  [ -d "$dir" ] || return 0
  case ":$PATH:" in
    *":$dir:"*) return 0 ;;
  esac
  PATH="$PATH:$dir"
  export PATH
}

# 判断本脚本是被 source 还是直接执行（bash 与 zsh 都兼容）
_simulsub_is_sourced() {
  if [ -n "${ZSH_VERSION:-}" ]; then
    case "${ZSH_EVAL_CONTEXT:-}" in
      *:file*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  [ "${BASH_SOURCE[0]:-}" != "$0" ]
}

# 1. JDK：沿用现有 JAVA_HOME，否则回退 Homebrew openjdk@17
if _simulsub_is_jdk "${JAVA_HOME:-}"; then
  echo "JDK：沿用现有 JAVA_HOME=$JAVA_HOME"
else
  _simulsub_jdk="$(_simulsub_default_jdk)"
  if _simulsub_is_jdk "$_simulsub_jdk"; then
    JAVA_HOME="$_simulsub_jdk"
    export JAVA_HOME
    echo "JDK：未检测到可用的 JAVA_HOME，已使用 Homebrew openjdk@17：$JAVA_HOME"
  else
    echo "提示：未检测到可用的 JDK 17。请先安装（brew install openjdk@17）或自行设置 JAVA_HOME，再重新 source 本脚本。" >&2
  fi
fi

# 2. Android SDK：默认 $HOME/Library/Android/sdk，HOME 本身不做任何改动
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    ANDROID_HOME="$ANDROID_SDK_ROOT"
  else
    ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi
export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [ -d "$ANDROID_HOME" ]; then
  echo "Android SDK：$ANDROID_HOME"
else
  echo "提示：未找到 Android SDK 目录 $ANDROID_HOME。请用 Android Studio 或 sdkmanager 安装 SDK，或先设置 ANDROID_HOME 后重新 source 本脚本。" >&2
fi

# 3. 追加 SDK 命令行工具到 PATH
_simulsub_append_path "$ANDROID_HOME/platform-tools"
_simulsub_append_path "$ANDROID_HOME/emulator"
_simulsub_append_path "$ANDROID_HOME/cmdline-tools/latest/bin"

# 4. 直接执行而非 source 时给出提示：变量只存在于子进程，不会留在当前终端
if ! _simulsub_is_sourced; then
  echo "提示：本脚本需要通过 source 生效，例如：source tools/android-env.sh" >&2
fi

# 清理临时变量与辅助函数，避免污染调用方 shell
unset _simulsub_jdk
unset -f _simulsub_default_jdk _simulsub_is_jdk _simulsub_append_path _simulsub_is_sourced
