package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class RedissonTest {

    @Resource
    private RedissonClient redissonClient;

    private RLock lock;

    @BeforeEach
    void setUp() {
        lock = redissonClient.getLock("order");
    }

    // 基于SETNX实现的锁存在以下问题：1、不可重入（同一个线程无法多次获得同一把锁）；2、不可重试（获取锁只尝试一次就返回结果）；
    // 3、超时释放（如果时间太短，业务执行时间较长，可能业务还未执行完锁提前释放；如果时间太长，则阻塞时间太长）；
    // 4、主从不一致（主从同步存在延迟，如果主宕机，而从还没来得及同步锁，则出现锁失效）

    @Test
    void method1() throws InterruptedException {
        // 尝试获取锁
        // 无参表示：不阻塞，失败直接返回结果，超时时间默认30s
        // boolean isLock = lock.tryLock();

        // 如果第一个参数有值，为最大等待时间，在这个时间内会执行重试，也就不是立即返回了
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
        if (!isLock) {
            // 获取锁失败，直接返回
            log.error("获取锁失败......1");
            return;
        }
        try {
            log.info("获取锁成功......1");
            method2();
            log.info("开始执行业务......1");
        }finally {
            // 释放锁
            log.warn("释放锁......1");
            lock.unlock();
        }
    }

    void method2() {
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，直接返回
            log.error("获取锁失败......2");
            return;
        }
        try {
            log.info("获取锁成功......2");
            log.info("开始执行业务......2");
        }finally {
            // 释放锁
            log.warn("释放锁......2");
            lock.unlock();
        }
    }

}
