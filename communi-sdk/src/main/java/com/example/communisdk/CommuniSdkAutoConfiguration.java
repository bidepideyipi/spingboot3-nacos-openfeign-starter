package com.example.communisdk;

import com.example.communisdk.client.BulkheadExecutor;
import com.example.communisdk.client.ServiceACaller;
import com.example.communisdk.config.FeignClientConfig;
import com.example.communisdk.config.HttpClientConfig;
import com.example.communisdk.http.HttpClientTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * communi-sdk 自动装配入口。
 *
 * <p>消费方只需在依赖中加入 communi-sdk,本类会自动:
 * <ul>
 *   <li>注册带连接池的 Apache HttpClient({@link HttpClientConfig});</li>
 *   <li>注册 Feign 配置({@link FeignClientConfig});</li>
 *   <li>注册公共隔离执行器 {@link BulkheadExecutor}(开关 + 提交到隔离池);</li>
 *   <li>注册 service-a 的 Feign 隔离调用器 {@link ServiceACaller};</li>
 *   <li>注册 HttpClient 请求模板 {@link HttpClientTemplate}(隔离 + HTTP 兜底)。</li>
 * </ul>
 *
 * <p>注意: Feign 接口(@FeignClient)、DTO 由<b>消费方</b>自行定义,
 * 在自己的 {@code @EnableFeignClients} 中扫描。这样下游接口/DTO 增删改时无需修改本 SDK,
 * 且 SDK 不绑定任何业务 DTO(如 UserDto)。
 *
 * <p>注册文件: META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 */
@AutoConfiguration
@Import({
        HttpClientConfig.class,
        FeignClientConfig.class,
        BulkheadExecutor.class,
        ServiceACaller.class,
        HttpClientTemplate.class
})
public class CommuniSdkAutoConfiguration {
}
