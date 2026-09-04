#!/bin/bash
# 编译/安装探针 APK。用项目内自带的 JDK17 + SDK + Gradle,不碰系统环境。
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"
W="C:/wp"   # 目录联接 -> Desktop/工作/whalephone,AGP 不接受非 ASCII 路径
export JAVA_HOME="$W/tools/jdk17"
export ANDROID_HOME="$W/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="$W/tools/gradle/bin/gradle.bat"

case "${1:-build}" in
  build)   "$GRADLE" --console=plain -p "$W" assembleDebug "${@:2}" ;;
  install) "$GRADLE" --console=plain -p "$W" installDebug "${@:2}" ;;
  clean)   "$GRADLE" --console=plain -p "$W" clean ;;
  *)       "$GRADLE" --console=plain -p "$W" "$@" ;;
esac
