package com.example.servicea.listener;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.client.naming.NacosNamingService;
import com.alibaba.nacos.client.naming.remote.NamingClientProxyDelegate;
import com.alibaba.nacos.client.naming.remote.gprc.NamingGrpcClientProxy;
import com.alibaba.nacos.common.remote.client.Connection;
import com.alibaba.nacos.common.remote.client.ConnectionEventListener;
import com.alibaba.nacos.common.remote.client.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;

/**
 * 监听 {@link ApplicationStartedEvent}，在自定义初始化逻辑完成后，
 * 把 service-a 在 Nacos 上的状态从 DOWN 翻为 UP（开始接流量）；
 * 同时注册 Nacos gRPC 连接监听器，仅打日志确认监听生效。
 * <p>
 * 配合 bootstrap.yml：
 * <pre>
 *   spring.cloud.nacos.discovery.register-enabled: true    # 自动注册
 *   spring.cloud.nacos.discovery.instance-enabled: false    # 注册时默认 DOWN
 * </pre>
 * 注册时机：WebServerInitializedEvent（web 容器启动时，早于 ApplicationStartedEvent）
 * 由 NacosAutoServiceRegistration 自动触发，此时实例已注册但 enabled=false（DOWN）。
 * 本监听器在 ApplicationStartedEvent 里完成预热等初始化后，调用
 * {@link NacosServiceRegistry#setStatus} 翻为 UP。
 */
@Component
public class StartedEventListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartedEventListener.class);

    private final NacosServiceRegistry serviceRegistry;
    private final NacosRegistration registration;
    private final NacosServiceManager serviceManager;

    public StartedEventListener(NacosServiceRegistry serviceRegistry,
                                 NacosRegistration registration,
                                 NacosServiceManager serviceManager) {
        this.serviceRegistry = serviceRegistry;
        this.registration = registration;
        this.serviceManager = serviceManager;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        log.info("== ApplicationStartedEvent 触发：开始执行 service-a 注册后初始化逻辑 ==");

        // 1. 预热缓存、校验依赖、加载配置等初始化工作
        doPreRegisterInit();

        // 2. 初始化完成，把 Nacos 实例状态从 DOWN 翻为 UP（开始接流量）
        serviceRegistry.setStatus(registration, "UP");
        log.info("== service-a 状态已置为 UP，开始接流量 ==");

        // 3. 注册 Nacos gRPC 连接监听器（仅打日志，验证监听生效）
        registerConnectionListener();
    }

    private void doPreRegisterInit() {
        // 示例：模拟一段初始化工作
        log.info("   - 执行初始化 Begin ...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        log.info("   - 执行初始化 End ...");
    }

    /**
     * 注册 Nacos gRPC 连接监听器，仅打日志确认监听生效。
     * <p>
     * 反射路径：
     * <pre>
     *   NacosNamingService
     *     .clientProxy  → NamingClientProxyDelegate
     *       .grpcClientProxy → NamingGrpcClientProxy
     *         .rpcClient     → RpcClient
     *           .registerConnectionListener(ConnectionEventListener)
     * </pre>
     * nacos-client 没有公开 API 暴露 RpcClient，只能反射获取。
     * 反射失败时降级：仅记录日志，不影响主流程。
     */
    private void registerConnectionListener() {
        try {
            NamingService namingService = serviceManager.getNamingService();
            if (!(namingService instanceof NacosNamingService)) {
                log.warn("NamingService 非 NacosNamingService 实现 ({}),跳过连接监听器注册",
                        namingService.getClass().getName());
                return;
            }
            NacosNamingService nacosNamingService = (NacosNamingService) namingService;

            // clientProxy (private)
            Field clientProxyField = NacosNamingService.class.getDeclaredField("clientProxy");
            clientProxyField.setAccessible(true);
            Object clientProxy = clientProxyField.get(nacosNamingService);
            if (!(clientProxy instanceof NamingClientProxyDelegate)) {
                log.warn("clientProxy 非 NamingClientProxyDelegate ({}),跳过连接监听器注册",
                        clientProxy.getClass().getName());
                return;
            }
            NamingClientProxyDelegate delegate = (NamingClientProxyDelegate) clientProxy;

            // grpcClientProxy (private final)
            Field grpcField = NamingClientProxyDelegate.class.getDeclaredField("grpcClientProxy");
            grpcField.setAccessible(true);
            Object grpcProxy = grpcField.get(delegate);
            if (!(grpcProxy instanceof NamingGrpcClientProxy)) {
                log.warn("grpcClientProxy 非 NamingGrpcClientProxy ({}),跳过连接监听器注册",
                        grpcProxy.getClass().getName());
                return;
            }
            NamingGrpcClientProxy namingGrpcProxy = (NamingGrpcClientProxy) grpcProxy;

            // rpcClient (private final)
            Field rpcField = NamingGrpcClientProxy.class.getDeclaredField("rpcClient");
            rpcField.setAccessible(true);
            Object rpcClient = rpcField.get(namingGrpcProxy);
            if (!(rpcClient instanceof RpcClient)) {
                log.warn("rpcClient 非 RpcClient ({}),跳过连接监听器注册",
                        rpcClient == null ? "null" : rpcClient.getClass().getName());
                return;
            }
            RpcClient client = (RpcClient) rpcClient;

            client.registerConnectionListener(new ConnectionEventListener() {
                @Override
                public void onConnected(Connection connection) {
                    log.info("== [Nacos连接监听] gRPC 连接建立 (connId={}) ==",
                            connection.getConnectionId());
                    serviceRegistry.setStatus(registration, "UP");
                }

                @Override
                public void onDisConnect(Connection connection) {
                    log.warn("== [Nacos连接监听] gRPC 连接断开 (connId={}) ==",
                            connection.getConnectionId());
                }
            });
            log.info("== Nacos gRPC 连接监听器注册成功 ==");
        } catch (Exception e) {
            // 反射失败不应阻断主流程
            log.error("注册 Nacos 连接监听器失败", e);
        }
    }
}
