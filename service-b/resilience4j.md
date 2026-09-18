# Resilience4j 韧性方案记录

> 本项目用 Resilience4j 应对"下游变慢/过载拖垮本服务"的问题。记录两套方案：
>
> - **方案 A：慢调用熔断（CircuitBreaker）**——事后统计、整体熔断。**当前未启用**（已从代码与配置中移除），保留文档供选型对比与将来恢复参考。
> - **方案 B：线程池隔离（ThreadPoolBulkhead）**——事中保护、池满即拒。**当前启用**。
>
> 两套方案可独立使用，也可叠加使用。本项目目前只启用方案 B。

---

# 方案 A：慢调用熔断（CircuitBreaker）【当前未启用】

> 配置位置（已移除）：`application.yml` → `resilience4j.circuitbreaker.configs.default`
> 代码引用（已移除）：`HttpClientTemplate` 中的 `CircuitBreakerRegistry`、SDK `ServiceACaller` 的 `@CircuitBreaker`

## A.1 慢调用判定参数

| 参数 | 参考值 | 类型 | 作用 |
|---|---|---|---|
| `slow-call-duration-threshold` | `3s` | Duration | **慢调用门槛**。单次调用耗时超过此值即记为"慢调用"（即使最终返回 200 成功）。慢调用熔断的核心判定依据。 |
| `slow-call-rate-threshold` | `50` | 百分比 | **慢调用率阈值**。滑动窗口内慢调用比例达到此值时触发熔断。取值 1~100。 |
| `failure-rate-threshold` | `50` | 百分比 | 失败率阈值。窗口内失败率达到此值也触发熔断。与慢调用率是**两个独立维度**，任一达标即熔断。 |

> 关键认知：慢调用**不算失败**。下游可能一直返回 200，但响应越来越慢，慢调用率照样能触发熔断。

## A.2 统计窗口参数

| 参数 | 参考值 | 类型 | 作用 |
|---|---|---|---|
| `sliding-window-type` | `TIME_BASED` | 枚举 | 滑动窗口类型。`TIME_BASED` 基于时间，`COUNT_BASED` 基于调用次数。TIME_BASED 更能反映"当前"压力，与流量大小无关。 |
| `sliding-window-size` | `30` | Integer | 滑动窗口大小。**TIME_BASED 下为"秒数"**（30 = 最近 30 秒），COUNT_BASED 下为"调用次数"。注意是整数，不能写 `30s`。 |
| `minimum-number-of-calls` | `10` | Integer | **最小调用数**。窗口内调用总数未达此值时，即使慢调用率超阈值也**不熔断**。避免低流量下误判。 |

## A.3 状态机参数

| 参数 | 参考值 | 类型 | 作用 |
|---|---|---|---|
| `wait-duration-in-open-state` | `10s` | Duration | **OPEN 保持时长**。熔断打开后保持 10 秒，期间所有请求直接拒绝（`CallNotPermittedException`），过后自动转 HALF_OPEN。 |
| `permitted-number-of-calls-in-half-open-state` | `3` | Integer | **HALF_OPEN 试探数**。放行 3 次试探请求；都健康则回 CLOSED，有慢/失败则回 OPEN。 |
| `automatic-transition-from-open-to-half-open-enabled` | `true` | Boolean | 是否自动从 OPEN 转 HALF_OPEN（无需等下一次调用触发）。 |
| `register-health-indicator` | `true` | Boolean | 是否向 Actuator 健康检查注册熔断器状态（`/actuator/health` 可见）。 |

## A.4 状态机流转

```
        慢调用率≥50% 且 调用数≥10
   CLOSED ─────────────────────► OPEN
     ▲                              │
     │ 试探3次都健康                 │ 等 10s
     │                              ▼
     └──────────── HALF_OPEN ◄──────┘
                    │ 试探有慢/失败
                    └─► 回到 OPEN
```

## A.5 慢调用耗时如何被采集

启用熔断时，`HttpClientTemplate.execute()` 中调用开始记 `callStart`，结束把耗时报给熔断器：

```java
long callStart = System.nanoTime();
HttpResponse<T> result = doRequest(...);                              // 实际等下游
circuitBreaker.onSuccess(System.nanoTime() - callStart, TimeUnit.NANOSECONDS);  // 上报耗时
```

熔断器拿这个耗时与 `slow-call-duration-threshold (3s)` 比较，超过即记为慢调用，并更新滑动窗口内的慢调用率。

> 注意：耗时从"supplier 在隔离线程上开始执行"算起，**不含队列等待时间**（队列等待由方案 B 度量）。

---

# 方案 B：线程池隔离（ThreadPoolBulkhead）【当前启用】

> 配置位置：`application.yml` → `resilience4j.thread-pool-bulkhead.configs.default`
> 代码引用：`HttpClientTemplate` 中的 `ThreadPoolBulkheadRegistry`、SDK `ServiceACaller` 的 `@Bulkhead(type=THREADPOOL)`

## B.1 核心参数

| 参数 | 当前值 | 类型 | 作用 |
|---|---|---|---|
| `max-thread-pool-size` | `10` | Integer | **隔离池最大线程数** = 同时执行下游调用的最大线程数。超过 core 的线程在空闲 `keep-alive-duration` 后回收。 |
| `core-thread-pool-size` | `10` | Integer | **核心线程数**（常驻）。本项目 core=max=10，故所有线程均为核心线程，不会被回收。 |
| `queue-capacity` | `20` | Integer | **等待队列容量**。线程池满后，多余请求在此排队；队列也满时才立即拒绝。设为 `0` 表示不排队、池满即拒。 |
| `keep-alive-duration` | `1m` | Duration | **空闲线程存活时长**。仅对**超出 core** 的线程生效；core=max 时此参数无可见效果。 |

## B.2 容量与拒绝行为

```
隔离舱总容量 = max-thread-pool-size(10) + queue-capacity(20) = 30
```

| 并发请求数 | 行为 |
|---|---|
| ≤ 10 | 立即分配线程执行 |
| 11 ~ 30 | 进队列排队，等线程空闲后执行（调用线程阻塞等待） |
| > 30 | 立即抛 `BulkheadFullException`，返回 `blocked`，不排队、不阻塞 |

> 关键认知：`queue-capacity > 0` 时，被接受的请求会"排队等待"，响应时间随排队位置增长；只有"池+队列都满"才立即拒绝。若要"池满即拒、绝不排队"，把 `queue-capacity` 设为 `0`。

## B.3 公共隔离执行器 `BulkheadExecutor`（SDK）

隔离的"开关判断 + 提交到隔离池"逻辑抽到 SDK 的 `BulkheadExecutor`，供 **Feign 路径**（`ServiceACaller`）和 **HttpClient 路径**（`HttpClientTemplate`）复用，消除两处重复。

```java
@Component
@RequiredArgsConstructor
public class BulkheadExecutor {
    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;

    @Value("${resilience4j.thread-pool-bulkhead.enabled:true}")
    private boolean enabled;

    public <T> CompletableFuture<T> submit(String resource, Supplier<T> task) {
        if (!enabled) {
            return CompletableFuture.completedFuture(task.get());          // 关闭:调用线程直通
        }
        return threadPoolBulkheadRegistry.bulkhead(resource)              // 启用:提交到隔离池
                .executeSupplier(task).toCompletableFuture();             //      池+队列满时同步抛 BulkheadFullException
    }
}
```

> 放在 SDK（而非消费方）是为了让 SDK 内的 `ServiceACaller` 和消费方的 `HttpClientTemplate` 都能依赖它——依赖方向 `消费方 → SDK`，不反向。
>
> **开关统一**：`resilience4j.thread-pool-bulkhead.enabled` 只在此处读一次，两条路径同时生效。
>
> **只抽隔离层**：HTTP 专属的 `BulkheadFullException` 捕获、超时、`blocked`/`error` 兜底仍留在 `HttpClientTemplate`，不进通用执行器。

## B.4 两条调用路径复用 `BulkheadExecutor`

```
                  BulkheadExecutor (SDK, 公共)
                  ├─ enabled 开关
                  └─ registry.bulkhead(name).executeSupplier(task)
                        ▲                ▲
                        │                │
            委托 submit()           委托 submit()
                        │                │
        ServiceACaller(SDK)        HttpClientTemplate(service-b)
        Feign 路径                  HttpClient 路径
        资源名 service-a            资源名 service-a-http / externalUserApi / slowApi / resetCounter
```

### B.4.1 Feign 路径 — `ServiceACaller`（SDK）

```java
@Component
@RequiredArgsConstructor
public class ServiceACaller {
    private static final String RESOURCE = "service-a";
    private final BulkheadExecutor bulkheadExecutor;

    public <T> CompletableFuture<T> execute(Supplier<T> feignCall) {
        return bulkheadExecutor.submit(RESOURCE, feignCall);
    }
    public CompletableFuture<Void> executeVoid(Runnable feignCall) {
        return bulkheadExecutor.submit(RESOURCE, () -> { feignCall.run(); return (Void) null; });
    }
}
```

> 不再用 `@Bulkhead` 注解（注解切面无法被自定义属性动态关闭），改编程式委托 `BulkheadExecutor`，从而支持 `enabled` 开关。Feign 调用在 `service-a` 隔离池中执行。

### B.4.2 HttpClient 路径 — `HttpClientTemplate`（service-b）

```java
private <T> HttpResponse<T> execute(HttpRequestBase request, String resourceName, Class<T> responseType) {
    long startTime = System.currentTimeMillis();
    try {
        CompletableFuture<HttpResponse<T>> future = bulkheadExecutor.submit(
                resourceName,
                () -> doRequest(request, responseType, startTime)
        );
        return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (BulkheadFullException e) { return HttpResponse.blocked(...); }   // 池+队列满
    catch (TimeoutException e)        { return HttpResponse.error(...); }
    catch (ExecutionException e)      { return HttpResponse.error(...); }     // 异步任务抛错
    catch (Exception e)               { return HttpResponse.error(...); }     // 关闭隔离时同步抛错兜底
    }
}
```

> HttpClient 调用按 `resourceName` 走对应隔离池（`service-a-http` / `externalUserApi` / `slowApi` / `resetCounter`）。HTTP 专属兜底保留在本类。

### B.4.3 同一下游、两种客户端的独立隔离

service-a 既被 Feign 调（`ServiceACaller` → `service-a` 池），也被 HttpClient 直连调（`ServiceADirectService` → `service-a-http` 池）。两个池**独立**，互不影响：

| 路径 | 客户端 | 资源名(隔离池) | 入口 |
|---|---|---|---|
| Feign | `ServiceAClient`（Feign 代理） | `service-a` | `UserAggregationService` |
| HttpClient 直连 | `CloseableHttpClient` | `service-a-http` | `ServiceADirectService` → `/api/service-a-direct/*` |
| HttpClient 外部用户 | `CloseableHttpClient` | `externalUserApi` | `ExternalUserService` → `/api/external/*` |


---

## B.5 `RemainingCapacity` 详解与背压预判（MQ 消费场景）

> 配置位置：`application.yml` → `resilience4j.thread-pool-bulkhead.configs.default`
> 代码引用：`BulkheadExecutor.getRemainingCapacity()`、`ServiceAPullConsumer.consumeLoop()` 中的背压预判

### B.5.1 `RemainingCapacity` 的定义

`BulkheadExecutor.getRemainingCapacity(resource)` 返回的是 **可用线程数 + 剩余队列容量** 的总和，不是 `queue-capacity` 本身：

```java
public int getRemainingCapacity(String resource) {
    if (!enabled) {
        return Integer.MAX_VALUE;          // 关闭隔离 = 无容量限制
    }
    ThreadPoolBulkhead bulkhead = threadPoolBulkheadRegistry.bulkhead(resource);
    ThreadPoolBulkhead.Metrics m = bulkhead.getMetrics();
    return m.getAvailableThreadCount() + m.getRemainingQueueCapacity();
}
```

公式：

```
RemainingCapacity = availableThreadCount + remainingQueueCapacity
                  = (max-thread-pool-size - 活跃线程数) + (queue-capacity - 排队任务数)
                  = (max-thread-pool-size + queue-capacity) - 在途任务数
                  = 总容量 - 在途任务数
```

### B.5.2 当前配置下的容量分解

```yaml
resilience4j:
  thread-pool-bulkhead:
    configs:
      default:
        max-thread-pool-size: 10   # 隔离池线程数
        queue-capacity: 20         # 池满后排队容量
```

**总容量 = 10 + 20 = 30**

```
总容量 30 = 隔离池线程(10) + 等待队列(20)

┌─────────────────────────────────────────┐
│ 隔离池 (max-thread-pool-size=10)          │ ← 实际执行下游调用的线程
│ ▓▓▓▓▓▓▓▓░░                              │ ← 8 个在跑,2 个空闲
├─────────────────────────────────────────┤
│ 等待队列 (queue-capacity=20)              │ ← 池满后任务在这里排队
│ ░░░░░░░░░░░░░░░░░░░░                     │ ← 0 个在排队,20 个空位
└─────────────────────────────────────────┘

RemainingCapacity = 空闲线程(2) + 空位队列(20) = 22
```

### B.5.3 日志对照表

| 日志 | 在途任务数 | 计算 | 含义 |
|---|---|---|---|
| `RemainingCapacity=30` | 0 | 30 - 0 | 空闲，可全速提交 |
| `RemainingCapacity=22` | 8 | 30 - 8 | 8 个实例在调 service-a（2 秒延迟中） |
| `RemainingCapacity=21` | 9 | 30 - 9 | 9 个实例在调 service-a |
| `RemainingCapacity=10` | 20 | 30 - 20 | 池满 + 队列排了一半，开始警戒 |
| `RemainingCapacity=0`  | 30 | 30 - 30 | 池+队列全满，再提交必被拒 |

### B.5.4 为什么这么设计

`RemainingCapacity` 的语义是"**还能再提交多少任务而不被拒绝**"：
- 提交的任务先尝试占用空闲线程（前 10 个）
- 池满后任务进队列等待（接下来 20 个）
- 队列也满 → 第 31 个任务直接抛 `BulkheadFullException`

所以消费端用它做背压预判：`RemainingCapacity > 0` 就可以提交，`= 0` 就别 poll 了（提交了也会被拒）。

### B.5.5 消费端的背压预判逻辑

`ServiceAPullConsumer.consumeLoop()` 在 `poll` 之前先查 `RemainingCapacity`，根据阈值决定是否拉消息：

```java
int rc = serviceACaller.getRemainingCapacity();
if (rc <= 10) {        // ← 阈值是 10,不是 0
    sleepQuietly(capacityCheckIntervalMs);
    continue;          // 不 poll,等容量恢复
}
log.info("[实例{}] RemainingCapacity={}", idx, rc);
List<MessageExt> msgs = c.poll(pullTimeoutMs);
...
```

### B.5.6 阈值取值对比

| 阈值 | 含义 | 背压触发时机 | 适用场景 |
|---|---|---|---|
| `<= 0` | 池+队列都满才背压 | 用满线程池 + 队列才开始 | 最大化吞吐，背压最晚 |
| `<= 10`（当前） | 队列开始被使用就背压 | 池满后队列一被用就停 poll | 平衡吞吐与保护，背压较早 |
| `<= 20` | 池满就背压 | 隔离池一满就停 poll | 强保护，不让任务排队 |
| `<= 30` | 几乎不消费 | 一有任务在途就停 | 极端保守，几乎不并发 |

> 当前 `<= 10` 的语义：**保留 10 个线程容量给在途任务收尾**，比 `<= 0` 更激进，让背压更早触发，避免堆积在队列里等待。

### B.5.7 验证背压的完整链路

当 service-a 变慢时，按顺序观察：

1. **`RemainingCapacity` 持续下降**：30 → 22 → 15 → 10 → 5 → 0
2. **到阈值（10）后**：消费端停止 poll，日志只剩 `RemainingCapacity=10` 反复刷
3. **若继续恶化到 0**：新 poll 的消息提交 bulkhead 会抛 `BulkheadFullException`
4. **触发 seek 回退重投**：日志出现 `[实例X] 消息处理失败,seek 回退重投`
5. **所有实例都在等**：不再 poll → broker 端 `Diff Total` 开始堆积

这才是背压从隔离舱传到 broker 的完整链路：

```
service-a 慢
   ↓
bulkhead 线程池占满 + 队列排满
   ↓
RemainingCapacity 降到阈值
   ↓
消费端停止 poll(背压预判)
   ↓
broker 端消息堆积(Diff Total 上升)
   ↓
service-a 恢复 → 线程释放 → RemainingCapacity 回升 → 消费端恢复 poll → 堆积下降
```

---

# 两套方案对比与选型

| 维度 | 方案 A 慢调用熔断 | 方案 B 线程池隔离 |
|---|---|---|
| 感知方式 | 事后统计（滑动窗口慢调用率） | 事中保护（池+队列容量） |
| 触发条件 | 慢调用率/失败率达阈值 | 并发数超过 max+queue |
| 拒绝时机 | OPEN 期间拒绝**所有**请求 | 仅拒绝超出容量的请求 |
| 恢复方式 | OPEN→HALF_OPEN 试探→CLOSED（需等待） | 下游恢复后线程释放，立即恢复（无需等待） |
| 线程开销 | 无额外线程（在调用线程统计） | 需独立线程池（占用额外线程） |
| 适用场景 | 下游持续劣化、需整体熔断止损 | 防止本服务线程被下游拖堆积 |

## 叠加使用时的执行顺序（如同时启用）

```
① circuitBreaker.acquirePermission()   OPEN 时直接降级，不占隔离池线程
② bulkhead.executeSupplier(...)        提交到隔离池，池+队列满立即拒绝
③ doRequest(...)                        实际等下游
④ circuitBreaker.onSuccess(耗时)        上报耗时，更新慢调用率
```

| 故障形态 | 谁先起作用 | 表现 |
|---|---|---|
| 下游偶尔慢 | 熔断器统计 | 不熔断，正常返回 |
| 下游持续慢（>3s 占多数） | 熔断器 | 累计到 50% 慢调用率 → OPEN → 后续直接 `degraded` |
| 下游慢 + 高并发 | 隔离池 | 线程占满 + 队列满 → 超出部分立即 `blocked` |
| 下游慢 + 高并发 + 持续 | 两者叠加 | 先 `blocked` 兜住瞬时流量，熔断器统计够后 OPEN 全面熔断 |

> 本项目当前**只启用方案 B**。如需恢复方案 A，需在 `application.yml` 加回 `resilience4j.circuitbreaker` 段、在 `HttpClientTemplate` 加回 `CircuitBreakerRegistry` 用法、在 `ServiceACaller` 加回 `@CircuitBreaker`、并在 `pom.xml` 加回 `spring-cloud-starter-circuitbreaker-resilience4j`（同时恢复 Feign 降级）。

---

# 相关资源绑定

资源名（如 `slowApi` / `service-a` / `service-a-http` / `externalUserApi` / `resetCounter`）在三处必须一致才能生效：

| 位置 | 文件 | 说明 |
|---|---|---|
| 配置 | `application.yml` → `thread-pool-bulkhead.instances.<name>` | 隔离池实例（方案 B，当前启用） |
| 配置 | `application.yml` → `thread-pool-bulkhead.enabled` | 隔离开关（自定义属性，默认 true） |
| 配置 | `application.yml` → `circuitbreaker.instances.<name>` | 熔断器实例（方案 A，当前未启用） |
| 代码 | `BulkheadExecutor.java`（SDK） | 按 `resource` 取隔离舱 + `enabled` 开关（公共复用） |
| 代码 | `ServiceACaller.java`（SDK） | 资源名常量 `service-a`（Feign 路径） |
| 代码 | `ServiceADirectService.java` | 资源名常量 `service-a-http`（HttpClient 直连路径） |
| 代码 | `ExternalUserService.java` | 资源名常量 `externalUserApi` / `slowApi` / `resetCounter` |
| 代码 | `HttpClientTemplate.java` | 调用 `bulkheadExecutor.submit(resourceName, ...)` |

---

# 验证入口

- HttpClient 直连 service-a：`GET http://localhost:8082/api/service-a-direct/welcome`
- 触发固定延迟慢调用：`GET http://localhost:8082/api/external/slow/fixed?ms=2000`
- 递增慢调用：`GET http://localhost:8082/api/external/slow/call`
- 查看隔离池配置：`GET http://localhost:8082/api/bulkhead/info`
- 查看运行时指标：`GET http://localhost:8082/api/bulkhead/metrics`
- 健康检查：`GET http://localhost:8082/actuator/health`
- 验证脚本：`service-b/verify-bulkhead.sh`（验证方案 B：并发打满隔离舱，观察池满即拒）
- 隔离开关：`application.yml` → `resilience4j.thread-pool-bulkhead.enabled`（`true` 启用 / `false` 直通，重启生效）
