#!/usr/bin/env bash
# 无障碍权限跟着任务开关 —— A11yGate 的验收。
#
# 为什么这件事需要单独一个脚本:它动的是**机主全局的系统设置**,而且是那种
# 出错了不报错、只是悄悄把别人的东西关掉的改动。一个能 performAction 的无障碍
# 服务在微信收银台眼里等于木马,所以权限该跟着任务走;但「跟着任务走」这个便利
# 一旦写坏,代价是把机主的读屏服务一起关掉 —— 对依赖读屏的人这是断人手脚。
# 便利和风险不成比例,所以每条不变量都要单独量,不能靠「跑一遍看着挺好」。
#
# 六个用例对应六种「好心办坏事」:
#   1 开不起来 / 关不掉        —— 功能本身
#   2 把别人的无障碍服务连坐   —— 整表覆盖的经典错法
#   3 把机主自己开的给关了     —— 分不清是谁开的
#   4 进程被杀就永远留着       —— 状态只活在内存里
#   5 长时任务被自己掐断       —— 关权限等于拆掉自己的触发器
#   6 机主明确说别自动         —— 开关本身要能关掉
#
# 用法: ANDROID_SERIAL=emulator-5554 bash scripts/tests/test-a11y-gate.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
SELF=$A/.EyesAndHands
SELF_FULL=$A/$A.EyesAndHands
TB=com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
GOAL="${GOAL:-打开时钟,告诉我现在几点}"
naps() { python -c "import time;time.sleep($1)"; }

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf "   ✓ %s\n" "$1"; }
no()  { FAIL=$((FAIL+1)); printf "   ✗ %s\n" "$1"; }
chk() { if [ "$2" = "$3" ]; then ok "$1"; else no "$1  (期望 $3,实际 $2)"; fi; }

svcs()  { sh settings get secure enabled_accessibility_services | tr -d '\r'; }
# 表里有没有我们。
# 不去还原完整组件名再拿它当正则 —— A11yGate 写短名、机主从设置里开是全名,
# 两种写法都要认,而把点和斜杠转义成正则是这个项目里反复踩的反斜杠折叠坑。
# 「whalephone」这个子串在系统的无障碍服务里独一无二,定长匹配就够,还坏不了。
hasme() { svcs | grep -qF whalephone && echo yes || echo no; }
# 表里有几项是我们。0 和 2 都是错:2 说明短名/全名被当成了两个不同的项各追加一遍。
nme()  { svcs | tr ':' '
' | grep -cF whalephone; }
hastb() { svcs | grep -q "marvin.talkback" && echo yes || echo no; }
enab()  { sh settings get secure accessibility_enabled | tr -d '\r'; }

# 把无障碍恢复成一个确定的起点,并确认 app 那边真的停了。
# 不能只发广播:MARK 存在 SharedPreferences 里,不清掉会串到下一个用例。
reset() {  # $1 = 表的初始内容(空串 = 全关)
  sh am force-stop $A >/dev/null 2>&1
  # 输入法状态也是全局量,必须一起复位。上一个用例把键盘叫起来没收干净,
  # 下一个用例开跑时 agent 会认为「机主在打字」,等满 180 秒然后按设计拒绝开工 ——
  # 读数是「任务没收工」,看着像功能坏了,其实是闸门在正常工作。栽过一次。
  sh input -d 0 keyevent 111 >/dev/null 2>&1
  sh input -d 0 keyevent 3 >/dev/null 2>&1
  if [ -z "$1" ]; then
    sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
    sh settings put secure accessibility_enabled 0 >/dev/null 2>&1
  else
    sh settings put secure enabled_accessibility_services "$1" >/dev/null 2>&1
    sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
  fi
  # 清掉上一个用例留下的痕迹。prefs 只能在进程没跑的时候动。
  sh "run-as $A sed -i 's/<string name=\"a11y_opened_by_us\">1<\/string>//;s/<string name=\"watch_goal\">[^<]*<\/string>//;s/<int name=\"watch_rounds_left\"[^>]*\/>//;s/<string name=\"A11Y_AUTO\">[^<]*<\/string>//' shared_prefs/whalephone.xml" >/dev/null 2>&1
  naps 2
}

# 在 app 进程没跑的时候直接改 prefs。用来构造「上次没关成」「有长时任务」这类前置状态 ——
# 这些状态在正常流程里要跑很久才出现,直接构造出来才测得动。
setpref() {
  sh am force-stop $A >/dev/null 2>&1; naps 1
  sh "run-as $A sed -i 's|</map>|<string name=\"$1\">$2</string></map>|' shared_prefs/whalephone.xml" >/dev/null 2>&1
}
# 整型版。SharedPreferences 按 XML 标签名分类型,getInt 读不到 <string>。
setprefi() {
  sh am force-stop $A >/dev/null 2>&1; naps 1
  sh "run-as $A sed -i 's|</map>|<int name=\"$1\" value=\"$2\" /></map>|' shared_prefs/whalephone.xml" >/dev/null 2>&1
}

# 发任务,等它收工。返回 0 = 正常结束,1 = 超时。
run_task() {
  sh logcat -c
  bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
  for _ in $(seq 1 "${2:-90}"); do
    naps 2
    sh logcat -d -s WPSvc:* | grep -q "结束 done=" && return 0
    [ -n "$1" ] && { sh logcat -d -s WPSvc:* | grep -q "$1" && return 0; }
  done
  return 1
}

echo "== 起点 =="
echo "   当前无障碍表: $(svcs)"
echo "   Shizuku:      $(sh ps -A | grep -c shizuku_server) 个进程"
echo

# ---------------------------------------------------------------- 1
echo "== 1. 没开权限时能自己开,跑完自己关 =="
reset ""
chk "起点:表是空的" "$(hasme)" "no"
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
# 不能等固定秒数再采:一轮任务可能 12 秒就完了,采样点比它还晚就永远是空的。
# 轮询「有没有出现过」,这个判据和任务跑多久无关。
MID=no; MID_EN=0
for _ in $(seq 1 120); do
  [ "$(hasme)" = "yes" ] && { MID=yes; MID_EN=$(enab); break; }
  sh logcat -d -s WPSvc:* | grep -q "结束 done=" && break
  naps 1
done
RC=1
for _ in $(seq 1 90); do naps 2; sh logcat -d -s WPSvc:* | grep -q "结束 done=" && { RC=0; break; }; done
naps 4
chk "跑的时候权限是开的" "$MID" "yes"
chk "跑的时候 accessibility_enabled=1" "$MID_EN" "1"
[ $RC -eq 0 ] && ok "任务正常收工" || no "任务 180 秒没收工(后面几条的读数会不准)"
chk "跑完自己关掉了" "$(hasme)" "no"
sh logcat -d -s WPGate:* | sed 's/^.*WPGate *: /     /' | tail -4
echo

# ---------------------------------------------------------------- 2
echo "== 2. 不波及机主自己的无障碍服务(拿 TalkBack 当对照)=="
reset "$TB"
chk "起点:只有 TalkBack" "$(hastb)/$(hasme)" "yes/no"
run_task "" 90; RC=$?
naps 4
chk "跑完 TalkBack 还在" "$(hastb)" "yes"
chk "跑完我们自己没了" "$(hasme)" "no"
chk "accessibility_enabled 还是 1(TalkBack 还要用)" "$(enab)" "1"
echo "   收工后的表: $(svcs)"
echo

# ---------------------------------------------------------------- 3
echo "== 3. 机主自己开着的,不许关 =="
reset "$SELF"
chk "起点:机主自己开着(没有 MARK)" "$(hasme)" "yes"
run_task "" 90; RC=$?
naps 4
chk "跑完还开着 —— 不是我们开的就不该我们关" "$(hasme)" "yes"
# 起点写的是短名 pkg/.Cls,A11yGate 内部用的是全名 pkg/pkg.Cls。
# 按字符串比的话它认不出表里已经有自己,会再追加一遍全名 —— 表里就有两项。
chk "表里只有一项是我们,没被重复追加" "$(nme)" "1"
echo "   收工后的表: $(svcs)"
echo

# ---------------------------------------------------------------- 3b
echo "== 3b. 残留的写成短名,收工时也要摘干净 =="
# 同一个组件在这张表里有两种等价写法:pkg/pkg.Cls 和 pkg/.Cls。机主从设置里开的、
# 脚本写的、AccessibilityManagerService 归一化后的,未必是同一种。
# 真机上实测 AMS 会在写入后把短名展开成全名 —— 按字符串比就会「明明有却认不出」,
# 关的时候摘不掉,权限留着而且不报错。这一条专门盯这个。
reset "$SELF"
STORED=$(svcs)
setpref a11y_opened_by_us 1
run_task "" 90; RC=$?
naps 4
echo "   起点表里存的写法: $STORED"
chk "短名写法的残留也摘掉了" "$(hasme)" "no"
echo

# ---------------------------------------------------------------- 4
echo "== 4. 进程被杀也要能收场 =="
reset ""
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
# 一看到权限被打开就立刻杀 —— 要的就是 close() 没机会跑的那个窗口
KILLED=no
for _ in $(seq 1 120); do [ "$(hasme)" = "yes" ] && { KILLED=yes; break; }; naps 1; done
# 不能用 force-stop:AccessibilityManagerService 收到包被强停的通知后,
# 会主动把这个包的无障碍服务从 enabled 表里剔掉 —— 那不是「进程被杀」,
# 是「系统替我们收拾了」,测不到 MARK 这条路。直接杀进程才是要模拟的场景。
# (顺带说明:机主自己去设置里强停 app,权限会自动清干净,不用靠 MARK。)
PID=$(sh pidof $A | tr -d '
' | awk '{print $1}')
[ -n "$PID" ] && sh kill -9 "$PID" >/dev/null 2>&1 || no "找不到 app 进程,杀不掉"
naps 3
chk "被杀之后权限残留着(这就是要收拾的烂摊子)" "$KILLED" "yes"
chk "确认残留" "$(hasme)" "yes"
run_task "" 90; RC=$?
naps 4
chk "下一轮跑完,上一轮的残留一并清掉" "$(hasme)" "no"
sh logcat -d -s WPGate:* | grep "没关成" | sed 's/^.*WPGate *: /     /' | tail -2
echo

# ---------------------------------------------------------------- 5
# 注意这一条测的是「一轮结束不等于任务结束」:AgentService 在 Watch 分支里直接
# return,根本没走 finish(),close() 不会被调用。结论对,但不是 A11yGate 里那道
# 闸门起的作用 —— 闸门要到 5b 才被真正打到。两者必须分开说,否则会以为测过了。
echo "== 5. 长时任务的一轮结束,权限不能关 =="
reset ""
run_task "" 90 >/dev/null; naps 4   # 先正常跑一轮,确保起点干净
setpref watch_goal "盯着降价"
# 剩余轮数必须一起给:Watch.record 会把它减一,减到 0 就 Watch.clear ——
# 只塞 goal 的话默认 1 轮,任务一结束计划就没了,close() 自然看不到它。
setprefi watch_rounds_left 5
sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
sh settings put secure accessibility_enabled 0 >/dev/null 2>&1
naps 2
run_task "" 90; RC=$?
naps 4
chk "跑完权限留着 —— 关掉等于把长时任务的触发器拆了" "$(hasme)" "yes"
sh logcat -d -s WPGate:* | grep "长时任务" | sed 's/^.*WPGate *: /     /' | tail -1
sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
echo

# ---------------------------------------------------------------- 5b
echo "== 5b. 长时任务撞上机主打字、提前收尾,也不能关权限 =="
# 这才是 A11yGate 里那道 Watch 闸门唯一真正被走到的路径。
# 盯降价的某一轮亮屏触发,机主正好在打字 —— agent 按设计不开工、提前收尾。
# 如果这时把无障碍关掉,亮屏触发器就跟着没了,后面所有轮次一起哑掉,
# 现象是「那个盯着的任务后来就没动静了」,而且不报任何错。
reset ""
setpref watch_goal "盯着降价"
setprefi watch_rounds_left 5
sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
sh settings put secure accessibility_enabled 0 >/dev/null 2>&1
naps 2
if raise_ime; then
  sh logcat -c
  bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
  # 全程真的在敲,不是把键盘顶着。
  #
  # 判据现在要求「键盘弹着**且**输入框最近在变」—— 只顶着键盘不打字,20 秒后
  # 就被判成「机主已经走开」,agent 照常开工,这一条测不到要测的东西。
  # 改之前这里恰好是绿的,因为旧判据只看键盘在不在。
  BAILED=no
  T0=$(date +%s)
  while [ $(( $(date +%s) - T0 )) -lt 215 ]; do
    owner_types
    naps 2
    sh logcat -d -s WPSvc:* | grep -q "收工: 机主一直在打字" && { BAILED=yes; break; }
  done
  naps 4
  chk "机主在打字,这一轮主动不开工" "$BAILED" "yes"
  chk "提前收尾了,权限仍然留着" "$(hasme)" "yes"
  # 只看结果不够:权限留着也可能是别的原因(比如根本没打开过)。
  # 要闸门自己那行日志才算归因成功。
  if sh logcat -d -s WPGate:* | grep -q "长时任务"; then ok "是那道闸门拦下来的(有日志为证)"
  else no "权限是留着,但闸门没打日志 —— 留着的理由存疑"; fi
  sh input -d 0 keyevent 111 >/dev/null 2>&1
else
  echo "   输入法没叫起来,这一条测不了(不计入结论)"
fi
echo

# ---------------------------------------------------------------- 6
echo "== 6. 机主说了别自动,就真的别动 =="
reset ""
setpref A11Y_AUTO 0
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
SEEN=no
for _ in $(seq 1 25); do [ "$(hasme)" = "yes" ] && { SEEN=yes; break; }; naps 1; done
chk "没有自作主张打开" "$SEEN" "no"
sh logcat -d -s WPSvc:* | grep -E "没有眼睛|没能自动" | sed 's/^.*WPSvc *: /     /' | tail -2
echo

echo "== 收尾:把无障碍恢复成测试前的样子 =="
reset "$SELF"
echo "   $(svcs)"
echo
echo "== 结论:$PASS 条过,$FAIL 条不过 =="
[ "$FAIL" -eq 0 ] || exit 1
