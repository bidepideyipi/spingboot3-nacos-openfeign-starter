#!/usr/bin/env bash
# =============================================================================
# 线程池隔离 (ThreadPoolBulkhead) 配置生效验证脚本
#
# 验证目标配置:
#   max-thread-pool-size: 10   (隔离池最大线程数 = 同时等待下游的最大线程数)
#   core-thread-pool-size: 3   (核心线程数)
#   queue-capacity: 0          (不排队,池满立即拒绝)
#   keep-alive-duration: 1m    (空闲线程存活时长)
#
# 前置条件:
#   1. Nacos 已启动 (127.0.0.1:8848)
#   2. service-a 已启动 (端口 8081)
#   3. service-b 已启动 (端口 8082)
# =============================================================================
set -u

SERVICE_B="http://localhost:8082"
SERVICE_A="http://localhost:8081"
DOWNSTREAM_DELAY_MS=5000   # 下游固定延迟 5s
CONCURRENT=15              # 并发请求数 (> max-thread-pool-size=10,触发拒绝)
REJECTED=$((CONCURRENT > 10 ? CONCURRENT - 10 : 0))

c_ok() { printf "\033[32m%s\033[0m\n" "$1"; }
c_no() { printf "\033[31m%s\033[0m\n" "$1"; }
c_y()  { printf "\033[33m%s\033[0m\n" "$1"; }
hr()   { printf -- "----------------------------------------------------------------\n"; }

# ----------------------------- 步骤1: 配置加载验证 -----------------------------
echo
c_y "【步骤1】配置加载验证 - 读取注册中心中各 bulkhead 实例的实际配置"
hr
INFO=$(curl -s "${SERVICE_B}/api/bulkhead/info")
echo "$INFO" | python3 -m json.tool 2>/dev/null || echo "$INFO"
echo
echo "对照期望值 (slowApi / externalUserApi / service-a / resetCounter 应均为):"
echo '  maxThreadPoolSize = 10, coreThreadPoolSize = 3, queueCapacity = 0, keepAliveDuration = "PT1M"'
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
  c_no "   请确认 service-a 已重新编译并重启 (新增了 /api/slow/fixed 端点)"
  c_no "   命令: cd service-a && mvn -DskipTests clean package && 重启 service-a"
  exit 1
fi
c_ok "✅ service-a 的 /api/slow/fixed 可用"

# ----------------------------- 步骤3: 重置下游计数器 -----------------------------
echo
c_y "【步骤3】重置 service-a 计数器,确保干净环境"
hr
curl -s "${SERVICE_A}/api/slow/reset"; echo

# ----------------------------- 步骤4: 并发打满隔离池 -----------------------------
echo
c_y "【步骤4】并发 ${CONCURRENT} 个请求 (下游延迟 ${DOWNSTREAM_DELAY_MS}ms),观察隔离池行为"
hr
echo "期望: 前 10 个被接受(等待下游),后 ${REJECTED} 个立即被拒绝(BulkheadFullException)"
echo

TMP=$(mktemp -d)
START=$(python3 -c 'import time;print(time.time())')

for i in $(seq 1 $CONCURRENT); do
  (
    T0=$(python3 -c 'import time;print(time.time())')
    RESP=$(curl -s "${SERVICE_B}/api/external/slow/fixed?ms=${DOWNSTREAM_DELAY_MS}")
    T1=$(python3 -c 'import time;print(time.time())')
    RT=$(python3 -c "print(f'{$T1-$T0:.3f}')")
    # 判断结果类型: success / blocked / degraded / other
    if echo "$RESP" | grep -q '"blocked":true'; then TYPE="BLOCKED";
    elif echo "$RESP" | grep -q '"degraded":true'; then TYPE="DEGRADED";
    elif echo "$RESP" | grep -q '"success":true'; then TYPE="OK";
    else TYPE="OTHER"; fi
    # OTHER 时打印原始响应,便于定位问题
    if [ "$TYPE" = "OTHER" ]; then
      printf "req#%02d  rt=%5ss  %s  resp=%s\n" "$i" "$RT" "$TYPE" "$RESP"
    else
      printf "req#%02d  rt=%5ss  %s\n" "$i" "$RT" "$TYPE"
    fi
  ) &
done
wait

END=$(python3 -c 'import time;print(time.time())')
TOTAL=$(python3 -c "print(f'{$END-$START:.3f}')")
echo
echo "总耗时: ${TOTAL}s (期望: 接受的请求 ~${DOWNSTREAM_DELAY_MS}ms,被拒的请求 <1s)"

# ----------------------------- 步骤5: 运行时指标 -----------------------------
echo
c_y "【步骤5】查看运行时指标 (请求结束后,池中线程仍存活,可观察 threadPoolSize)"
hr
sleep 1
METRICS=$(curl -s "${SERVICE_B}/api/bulkhead/metrics")
echo "$METRICS" | python3 -m json.tool 2>/dev/null || echo "$METRICS"
echo
echo "重点观察 slowApi 实例:"
echo "  - maxThreadPoolSize 应为 10"
echo "  - threadPoolSize(实际) 在压力下应增长到 10 (从 core=3 增长)"
echo "  - queueCapacity 应为 0, queueDepth(当前队列) 恒为 0"

# ----------------------------- 步骤6: keep-alive 验证 -----------------------------
echo
c_y "【步骤6】keep-alive-duration 验证 (空闲 65s 后,线程数应从 10 回落到 core=3)"
hr
echo "等待 65 秒让超过 core 的空闲线程被回收..."
sleep 65
METRICS2=$(curl -s "${SERVICE_B}/api/bulkhead/metrics")
echo "65s 后指标:"
echo "$METRICS2" | python3 -m json.tool 2>/dev/null || echo "$METRICS2"
echo
echo "期望: slowApi 的 threadPoolSize(实际) 从 10 回落到 3 (coreThreadPoolSize)"

echo
c_ok "验证完成。"
