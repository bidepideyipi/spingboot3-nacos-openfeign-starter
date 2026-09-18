package com.example.serviceb.mq;

import com.example.communisdk.client.ServiceACaller;
import com.example.serviceb.client.ServiceAClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RocketMQ Pull 消费者(多线程):N 个消费者实例,每个单线程 poll → Feign 调 service-a → 成功 ack。
 *
 * <p><b>为什么是多实例而非一个实例多线程</b>:
 * {@link DefaultLitePullConsumer} 的 {@code poll} 非线程安全,多线程 poll 同一实例会错乱内部偏移。
 * RocketMQ 横向消费的标准做法是:同 group 多个消费者实例,broker 把队列 rebalance 给它们,
 * 每个实例各自管理自己队列的偏移。本类按此实现。
 *
 * <p><b>消费循环(每个实例一个线程)</b>:
 * <pre>
 *   poll 一条
 *     → serviceACaller.execute(...).get(timeout)   同步等(经 service-a 隔离舱)
 *        成功 → commitSync()   ack 本条
 *        失败 → seek(queue, offset) 回退 + 不 commit + sleep → 下次 poll 重拉(重投)
 * </pre>
 *
 * <p><b>背压(下游慢→降低消费速度)的实现</b>:
 * N 个实例共享同一个 service-a 隔离舱,总在途数 ≤ N。
 * <ul>
 *   <li>下游变慢 → 每个实例的 .get() 阻塞变长 → N 个线程都在等 → 全部不再 poll → 背压传回 broker;</li>
 *   <li>吞吐 = N / 单次延迟(Little's Law),延迟↑ → 吞吐↓。</li>
 * </ul>
 *
 * <p><b>有效并发</b> = min(threads, topic 队列数)。
 * RocketMQ 默认 topic 4 个队列,故 threads=4 时 4 个实例各分 1 队列,并发=4;
 * 要提高并发,需先创建队列数更多的 topic,再把 threads 调到对应值。
 *
 * <p><b>与隔离舱容量的关系</b>:若 threads > max-thread-pool-size,提交时池满会排队
 * (queue-capacity>0)或拒绝(queue-capacity=0,本条失败 seek 重投)。
 * 推荐 threads ≤ max-thread-pool-size,让每个消费线程恰好占一个池线程,池满即所有线程阻塞=背压。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceAPullConsumer {

    private final ServiceACaller serviceACaller;
    private final ServiceAClient serviceAClient;

    @Value("${rocketmq.name-server}")
    private String nameServer;
    @Value("${rocketmq.consumer.group}")
    private String group;
    @Value("${rocketmq.consumer.topic}")
    private String topic;
    @Value("${rocketmq.consumer.threads:4}")
    private int threads;
    @Value("${rocketmq.consumer.pull-timeout-ms:3000}")
    private long pullTimeoutMs;
    @Value("${rocketmq.consumer.capacity-check-interval-ms:500}")
    private long capacityCheckIntervalMs;

    private final List<DefaultLitePullConsumer> consumers = new ArrayList<>();
    /** queueId → MessageQueue 映射,所有实例共享(同 topic),seek 回退时用 */
    private final Map<Integer, MessageQueue> queueMap = new HashMap<>();

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rocketmq-pull-consumer");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        for (int i = 0; i < threads; i++) {
            try {
                DefaultLitePullConsumer c = new DefaultLitePullConsumer(group);
                c.setNamesrvAddr(nameServer);
                // ★ 关键:同 JVM 多实例必须设唯一 instanceName,否则 clientId(=IP@PID)全相同,
                //   broker 把它们当成同一个消费者,rebalance 只给 1 个实例分队列,其余空转。
                c.setInstanceName("service-b-pull-" + i);
                c.setPullBatchSize(1);   // 一条一条 pull
                c.setAutoCommit(false); // 手动提交偏移(ack)
                c.subscribe(topic, "*");
                c.start();
                consumers.add(c);
                if (queueMap.isEmpty()) {
                    buildQueueMap(c);
                }
                final int idx = i;
                executor.submit(() -> consumeLoop(c, idx));
            } catch (Exception e) {
                log.error("RocketMQ 消费者实例 {} 启动失败", i, e);
            }
        }
        log.info("RocketMQ pull 消费者启动(多线程): threads={}, group={}, topic={}, namesrv={}, queues={}",
                threads, group, topic, nameServer, queueMap.keySet());
    }

    private void buildQueueMap(DefaultLitePullConsumer c) throws MQClientException {
        Collection<MessageQueue> queues = c.fetchMessageQueues(topic);
        queueMap.clear();
        for (MessageQueue q : queues) {
            queueMap.put(q.getQueueId(), q);
        }
    }

    private void consumeLoop(DefaultLitePullConsumer c, int idx) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            MessageExt msg = null;
            try {
                // ★ 背压预判:poll 前先看隔离舱还有没有空位。
                //   没空位 → 不 poll(避免拉了立即被 BulkheadFullException 拒绝再 seek 重投的无效往返),
                //   睡一小段(仅防 busy-spin 空转查指标,与下游保护无关)再查 → 背压从隔离舱直接传到消费者。
                int rc = serviceACaller.getRemainingCapacity();
                if (rc <= 10) {
                    sleepQuietly(capacityCheckIntervalMs);
                    continue;
                }

                log.info("[实例{}] RemainingCapacity={}", idx, rc);
                List<MessageExt> msgs = c.poll(pullTimeoutMs);
                if (msgs == null || msgs.isEmpty()) {
                    continue;
                }
                msg = msgs.get(0);
                log.info("[实例{}] 消息接收成功: msgId={}, queueId={}, offset={}",
                        idx, msg.getMsgId(), msg.getQueueId(), msg.getQueueOffset());

                // 同步处理:Feign 调 service-a(经 service-a 隔离舱)
                // 下游慢 → .get() 阻塞变长 → 该实例不再 poll;N 个实例都阻塞 → 背压传回 broker
                // 不加额外超时:底层 HttpClient 已有连接/读取超时兜底,这里无限等让背压自然形成
                String result = serviceACaller
                        .execute(() -> serviceAClient.getSlowFixed(150))//模拟下游处理时间
                        .get();

                // 成功 → ack 本条(提交偏移)
                c.commitSync();
                log.info("[实例{}] 消息处理成功并 ack: msgId={}, queueId={}, offset={}, response={}",
                        idx, msg.getMsgId(), msg.getQueueId(), msg.getQueueOffset(), result);

            } catch (Exception e) {
                if (msg != null) {
                    seekBack(c, msg, idx);
                }
            }
        }
    }

    /** seek 回退到本条(使下次 poll 重新拉到本条) */
    private void seekBack(DefaultLitePullConsumer c, MessageExt msg, int idx) {
        MessageQueue q = queueMap.get(msg.getQueueId());
        if (q != null) {
            try {
                c.seek(q, msg.getQueueOffset());
            } catch (MQClientException e) {
                log.error("[实例{}] seek 回退失败: queue={}, offset={}", idx, q, msg.getQueueOffset(), e);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            log.info("感知到下游压力，休眠: {}ms", ms);
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        for (DefaultLitePullConsumer c : consumers) {
            c.shutdown();
        }
        log.info("RocketMQ pull 消费者已关闭(共 {} 个实例)", consumers.size());
    }
}
