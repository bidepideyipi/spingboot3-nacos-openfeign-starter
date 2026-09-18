package com.example.serviceb.mq;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立批量消息生产者(非 Spring Bean,带 main 方法,单 JVM 高速发消息)。
 *
 * <p><b>为什么不用 mqadmin sendMessage 循环</b>:
 * mqadmin 每次执行都要启动一个 JVM(1-2 秒),发 100 条要 3-4 分钟;
 * 本类一次 JVM 启动发完所有消息,1000 条通常 < 1 秒。
 *
 * <h3>用法</h3>
 * <pre>
 * # 默认:发 100 条到 service-a-topic,NameServer=localhost:9876
 * mvn -pl service-b exec:java -Dexec.mainClass="com.example.serviceb.mq.BatchProducer"
 *
 * # 自定义参数(顺序: topic count namesrv)
 * mvn -pl service-b exec:java -Dexec.mainClass="com.example.serviceb.mq.BatchProducer" \
 *   -Dexec.args="service-a-topic 1000 localhost:9876"
 *
 * # 或编译后用 java 直接跑(需先 mvn -pl service-b -am dependency:copy-dependencies -DoutputDirectory=target/lib)
 * java -cp "service-b/target/classes:service-b/target/lib/*" \
 *   com.example.serviceb.mq.BatchProducer service-a-topic 1000 localhost:9876
 * </pre>
 *
 * <p><b>参数</b>(全部可选,有默认值):
 * <ul>
 *   <li>args[0] topic,默认 {@code service-a-topic}</li>
 *   <li>args[1] count,默认 {@code 100}</li>
 *   <li>args[2] namesrv,默认 {@code localhost:9876}</li>
 *   <li>args[3] tag,默认 {@code batch}</li>
 * </ul>
 */
public class BatchProducer {

    public static void main(String[] args) throws MQClientException, InterruptedException {
        String topic   = args.length > 0 ? args[0] : "service-a-topic";
        int    count   = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        String namesrv = args.length > 2 ? args[2] : "localhost:9876";
        String tag    = args.length > 3 ? args[3] : "batch";

        // producer group 名字(仅 producer 端统计用,不影响消费)
        DefaultMQProducer producer = new DefaultMQProducer("batch-producer-group");
        producer.setNamesrvAddr(namesrv);
        producer.start();

        System.out.printf("开始发送: topic=%s, count=%d, namesrv=%s, tag=%s%n",
                topic, count, namesrv, tag);

        long start = System.currentTimeMillis();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            // body 带 index,方便消费端按序号核对
            String body = String.format("{\"index\":%d,\"msg\":\"batch-%d\",\"ts\":%d}",
                    i, i, System.currentTimeMillis());
            Message msg = new Message(topic, tag,
                    "key-" + i,
                    body.getBytes(StandardCharsets.UTF_8));

            try {
                SendResult r = producer.send(msg);
                if (r.getSendStatus() == SendStatus.SEND_OK) {
                    ok.incrementAndGet();
                } else {
                    fail.incrementAndGet();
                    System.err.printf("发送非 OK: i=%d, status=%s%n", i, r.getSendStatus());
                }
            } catch (Exception e) {
                fail.incrementAndGet();
                System.err.printf("发送异常: i=%d, %s%n", i, e.getMessage());
            }

            // 每 100 条打印一次进度
            if ((i + 1) % 100 == 0) {
                System.out.printf("已发送 %d / %d, 耗时 %dms%n",
                        i + 1, count, System.currentTimeMillis() - start);
            }
        }

        long cost = System.currentTimeMillis() - start;
        System.out.println("────────────────────────────────────────");
        System.out.printf("完成: 成功=%d, 失败=%d, 总耗时=%dms, 平均=%.2f 条/秒%n",
                ok.get(), fail.get(), cost, count * 1000.0 / Math.max(cost, 1));
        System.out.println("────────────────────────────────────────");

        producer.shutdown();
    }
}
