#!/usr/bin/env bash
# 模拟 service-a 与 Nacos 之间心跳异常：阻断 gRPC 端口 9848
# 用法: ./sim-heartbeat-fail.sh
#   阻断后按回车恢复
set -u

NACOS=http://127.0.0.1:8848
GRPC_PORT=9848
PF_RULES="/tmp/nacos-pf-block.conf"

state() {
  curl -s -m 2 "$NACOS/nacos/v1/ns/instance/list?serviceName=service-a" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
    hosts = d.get("hosts") or []
    if not hosts:
        print("NO INSTANCES")
    for h in hosts:
        print("  ip=%s:%s healthy=%s ephemeral=%s beat=%sms timeout=%sms del=%sms" % (h["ip"], h["port"], h["healthy"], h["ephemeral"], h.get("instanceHeartBeatInterval"), h.get("instanceHeartBeatTimeOut"), h.get("ipDeleteTimeout")))
except Exception as e:
    print("查询失败:", e)
'
}

echo "=== [0] 阻断前状态 ==="
state

echo
echo "=== [1] 写入 pf 规则阻断 127.0.0.1:${GRPC_PORT} (gRPC) ==="
cat > "$PF_RULES" <<EOF
block drop out quick on lo0 proto tcp from any to 127.0.0.1 port = ${GRPC_PORT}
EOF
sudo pfctl -ef "$PF_RULES" 2>&1 | tail -2

echo
echo "=== [2] 轮询实例状态 (每 3s)，预期: ~15s healthy=false，~30s 实例被剔除 ==="
for i in $(seq 1 32); do
  printf "[%2ds] " $((i*3))
  state
  sleep 3
done

echo
echo "=== [3] 恢复：关闭 pf ==="
sudo pfctl -d 2>&1 | tail -1
rm -f "$PF_RULES"

echo
echo "=== [4] 恢复后等待 service-a 重新注册 ==="
sleep 8
state
