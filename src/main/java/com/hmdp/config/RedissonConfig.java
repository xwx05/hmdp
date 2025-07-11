package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加Redis地址，这里使用的是Redis的单机模式，可以使用config.useClusterServers()方法配置Redis集群
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("123456");
        // 创建客户端
        return Redisson.create(config);

    }
}