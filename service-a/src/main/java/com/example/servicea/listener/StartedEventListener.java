package com.example.servicea.listener;

import com.alibaba.cloud.nacos.registry.NacosRegistration;
import com.alibaba.cloud.nacos.registry.NacosServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 监听 {@link ApplicationStartedEvent}，并在自定义初始化逻辑完成后，
 * 手动将 service-a 注册到 Nacos。
 * <p>
 * 背景：Nacos 自动注册由 {@code WebServerInitializedEvent} 触发，发生在
 * {@code refresh()} 末尾（web 容器启动时），早于 {@code ApplicationStartedEvent}。
 * 因此若要"先做某件事再注册"，必须：
 * <ol>
 *   <li>用 {@code spring.cloud.nacos.discovery.register-enabled=false} 关闭自动注册
 *       （此时 {@link NacosServiceRegistry} / {@link NacosRegistration} bean 仍存在，
 *        只是 {@code NacosAutoServiceRegistration.isEnabled()} 返回 false 而跳过注册）</li>
 *   <li>在此监听器里完成自定义逻辑后，手动调用
 *       {@code serviceRegistry.register(registration)}</li>
 * </ol>
 * 注意：手动注册后，下线仍需调用 {@code serviceRegistry.deregister(registration)}
 * 或依赖 actuator 的 service-registry endpoint。
 */
@Component
public class StartedEventListener implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartedEventListener.class);

    private final NacosServiceRegistry serviceRegistry;
    private final NacosRegistration registration;
    private final WebServerApplicationContext webServerContext;

    public StartedEventListener(NacosServiceRegistry serviceRegistry,
                                 NacosRegistration registration,
                                 WebServerApplicationContext webServerContext) {
        this.serviceRegistry = serviceRegistry;
        this.registration = registration;
        this.webServerContext = webServerContext;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        log.info("== ApplicationStartedEvent 触发：开始执行 service-a 注册前自定义逻辑 ==");

        // TODO: 在这里放置需要在注册前完成的初始化逻辑（预热缓存、校验依赖、加载配置等）
        doPreRegisterInit();

        // register-enabled=false 时 NacosAutoServiceRegistration.start() 提前 return，
        // 不会从 web 容器把端口绑定到 registration，这里手动补上，否则注册报
        // "Param 'port' is illegal, the value should be between 0 and 65535"
        int port = webServerContext.getWebServer().getPort();
        registration.setPort(port);
        //log.info("== 自定义逻辑完成，开始手动注册 service-a 到 Nacos (port={}) ==", port);
        serviceRegistry.register(registration);
        // serviceRegistry.setStatus(registration, "UP");
        log.info("== service-a 已注册到 Nacos ==");
    }

    private void doPreRegisterInit() {
        // 示例：模拟一段注册前需要完成的初始化工作
        log.info("   - 执行注册前初始化 Begin ...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.info("   - 执行注册前初始化 End ...");
    }
}
