# Spring Boot 3 + Nacos + OpenFeign + Resilience4j Starter

A demo showing how to apply **Resilience4j fault tolerance** (CircuitBreaker + ThreadPoolBulkhead)
uniformly across three downstream call paths in a Spring Cloud microservice:

1. **HTTP** — direct calls via Apache HttpClient (`HttpClientTemplate`)
2. **Feign** — declarative calls via OpenFeign (`ServiceACaller`)
3. **RocketMQ Pull** — a pull-mode consumer that turns downstream pressure into broker backpressure (`ServiceAPullConsumer`)

All three paths share one reusable executor (`BulkheadExecutor`), so the resilience policy is
declared **once** and applied everywhere.

## Project Layout

```
.
├── communi-sdk/     # Shared SDK: BulkheadExecutor, ServiceACaller, HttpClientTemplate, Feign/HTTP config
├── service-a/       # Provider (port 8081) — user + slow endpoints
└── service-b/       # Consumer (port 8082) — Feign + HttpClient + RocketMQ pull consumer
```

## Architecture

```
                       ┌──────────────┐
                       │    Nacos     │  service discovery & config (8848)
                       └──────┬───────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   ┌────▼─────┐          ┌────▼─────┐          ┌────▼─────┐
   │ Service A│◄─Feign────│ Service B│          │ RocketMQ │
   │  :8081   │◄─HttpClient│  :8082   │◄─pull────│  broker  │
   └──────────┘           └────┬─────┘          └──────────┘
                              │  all downstream calls go through
                              └─► BulkheadExecutor
                                  = CircuitBreaker(outer) + ThreadPoolBulkhead(inner)
```

## The Core Idea: One Executor, Three Call Paths

`BulkheadExecutor` (in `communi-sdk`) is the single point where resilience is applied.
Every downstream call — whether from a Feign interface, a raw HttpClient call, or a RocketMQ
poll loop — is funneled through `BulkheadExecutor.submit(resource, task)`.

### Decoration Order — CircuitBreaker outside, Bulkhead inside

```
submit(resource, task)
   │
   ▼
CircuitBreaker.decorateCompletionStage(...)        ← outer
   │  • OPEN          → throw CallNotPermittedException synchronously (no pool slot consumed)
   │  • CLOSED/HALF_OPEN → proceed
   ▼
ThreadPoolBulkhead.executeSupplier(task)           ← inner
   │  • pool+queue full → throw BulkheadFullException synchronously
   │  • otherwise → run task in isolated bounded thread pool
   ▼
whenComplete → CircuitBreaker records success/failure → updates failure rate
```

**Why this order?** When the downstream is failing, the circuit opens and short-circuits
*before* a pool thread is consumed. The bulkhead pool is therefore not filled with doomed
in-flight calls, leaving capacity for half-open probes once the downstream recovers.

### Behavior Matrix

| `bulkhead.enabled` | `circuitbreaker.enabled` | Behavior |
|---|---|---|
| `true`  | `true`  | Full chain: CB(outer) → Bulkhead(inner) |
| `true`  | `false` | Bulkhead only (no circuit breaker) |
| `false` | `true`  | Circuit breaker only (task runs on caller thread) |
| `false` | `false` | Pass-through (no isolation, no circuit breaking) |

## Path 1 — HTTP (Apache HttpClient)

`HttpClientTemplate` wraps every GET/POST in `BulkheadExecutor.submit(resourceName, ...)`.
The resource name maps to a `resilience4j.thread-pool-bulkhead.instances.<name>` entry in YAML,
so different downstream services get **independent** thread pools that cannot starve each other.

```java
// communi-sdk: HttpClientTemplate
CompletableFuture<HttpResponse<T>> future = bulkheadExecutor.submit(
        resourceName,
        () -> doRequest(request, responseType, startTime));
return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
```

Failure modes returned to the caller:

| Condition | Result |
|---|---|
| Pool + queue full | `HttpResponse.blocked(...)` (BulkheadFullException caught) |
| Circuit open | `HttpResponse.error(...)` (CallNotPermittedException caught) |
| Downstream throws | `HttpResponse.error(...)` (ExecutionException unwrapped) |
| Caller wait > 15s | `HttpResponse.error("request timeout")` |

`ServiceADirectService` uses resource name `service-a-http` — a separate pool from the Feign
path (`service-a`), so a slow direct-HTTP burst cannot exhaust the Feign pool.

## Path 2 — Feign (OpenFeign)

`ServiceACaller` is a thin wrapper that submits a `Supplier<T>` (the Feign call) to the
`service-a` bulkhead. Callers do `serviceACaller.execute(() -> serviceAClient.foo()).get(...)`.

```java
// communi-sdk: ServiceACaller
public <T> CompletableFuture<T> execute(Supplier<T> feignCall) {
    return bulkheadExecutor.submit(RESOURCE, feignCall);   // RESOURCE = "service-a"
}
```

The SDK holds **no Feign interface reference** — consumers declare their own `@FeignClient`
and pass the call as a `Supplier`. Adding a new downstream endpoint requires **zero SDK changes**.

## Path 3 — RocketMQ Pull Consumer (Backpressure)

`ServiceAPullConsumer` runs `N` `DefaultLitePullConsumer` instances (one thread each — `poll()`
is not thread-safe on a single instance). Each instance loops:

```
poll(1) → serviceACaller.execute(...).get() → success: commitSync()  (ack)
                                       └─► failure: seek(queue, offset) (re-deliver next poll)
```

The key design is **backpressure pre-checks before `poll()`**, so the consumer never pulls a
message it cannot process — avoiding the wasteful "pull → reject → seek → re-pull" round-trip:

```java
// service-b: ServiceAPullConsumer.consumeLoop
// 1) Circuit-breaker pre-check: if OPEN, skip poll entirely
if (!serviceACaller.isCallPermitted()) {
    sleepQuietly(capacityCheckIntervalMs);   // wait for HALF_OPEN
    continue;
}
// 2) Bulkhead capacity pre-check: if pool+queue full, skip poll
int rc = serviceACaller.getRemainingCapacity();
if (rc <= 0) {
    sleepQuietly(capacityCheckIntervalMs);
    continue;
}
// 3) Only now poll
List<MessageExt> msgs = c.poll(pullTimeoutMs);
```

### How Backpressure Propagates

```
service-a slows down
   → each .get() blocks longer
   → all N consumer threads block on .get()
   → bulkhead pool saturates → getRemainingCapacity() == 0
   → consumers stop polling
   → broker stops delivering (no pull requests)
   => downstream pressure flows back to the broker
```

If failures accumulate, the circuit opens and `isCallPermitted()` returns `false` — consumers
skip polling and wait for half-open probes, **without consuming any pool thread**.

### Effective Concurrency

`effectiveConcurrency = min(threads, topicQueueCount)`. RocketMQ's default topic has 4 queues,
so `threads=4` gives 4-way parallelism. To raise concurrency, first widen the topic's queue
count, then raise `threads` to match.

```bash
./mqadmin updateTopic -n localhost:9876 -b localhost:10911 \
  -t service-a-topic -r 16 -w 16
```

## Resilience4j Configuration

Two resilience modules are configured, both keyed by the **same resource name** so a single
`BulkheadExecutor.submit("service-a", ...)` picks up both the bulkhead and the circuit breaker.

```yaml
resilience4j:
  thread-pool-bulkhead:
    enabled: true
    configs:
      default:
        max-thread-pool-size: 10
        core-thread-pool-size: 10
        queue-capacity: 5
        keep-alive-duration: 1m
    instances:
      service-a:      { base-config: default }   # Feign path
      service-a-http: { base-config: default }   # HttpClient path

  circuitbreaker:
    enabled: true
    configs:
      default:
        sliding-window-type: TIME_BASED     # time-based window (vs COUNT_BASED)
        sliding-window-size: 60            # last 60 seconds
        minimum-number-of-calls: 10        # need ≥10 calls before rate is computed
        failure-rate-threshold: 50         # ≥50% failures → OPEN
        slow-call-rate-threshold: 60        # ≥60% slow calls → OPEN
        slow-call-duration-threshold: 3s
        wait-duration-in-open-state: 30s   # OPEN → wait 30s → HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - java.io.IOException
          - java.net.SocketTimeoutException
          - java.util.concurrent.TimeoutException
          - feign.FeignException
    instances:
      service-a:      { base-config: default }
      service-a-http: { base-config: default }
```

### Why TIME_BASED?

A `COUNT_BASED` window of size 60 needs 60 calls to fill; under low traffic the window never
fills and the failure rate is computed on stale data. A `TIME_BASED` window of 60s always
reflects the **last 60 seconds** regardless of call volume — better for bursty or low-traffic
services. `minimum-number-of-calls: 10` prevents cold-start false positives (2 calls, 1 fail).

## Metrics & Diagnostics

Resilience4j metrics are exposed through three channels:

### 1. Custom diagnostic endpoints (human-readable JSON)

```bash
# Bulkhead (thread pool + queue)
curl localhost:8082/api/bulkhead/info
curl localhost:8082/api/bulkhead/metrics

# Circuit breaker (state + failure rate + call counts)
curl localhost:8082/api/circuitbreaker/info
curl localhost:8082/api/circuitbreaker/metrics
```

`/api/circuitbreaker/metrics` returns per-instance: `state`, `failureRate(%)`, `slowCallRate(%)`,
`numberOfBufferedCalls`, `numberOfSuccessfulCalls`, `numberOfFailedCalls`, `numberOfSlowCalls`,
`numberOfNotPermittedCalls`.

### 2. Micrometer / Actuator (for scrapers)

`resilience4j-micrometer` is already on the classpath, so circuit-breaker and bulkhead metrics
are auto-bound to the `MeterRegistry`:

```bash
curl localhost:8082/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:service-a
curl localhost:8082/actuator/metrics/resilience4j.circuitbreaker.failure.rate?tag=name:service-a
curl localhost:8082/actuator/metrics/resilience4j.bulkhead.available.size?tag=name:service-a
```

Add `micrometer-registry-prometheus` to expose `/actuator/prometheus` for scraping.

### 3. Actuator health

`/actuator/health` includes circuit-breaker state when `register-health-indicator` is on.

## Prerequisites

| Component | Version | Port |
|---|---|---|
| Nacos | 2.4.3 | 8848 |
| RocketMQ | 4.9.8 (broker + nameserver) | 10911 / 9876 |
| Java | 17 | — |
| Spring Boot | 3.2.0 | — |
| Spring Cloud | 2023.0.1 | — |
| Spring Cloud Alibaba | 2023.0.1.0 | — |
| Resilience4j | 2.2.0 (pinned via BOM) | — |

## Getting Started

### 1. Start Nacos

```bash
docker run -d --name nacos \
  -p 8848:8848 -p 9848:9848 -p 9849:9849 \
  -e MODE=standalone \
  nacos/nacos-server:v2.4.3
```

Console: http://localhost:8848/nacos (nacos / nacos)

### 2. Start RocketMQ

```bash
cd ~/Env/rocketmq-all-4.9.8-bin-release/bin
nohup sh mqnamesrv > ~/logs/mqnamesrv.log 2>&1 &
nohup sh mqbroker -c ../conf/broker.conf -n localhost:9876 > ~/logs/mqbroker.log 2>&1 &

# Create topic with 16 read/write queues (raises effective consumer concurrency)
./mqadmin updateTopic -n localhost:9876 -b localhost:10911 \
  -t service-a-topic -r 16 -w 16
```

### 3. Start services

```bash
cd service-a && mvn spring-boot:run      # provider, port 8081
cd service-b && mvn spring-boot:run      # consumer, port 8082
```

### 4. Verify

```bash
curl localhost:8082/api/welcome           # Feign path (service-a bulkhead)
curl localhost:8082/api/direct/welcome    # HttpClient path (service-a-http bulkhead)
curl localhost:8082/api/aggregate        # both paths in one request
```

## Fault-Injection Playbook

### Scenario A — Downstream slow (Feign path)

`service-a` exposes `/api/slow/fixed?ms=...`. Drive concurrent requests against service-b's
aggregation endpoint and watch the bulkhead fill:

```bash
# Watch bulkhead metrics in one terminal
watch -n 1 'curl -s localhost:8082/api/bulkhead/metrics | jq'
# In another, fire 20 concurrent slow calls
for i in $(seq 1 20); do curl -s localhost:8082/api/aggregate & done; wait
```

Expected: `availableThreadCount` drops to 0, `queueDepth` climbs to 5, the 16th+ requests
return `blocked`. Once `slow-call-rate` exceeds 60% over 60s, the circuit opens.

### Scenario B — Downstream failing (circuit breaker)

Make `service-a` return 5xx or refuse connections. After `minimum-number-of-calls=10` and
`failure-rate ≥ 50%` within 60s:

```bash
curl localhost:8082/api/circuitbreaker/metrics
# → "state": "OPEN", "failureRate(%)": 80.0, "numberOfNotPermittedCalls": <growing>
```

During OPEN, all calls throw `CallNotPermittedException` **without entering the bulkhead pool**.
After 30s the breaker moves to HALF_OPEN and permits 3 probe calls.

### Scenario C — RocketMQ backpressure

Load 2000 messages and watch the consumer keep up via Little's Law (`throughput = N / latency`):

```bash
# From the service-b directory
mvn exec:java -Dexec.mainClass="com.example.serviceb.mq.BatchProducer" \
  -Dexec.args="service-a-topic 2000 localhost:9876" -q 2>&1 | tail -15

# Watch consumption progress
./mqadmin consumerProgress -n localhost:9876 -g service-b-pull-consumer
```

When `service-a` slows down, the consumer log shows `感知到下游压力，休眠: 500ms` and
`熔断打开,跳过 poll 等待半开` — the broker sees no `pull` requests and stops delivering.

## Key Source Files

| File | Role |
|---|---|
| `communi-sdk/.../client/BulkheadExecutor.java` | CB(outer) + Bulkhead(inner) composition; `submit`, `getRemainingCapacity`, `isCallPermitted` |
| `communi-sdk/.../client/ServiceACaller.java` | Feign-path wrapper, resource `service-a` |
| `communi-sdk/.../http/HttpClientTemplate.java` | HTTP-path wrapper, resource `service-a-http` |
| `service-b/.../mq/ServiceAPullConsumer.java` | N-instance pull consumer with pre-poll backpressure checks |
| `service-b/.../controller/BulkheadDiagnosticController.java` | `/api/bulkhead/{info,metrics}` |
| `service-b/.../controller/CircuitBreakerDiagnosticController.java` | `/api/circuitbreaker/{info,metrics}` |
| `service-b/src/main/resources/application.yml` | Resilience4j config (bulkhead + circuitbreaker) |

## Design Notes

- **Same resource name across CB and Bulkhead.** `BulkheadExecutor` looks up both registries by
  the same name, so YAML only declares each instance once per module.
- **No `@CircuitBreaker` / `@Bulkhead` annotations.** Decoration is programmatic inside
  `BulkheadExecutor`, keeping the policy in one place and avoiding annotation ordering pitfalls.
- **SDK holds no business DTOs.** Consumers declare their own `@FeignClient` and DTOs; the SDK
  only provides `execute(Supplier<T>)`. New downstream endpoints need no SDK change.
- **`getRemainingCapacity` = `availableThreadCount + remainingQueueCapacity`.** The sum is the
  true "how many more tasks can be accepted before rejection" — both direct thread slots and
  queue slots count, because a task goes to an idle thread if one is free, otherwise to the queue.
- **`TIME_BASED` window over `COUNT_BASED`.** Time windows always reflect the last N seconds
  regardless of traffic volume, avoiding stale failure rates under low load.

