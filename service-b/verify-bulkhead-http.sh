#!/usr/bin/env bash
# =============================================================================
# HttpClient 直连路径 (service-a-http 隔离舱) 线程池隔离验证脚本
#
# 与 verify-bulkhead-feign.sh 的区别:
#   verify-bulkhead-feign.sh → /api/slow/fixed              → 隔离舱 service-a     (Feign 路径)
#   verify-bulkhead-http.sh → /api/service-a-direct/slow/fixed → 隔离舱 service-a-http (HttpClient 直连)
#
# 验证目标配置 (service-b/src/main/resources/application.yml):
#   max-thread-pool-size:  10
#   core-thread-pool-size: 10
#   queue-capacity:        20
#   keep-alive-duration:   1m
#   隔离舱总容量 = max(10) + queue(20) = 30
#
# 注意: 本项目已移除熔断器,本脚本验证的是"线程池隔离"(池+队列满即拒),
#       不是熔断。下游延迟用 2000ms(< 3s 慢阈值),熔断器本就不介入。
#
# 前置条件:
#   1. Nacos 已启动 (127.0.0.1:8848) —— 仅用于服务发现
#   2. service-a 已启动 (端口 8081, 含 /api/slow/fixed 端点)
#   3. service-b 已启动 (端口 8082)
# =============================================================================
set -u

SERVICE_B="http://localhost:8082"
SERVICE_A="http://localhost:8081"
DOWNSTREAM_DELAY_MS=2000   # 下游固定延迟 2s (< 3s 慢调用阈值,避免触发熔断)
MAX_THREADS=10
QUEUE_CAP=20
CAPACITY=$((MAX_THREADS + QUEUE_CAP))   # 30
CONCURRENT=35                            # > 30,触发拒绝
REJECTED=$((CONCURRENT > CAPACITY ? CONCURRENT - CAPACITY : 0))
BULKHEAD="service-a-http"                # 本次验证的隔离舱实例名

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
echo "重点确认 ${BULKHEAD} 实例应为:"
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
  c_no "   请确认 service-a 已启动且含 /api/slow/fixed 端点"
  exit 1
fi
c_ok "✅ service-a 的 /api/slow/fixed 可用"

# ----------------------------- 步骤3: 预检本服务直连端点 -----------------------------
echo
c_y "【步骤3】预检 service-b 的 /api/service-a-direct/slow/fixed 是否可用"
hr
PREFLIGHT2=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_B}/api/service-a-direct/slow/fixed?ms=100")
echo "HTTP 状态码: ${PREFLIGHT2}"
if [ "$PREFLIGHT2" != "200" ]; then
  c_no "❌ service-b 的 /api/service-a-direct/slow/fixed 不可用 (返回 ${PREFLIGHT2})"
  c_no "   请确认 service-b 已重新编译并重启 (新增了 /slow/fixed 端点)"
  c_no "   命令: cd service-b && mvn -DskipTests clean package && 重启 service-b"
  exit 1
fi
c_ok "✅ service-b 的 HttpClient 直连慢调用端点可用"

# ----------------------------- 步骤4: 并发打满隔离舱 -----------------------------
echo
c_y "【步骤4】并发 ${CONCURRENT} 个请求 (下游延迟 ${DOWNSTREAM_DELAY_MS}ms),观察 ${BULKHEAD} 隔离舱行为"
hr
echo "隔离舱总容量 = max(${MAX_THREADS}) + queue(${QUEUE_CAP}) = ${CAPACITY}"
echo "期望: 前 ${CAPACITY} 个被接受 (10 个立即执行 + ${QUEUE_CAP} 个排队等待),后 ${REJECTED} 个立即被拒 (BulkheadFullException)"
echo "      由于 queue-capacity=${QUEUE_CAP},接受的请求会分批返回 (每批 ${MAX_THREADS} 个,间隔 ${DOWNSTREAM_DELAY_MS}ms)"
echo

START=$(python3 -c 'import time;print(time.time())')

# 后台并发发起所有请求
for i in $(seq 1 $CONCURRENT); do
  (
    T0=$(python3 -c 'import time;print(time.time())')
    RESP=$(curl -s "${SERVICE_B}/api/service-a-direct/slow/fixed?ms=${DOWNSTREAM_DELAY_MS}")
    T1=$(python3 -c 'import time;print(time.time())')
    RT=$(python3 -c "print(f'{$T1-$T0:.3f}')")
    if echo "$RESP" | grep -q '"blocked":true'; then TYPE="BLOCKED";
    elif echo "$RESP" | grep -q '"degraded":true'; then TYPE="DEGRADED";
    elif echo "$RESP" | grep -q '"success":true'; then TYPE="OK";
    else TYPE="OTHER"; fi
    if [ "$TYPE" = "OTHER" ]; then
      printf "req#%02d  rt=%5ss  %s  resp=%s\n" "$i" "$RT" "$TYPE" "$RESP"
    else
      printf "req#%02d  rt=%5ss  %s\n" "$i" "$RT" "$TYPE"
    fi
  ) &
done

# ----------------------------- 步骤5: 运行时指标采样 (与步骤4并发进行) -----------------------------
echo
c_y "【步骤5】运行时指标采样 (在并发压力期间每 1s 采样一次,观察 ${BULKHEAD} 线程增长与队列堆积)"
hr
for s in $(seq 1 7); do
  sleep 1
  M=$(curl -s "${SERVICE_B}/api/bulkhead/metrics" | python3 -c '
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print("  (解析失败)")
    sys.exit(0)
b=d.get("service-a-http",{})
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

# ----------------------------- 步骤6: 压力后指标 -----------------------------
echo
c_y "【步骤6】压力结束后指标 (确认 ${BULKHEAD} 已空闲,且未影响其它隔离舱)"
hr
sleep 1
METRICS=$(curl -s "${SERVICE_B}/api/bulkhead/metrics")
echo "$METRICS" | python3 -m json.tool 2>/dev/null || echo "$METRICS"
echo
echo "重点观察:"
echo "  - service-a-http: threadPoolSize=10, queueDepth=0, activeThreadCount=0 (本路径已空闲)"
echo "  - service-a (Feign 路径隔离舱): 各项指标应未受影响(独立线程池,互不干扰)"

echo
c_ok "验证完成。"
