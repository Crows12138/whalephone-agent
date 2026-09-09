#!/usr/bin/env bash
# 加购物车:比「比价」多一层的任务。
#
# 为什么值得单独跑一遍:比价全程是「读」,加购是第一个真正**改变外部状态**的任务,
# 而且路上有一个比价碰不到的东西 —— 商品详情页的 SKU 选择弹层。那个弹层是模态的、
# 不选完颜色/版本加购按钮就不生效,而它在无障碍树里往往没有「已选中」的语义标记,
# 模型只能靠元素文本判断自己选没选。这是「撑得起更复杂的任务」这条最实在的证据。
#
# 加购是可撤销的,所以按提示词里的规矩它**不该** ask(ask 留给花钱和发消息)。
# 跑完顺便看它有没有多问 —— 多问一句的代价是机主要放下手上的事。
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
. scripts/lib.sh

A=ai.whalephone.agent
# 和演示同一个串(定义在 lib.sh)。这里过了才代表演示那条链路是通的。
GOAL="${1:-$DEMO_GOAL}"

echo "目标:$GOAL"
echo

"$ADB" logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null

# 只跟这三个标签,别的全是噪声
"$ADB" logcat -s WPAgent:I WPSvc:I 2>&1 | tr -d '\r' | while IFS= read -r line; do
  case "$line" in
    *"第 "*|*"     -> "*|*"副屏 "*"就绪"*|*"结束 done="*|*"收工"*)
      echo "${line#*: }" ;;
  esac
  case "$line" in *"收工"*) exit 0;; esac
done
