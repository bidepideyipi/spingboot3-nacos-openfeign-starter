### nacos 1 分钟状态记忆
实测的"约1分钟状态记忆"，最对应的是 
```text
nacos.naming.clean.expired-metadata.expired-time（默认 60000ms = 60s）——失效元数据（含实例的 enabled 状态）被保留 60s
```