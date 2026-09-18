# Spring Boot 微服务 + Nacos + OpenFeign 示例

## 项目结构

```
microservices-demo/
├── service-a/        # 服务提供者 (端口 8081)
├── service-b/        # 服务消费者 (端口 8082) - 通过 OpenFeign 调用 Service A
```

## 架构图

```
                    ┌─────────────────┐
                    │     Nacos       │
                    │  (8848)         │
                    │  服务发现/配置   │
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   ┌────▼────┐          ┌────▼────┐          ┌────▼────┐
   │Service A│          │Service B│          │  ...    │
   │  :8081  │◄────────│  :8082  │          │         │
   └─────────┘   Feign  └─────────┘          └─────────┘
```

## 服务说明

### Service A (服务提供者)
- **端口**: 8081
- **功能**: 提供用户管理接口
- **接口**:
  - `GET /api/users` - 获取所有用户
  - `GET /api/users/{id}` - 获取指定用户
  - `POST /api/users` - 创建用户
  - `PUT /api/users/{id}` - 更新用户
  - `DELETE /api/users/{id}` - 删除用户
  - `GET /api/welcome` - 欢迎信息
  - `GET /api/health` - 健康检查

### Service B (服务消费者)
- **端口**: 8082
- **功能**: 通过 OpenFeign 调用 Service A
- **接口**: 与 Service A 相同，但实际请求由 Service B 通过 Feign 转发到 Service A

## 启动前准备

### 1. 启动 Nacos

```bash
docker run -d --name nacos \
    -p 8848:8848 \
    -p 9848:9848 \
    -p 9849:9849 \
    -e MODE=standalone \
    nacos/nacos-server:v2.4.3
```

访问: http://localhost:8848/nacos (用户名/密码: nacos/nacos)

## 启动服务

### 启动 Service A

```bash
cd service-a
mvn spring-boot:run
```

### 启动 Service B

```bash
cd service-b
mvn spring-boot:run
```

## 测试接口

### 1. 健康检查

```bash
# Service A 直接访问
curl http://localhost:8081/api/health
# 返回: Service A is running!

# Service B 通过 Feign 访问 Service A
curl http://localhost:8082/api/health
# 返回: Service B is running! Service A is running!
```

### 2. 欢迎接口

```bash
# Service A
curl http://localhost:8081/api/welcome
# 返回: 欢迎来到 Service A - 用户服务

# Service B 通过 Feign
curl http://localhost:8082/api/welcome
# 返回: Service B 调用结果: 欢迎来到 Service A - 用户服务
```

## Nacos 服务列表

启动成功后，在 Nacos 控制台可以看到两个服务已注册：

- `service-a` - 192.168.x.x:8081
- `service-b` - 192.168.x.x:8082

## OpenFeign 说明

Service B 通过 `@FeignClient(name = "service-a")` 声明式调用 Service A：

- 自动从 Nacos 获取 `service-a` 的实例列表
- 自动进行负载均衡（Spring Cloud LoadBalancer）
- 支持自定义超时、日志级别等配置

## RocketMQ相关

```bash
cd <yourpath>/rocketmq-all-4.9.8-bin-release/bin
nohup sh mqnamesrv > ~/logs/mqnamesrv.log 2>&1 &
nohup sh mqbroker -c ~/Env/rocketmq-all-4.9.8-bin-release/conf/broker.conf -n localhost:9876 > ~/logs/mqbroker.log 2>&1 &

#创建topic
./mqadmin updateTopic \
    -n localhost:9876 \
    -b localhost:10911 \
    -t service-a-topic
    
    
  # 灌 2000 条，让 service-b 消费不过来形成堆积
  mvn exec:java -Dexec.mainClass="com.example.serviceb.mq.BatchProducer" -Dexec.args="service-a-topic 2000 localhost:9876" -q 2>&1 | grep -E "已发送|完成" | tail -15
  
  #查看消费进度
  ./mqadmin consumerProgress -n localhost:9876 -g service-b-pull-consumer
  
  # 把topic 队列数扩到10
  sh ~/Env/rocketmq-all-4.9.8-bin-release/bin/mqadmin updateTopic \
    -n localhost:9876 -b localhost:10911 \
    -t service-a-topic -r 10 -w 10
```