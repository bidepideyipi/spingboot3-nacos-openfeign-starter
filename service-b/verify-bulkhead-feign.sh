#!/usr/bin/env bash
# =============================================================================
# 线程池隔离 (ThreadPoolBulkhead) 配置生效验证脚本 —— Feign 路径
#
# 验证目标: service-b 经 Feign(由 communi-sdk 的 ServiceACaller)调用 service-a 时,
#           走名为 "service-a" 的隔离舱;并发打满后,池+队列满 → 立即拒绝。
#
# 调用链:
#   ${SERVICE_B}/api/slow/fixed
#     → AggregationController.slowFixed
#     → UserAggregationService.callFixedSlowApi
#     → ServiceACaller.execute (隔离舱 "service-a")
#     → Feign → service-a /api/slow/fixed
#
# 当前验证目标配置 (service-b/src/main/resources/application.yml):
#   max-thread-pool-size:  10   隔离池最大线程数
#   core-thread-pool-size: 10   核心线程数 (与 max 相等 → 线程不会被回收)
#   queue-capacity:        20   等待队列容量 (池满后排队,而非立即拒绝)
#   keep-alive-duration:   1m   空闲线程存活时长 (仅对超出 core 的线程生效)
#
# 关键推论:
#   隔离舱总容量 = max-thread-pool-size(10) + queue-capacity(20) = 30
#   - 并发 ≤ 30: 全部被接受 (10 个进线程 + 多余的进队列等待) → HTTP 200
#   - 并发 > 30: 超出部分立即被拒 (BulkheadFullException → RuntimeException → HTTP 500)
#
# 响应判断(Feign 路径返回纯字符串,无 JSON,改用 HTTP 状态码):
#   HTTP 200 = 接受(成功)   HTTP 500 = 被拒(隔离舱满)
#
# 下游延迟选用 2000ms:
#   所有调用延迟相同,便于观察隔离舱分批返回(每批 max 个,间隔 2000ms)。
#
# 前置条件:
#   1. Nacos 已启动 (127.0.0.1:8848) —— 仅用于服务发现
#   2. service-a 已启动 (端口 8081, 含 /api/slow/fixed 端点)
#   3. service-b 已启动 (端口 8082)
# =============================================================================
set -u

SERVICE_B="http://localhost:8082"
SERVICE_A="http://localhost:8081"
DOWNSTREAM_DELAY_MS=2000   # 下游固定延迟 2s
MAX_THREADS=10
QUEUE_CAP=20
CAPACITY=$((MAX_THREADS + QUEUE_CAP))   # 30
CONCURRENT=35                            # > 30,触发拒绝
REJECTED=$((CONCURRENT > CAPACITY ? CONCURRENT - CAPACITY : 0))
BULKHEAD="service-a"                     # 本脚本验证的隔离舱实例名

c_ok() { printf "\033[32m%s\033[0m\n" "$1"; }
c_no() { printf "\033[31m%s\033[0m\n" "$1"; }
c_y()  { printf "\033[33m%s\033[0m\n" "$1"; }
hr()   { printf -- "----------------------------------------------------------------\n"; }

# ----------------------------- 步骤1: 配置加载验证 -----------------------------
echo
c_y "【步骤1】配置加载验证 - 读取 service-b 进程内 Resilience4j 各 bulkhead 实例的实际配置"
hr
echo "(配置来源: 本地 application.yml → Resilience4j 自动装配 → ThreadPoolBulkheadRegistry,与 Nacos 无关)"
echo
INFO=$(curl -s "${SERVICE_B}/api/bulkhead/info")
echo "$INFO" | python3 -m json.tool 2>/dev/null || echo "$INFO"
echo
echo "对照期望值 (${BULKHEAD} 应为):"
echo '  maxThreadPoolSize = 10, coreThreadPoolSize = 10, queueCapacity = 20, keepAliveDuration = "PT1M"'
echo
read -r -p "确认配置值正确后按回车继续..." _

# ----------------------------- 步骤2: 预检下游接口 -----------------------------
echo
c_y "【步骤2】预检 service-a 的 /api/slow/fixed 接口是否可用"
hr
PREFLIGHT=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_A}/api/slow/fixed?ms=100")
echo "HTTP 状态码: ${PREFLIGHT}"
if [ "$PREFLIGHT" != "200" ]; then
  c_no "❌ service-a 的 /api/slow/fixed 不可用 (返回 ${PREFLIGHT})"
  c_no "   请确认 service-a 已启动"
  exit 1
fi
c_ok "✅ service-a 的 /api/slow/fixed 可用"

# ----------------------------- 步骤3: 预检 Feign 路径 -----------------------------
echo
c_y "【步骤3】预检 service-b 的 Feign 路径 /api/slow/fixed 是否可用"
hr
FEIGN_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_B}/api/slow/fixed?ms=100")
echo "HTTP 状态码: ${FEIGN_CODE}"
if [ "$FEIGN_CODE" != "200" ]; then
  c_no "❌ service-b 的 Feign 路径不可用 (返回 ${FEIGN_CODE})"
  c_no "   请确认 service-b 已重新编译并重启 (AggregationController 含 /api/slow/fixed)"
  exit 1
fi
c_ok "✅ service-b 的 Feign 路径可用"

# ----------------------------- 步骤4: 重置下游计数器 -----------------------------
echo
c_y "【步骤4】重置 service-a 计数器,确保干净环境"
hr
curl -s "${SERVICE_A}/api/slow/reset"; echo

# ----------------------------- 步骤5: 并发打满隔离舱 -----------------------------
echo
c_y "【步骤5】并发 ${CONCURRENT} 个请求 (下游延迟 ${DOWNSTREAM_DELAY_MS}ms),观察 ${BULKHEAD} 隔离舱行为"
hr
echo "隔离舱总容量 = max(${MAX_THREADS}) + queue(${QUEUE_CAP}) = ${CAPACITY}"
echo "期望: 前 ${CAPACITY} 个被接受 (HTTP 200),后 ${REJECTED} 个立即被拒 (HTTP 500)"
echo "      由于 queue-capacity=${QUEUE_CAP},接受的请求会分批返回 (每批 ${MAX_THREADS} 个,间隔 ${DOWNSTREAM_DELAY_MS}ms)"
echo

TMP=$(mktemp -d)
START=$(python3 -c 'import time;print(time.time())')

# 后台并发发起所有请求(经 Feign 路径)
for i in $(seq 1 $CONCURRENT); do
  (
    T0=$(python3 -c 'import time;print(time.time())')
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_B}/api/slow/fixed?ms=${DOWNSTREAM_DELAY_MS}")
    T1=$(python3 -c 'import time;print(time.time())')
    RT=$(python3 -c "print(f'{$T1-$T0:.3f}')")
    if [ "$CODE" = "200" ]; then TYPE="OK(接受)";
    elif [ "$CODE" = "500" ]; then TYPE="REJECTED(拒)";
    else TYPE="OTHER($CODE)"; fi
    printf "req#%02d  rt=%5ss  HTTP %s  %s\n" "$i" "$RT" "$CODE" "$TYPE"
  ) &
done

# ----------------------------- 步骤6: 运行时指标采样 (与步骤5并发进行) -----------------------------
echo
c_y "【步骤6】运行时指标采样 (在并发压力期间每 1s 采样一次,观察 ${BULKHEAD} 线程增长与队列堆积)"
hr
# 采样时长略大于总执行时间 (3 批 × 2s ≈ 6s),采 7 次
for s in $(seq 1 7); do
  sleep 1
  M=$(curl -s "${SERVICE_B}/api/bulkhead/metrics" | python3 -c '
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print("  (解析失败)")
    sys.exit(0)
b=d.get("service-a",{})
print(f"  t={$s}s  threadPoolSize={b.get(\"threadPoolSize(实际)\",\"?\")}  "
      f"active={b.get(\"activeThreadCount(忙碌)\",\"?\")}  "
      f"queueDepth={b.get(\"queueDepth(当前队列)\",\"?\")}  "
      f"remainingQueue={b.get(\"remainingQueueCapacity\",\"?\")}")
' 2>/dev/null)
  echo "$M"
done

wait
END=$(python3 -c 'import time;print(time.time())')
TOTAL=$(python3 -c "print(f'{$END-$START:.3f}')")
echo
echo "总耗时: ${TOTAL}s"
echo "期望: 接受的请求分 3 批返回 (rt ≈ ${DOWNSTREAM_DELAY_MS}ms / $((2*DOWNSTREAM_DELAY_MS)) / $((3*DOWNSTREAM_DELAY_MS))),被拒的请求 rt < 1s"

# ----------------------------- 步骤7: 压力后指标 -----------------------------
echo
c_y "【步骤7】压力结束后指标 (${BULKHEAD} 线程池应已空闲,队列排空)"
hr
sleep 1
METRICS=$(curl -s "${SERVICE_B}/api/bulkhead/metrics")
echo "$METRICS" | python3 -m json.tool 2>/dev/null || echo "$METRICS"
echo
echo "重点观察 ${BULKHEAD} 实例:"
echo "  - threadPoolSize(实际) 应为 10 (已增长到 max,因 core=max=10 不会回落)"
echo "  - queueDepth(当前队列) 应为 0 (压力结束,队列已排空)"
echo "  - activeThreadCount(忙碌) 应为 0"

echo
c_ok "验证完成。"
