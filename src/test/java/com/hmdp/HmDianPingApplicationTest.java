package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
public class HmDianPingApplicationTest {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void testSaveShop() throws InterruptedException {

        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    // 创建线程池
    private static final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() throws InterruptedException {
        // CountDownLatch(300) 表示需要等待 300 个操作完成
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            // 每个线程生成100个ID
            for (int i = 0; i < 100; i++) {
                System.out.println(redisIdWorker.next("order"));
            }
            // 每执行完一个任务，计数减1
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        // 300 个线程并发生成 ID
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        // 主线程调用 latch.await() 会被阻塞，直到计数器变为 0（即所有 300 个线程都完成了任务）。
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time:" + (end-begin));
    }
}
