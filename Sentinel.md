# Sentinel 慢调用熔断演示

## 项目结构

```
microservices-demo/
├── service-a/                    # 服务提供者（8081）
│   └── SlowController.java       # 慢响应接口
└── service-b/                    # 服务消费者（8082）
    └── httpclient/
        ├── HttpClientTemplate.java   # HTTP客户端模板（集成Sentinel）
        ├── ExternalUserService.java  # 演示服务（配置熔断规则）
        └── ExternalUserController.java # 演示接口
```

## 核心组件

### 1. HttpClientTemplate

独立的 HTTP 客户端组件，集成 Sentinel 熔断能力：

- 连接池管理
- 自动 Sentinel 资源保护
- 熔断/限流响应封装
- 自动异常追踪

### 2. 慢调用比例熔断规则

Sentinel 独有的熔断策略，当响应时间超过阈值且比例达到设定值时触发熔断：

| 参数 | 值 | 说明 |
|------|----|----|
| 慢调用阈值 | 500ms | 响应时间超过500ms算慢调用 |
| 慢调用比例 | 50% | 5秒内50%请求超过500ms则熔断 |
| 熔断时长 | 10秒 | 熔断后10秒进入Half-Open |
| 最小请求数 | 5次 | 至少5个请求才开始统计 |

## 接口说明

### Service-A (端口8081)

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/slow/incremental` | GET | 慢响应接口，每次延迟增加200ms |
| `/api/slow/reset` | GET | 重置计数器 |
| `/api/slow/count` | GET | 查看当前请求次数 |

### Service-B (端口8082)

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/external/slow/call` | GET | 调用慢响应接口（演示熔断） |
| `/api/external/slow/reset` | POST | 重置Service-A计数器 |

## 演示步骤

### 1. 启动 Sentinel 控制台

```bash
# 下载 Sentinel Dashboard
wget https://github.com/alibaba/Sentinel/releases/download/1.8.6/sentinel-dashboard-1.8.6.jar

# 启动（默认端口8080，账号密码: sentinel/sentinel）
java -jar sentinel-dashboard-1.8.6.jar --server.port=8859
```

### 2. 启动 Service-A

```bash
cd service-a
mvn spring-boot:run
```

### 3. 启动 Service-B

```bash
cd service-b
mvn spring-boot:run
```

### 4. 观察熔断效果

**方式一：手动测试**

```bash
# 连续调用，观察响应时间递增和熔断
curl http://localhost:8082/api/external/slow/call
curl http://localhost:8082/api/external/slow/call
curl http://localhost:8082/api/external/slow/call
# ...
```

**方式二：循环测试**

```bash
# 快速连续调用10次
for i in {1..10}; do
  echo "=== 第 $i 次调用 ==="
  curl http://localhost:8082/api/external/slow/call
  echo ""
done
```

**方式三：并发测试**

```bash
# 并发请求，更快触发熔断
for i in {1..10}; do
  curl http://localhost:8082/api/external/slow/call &
done
wait
```

### 5. 重置测试

```bash
# 重置Service-A计数器，重新开始测试
curl -X POST http://localhost:8082/api/external/slow/reset
```

## 预期输出

```
=== 第 1 次调用 ===
{"success":true,"statusCode":200,"data":"第 1 次请求完成 (延迟 200ms)","message":null,"degraded":false,"blocked":false}

=== 第 2 次调用 ===
{"success":true,"statusCode":200,"data":"第 2 次请求完成 (延迟 400ms)","message":null,"degraded":false,"blocked":false}

=== 第 3 次调用 ===
{"success":true,"statusCode":200,"data":"第 3 次请求完成 (延迟 600ms)","message":null,"degraded":false,"blocked":false}
⚠️ 响应时间600ms，超过500ms阈值，计入慢调用

=== 第 4 次调用 ===
{"success":true,"statusCode":200,"data":"第 4 次请求完成 (延迟 800ms)","message":null,"degraded":false,"blocked":false}
⚠️ 响应时间800ms，超过500ms阈值，计入慢调用

=== 第 5 次调用 ===
{"success":false,"statusCode":-1,"data":null,"message":"服务响应过慢，已触发熔断保护，请稍后重试","degraded":true,"blocked":false}
🔴 慢调用比例达到50%，触发熔断！

=== 第 6-10 次调用 ===
{"success":false,"statusCode":false,"data":null,"message":"服务响应过慢，已触发熔断保护，请稍后重试","degraded":true,"blocked":false}
🔴 熔断中，所有请求直接返回降级响应

... 10秒后 ...

=== 第 11 次调用 ===
{"success":true,"statusCode":200,"data":"第 11 次请求完成 (延迟 2200ms)","message":null,"degraded":false,"blocked":false}
✅ 熔断恢复，进入Half-Open状态
```

## Sentinel 控制台观察

访问 `http://localhost:8080` 登录 Sentinel 控制台：

1. **实时监控** - 查看 QPS、响应时间
2. **熔断规则** - 确认 `slowApi` 资源的规则配置
3. **熔断状态** - 观察熔断触发和恢复过程

## 熔断状态机

```
        Closed
         │
         │ 慢调用比例 >= 50%
         │ (5秒内)
         ↓
        Open
         │
         │ 10秒后
         ↓
    Half-Open
         │
         │ 成功 -> Closed
         │ 失败 -> Open
```

## 技术要点

### Service-A: 原子计数器实现

```java
private final AtomicInteger requestCounter = new AtomicInteger(0);

public String incrementalSlow() {
    int count = requestCounter.incrementAndGet();
    int delayMs = count * 200;  // 每次增加200ms
    Thread.sleep(delayMs);
    return String.format("第 %d 次请求完成 (延迟 %dms)", count, delayMs);
}
```

### Service-B: Sentinel 规则配置

```java
DegradeRule degradeRule = new DegradeRule();
degradeRule.setResource("slowApi");
degradeRule.setGrade(RuleConstant.DEGRADE_GRADE_SLOW_REQUEST_RATIO);
degradeRule.setCount(500);              // 慢调用阈值500ms
degradeRule.setSlowRatioThreshold(0.5);  // 慢调用比例50%
degradeRule.setTimeWindow(10);          // 熔断时长10秒
degradeRule.setMinRequestAmount(5);      // 最小请求数5次
degradeRule.setStatIntervalMs(5000);    // 统计窗口5秒
```

### Service-B: HTTP客户端集成

```java
Entry entry = SphU.entry(resourceName, EntryType.OUT);
try {
    // 执行HTTP请求
    response = httpClient.execute(request);
} catch (BlockException e) {
    // 熔断/限流处理
    return HttpResponse.degraded("服务降级");
} catch (Exception e) {
    // 记录异常到Sentinel统计
    Tracer.traceEntry(e, entry);
} finally {
    entry.exit();
}
```

## 与 Hystrix 对比

| 特性 | Hystrix | Sentinel |
|------|---------|----------|
| 慢调用比例熔断 | ❌ | ✅ |
| 慢调用数量熔断 | ❌ | ✅ |
| 异常比例熔断 | ✅ | ✅ |
| 异常数量熔断 | ✅ | ✅ |
| 半开状态试探 | 1个请求 | 所有请求 |
| 统计窗口 | RxJava滑动窗口 | LeapArray时间轮 |
