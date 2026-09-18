# RocketMQ `consumerProgress` 输出参数解释

## 命令

```bash
./mqadmin consumerProgress \
  -n localhost:9876 -g service-b-pull-consumer
```

## 输出示例

```
#Topic          #Broker Name   #QID  #Broker Offset  #Consumer Offset  #Diff  #LastTime
service-a-topic broker-a       0     25              16                9      2026-09-18 20:14:49
service-a-topic broker-a       1     16              11                5      2026-09-18 20:07:44
...
service-a-topic broker-a       9     5               0                 5      N/A

Consume TPS: 0.00
Diff Total: 42
```

## 各列含义

| 列名 | 含义 | 说明 |
|---|---|---|
| `#Topic` | topic 名称 | 消费者订阅的 topic |
| `#Broker Name` | broker 名称 | 该队列所在的 broker 实例（单机就是 `broker-a`） |
| `#QID` | queue id | topic 下的队列编号。RocketMQ 用队列做并行消费单元，每个队列被分配给一个消费者实例 |
| `#Broker Offset` | broker 已写入的最大 offset | 生产者发到这个队列的消息条数（提交到 broker 的位点） |
| `#Consumer Offset` | 消费者已 ack 的 offset | 消费者成功处理并提交（commitSync）的位点 |
| `#Diff` | **堆积量** = `Broker Offset - Consumer Offset` | >0 表示这个队列还有 N 条消息没被消费（堆积） |
| `#LastTime` | 最后消费时间 | 该队列最近一次成功消费的时间戳；`N/A` 表示该队列**从未被消费过** |

## 汇总行

| 行 | 含义 |
|---|---|
| `Consume TPS` | 整个消费组当前每秒消费消息数（最近采样窗口） |
| `Diff Total` | 所有队列 `#Diff` 之和 = **整个消费组当前总堆积量** |

## 实战解读（以上面输出为例）

### 1. 队列分布情况
- topic `service-a-topic` 有 **10 个队列**（QID 0-9）
- 消费组 `service-b-pull-consumer` 有 10 个实例（`threads=10`），刚好 1 实例 1 队列

### 2. 堆积分析
- `Diff Total: 42` —— 整个消费组当前堆积 42 条
- 队列 0 堆积最多（9 条），队列 3/4/8 堆积少（1-2 条）
- 堆积分布不均 → 可能某些实例处理慢（或对应下游 service-a 实例慢）

### 3. `N/A` 队列的含义
- 队列 9：`#LastTime = N/A`，`#Consumer Offset = 0`
- 表示**这个队列从未被消费过** —— 可能原因：
  - 该消费者实例刚启动还没 poll 到（rebalance 刚分配）
  - 该实例挂了 / 卡住了
  - rebalance 还没把队列 9 分给任何实例

### 4. `Consume TPS: 0.00` 的含义
- 当前采样窗口内没有消费 —— **不是真的没消费**，可能是：
  - 消费者都在等下游（service-a 慢），2 秒延迟期间 TPS 采样为 0
  - 采样窗口刚好落在两次 commit 之间
- 看长期趋势要看 `statsAll` 或 dashboard，不要只看瞬时 TPS

## 常用排查场景

### 场景 1：堆积持续增长（消费跟不上生产）
```
#Broker Offset  #Consumer Offset  #Diff
1000            800                200   ← 几秒后
1000            850                150   ← 又过几秒
1100            850                250   ← 生产继续，消费停滞
```
→ 消费者处理速度 < 生产速度，需要加并发或优化下游

### 场景 2：某队列堆积，其他队列正常
```
#QID  #Diff
0     0
1     0
2     500   ← 只有队列 2 堆积
3     0
```
→ 队列 2 对应的消费者实例卡住了（下游慢 / 死锁 / GC）

### 场景 3：所有队列都堆积，TPS=0
→ 整个消费组挂了或下游全挂，需要看 service-b 日志

### 场景 4：`N/A` + `#Consumer Offset = 0`
→ 该队列从未被消费，检查 rebalance 是否完成、对应实例是否启动

## 相关命令

```bash
# 看所有消费组的堆积汇总（不指定 -g）
./mqadmin consumerProgress -n localhost:9876

# 看消费者实例在线情况（确认实例数对不对）
./mqadmin consumerConnection -n localhost:9876 -g service-b-pull-consumer

# 看 topic 的生产/消费 TPS 和堆积
./mqadmin statsAll -n localhost:9876

# 看 topic 各队列的 min/max offset
./mqadmin topicStatus -n localhost:9876 -t service-a-topic

# 按 key 查具体消息
./mqadmin queryMsgByKey -n localhost:9876 -t service-a-topic -k "k-1"
```
