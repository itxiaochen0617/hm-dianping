package com.hmdp.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        // 配置：与 application.yaml 的 spring.redis.* 共用一份配置，避免两处各写一遍导致漂移
        Config config = new Config();
        SingleServerConfig singleServer = config.useSingleServer()
                // Redisson 要求地址带 redis:// 前缀，由代码拼接，不让外部写错
                .setAddress("redis://" + host + ":" + port);
        // 只在确实配了密码时才设置：传空串会让 Redisson 发送 AUTH ""，被无密码的 Redis 拒绝
        if (StrUtil.isNotBlank(password)) {
            singleServer.setPassword(password);
        }
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
