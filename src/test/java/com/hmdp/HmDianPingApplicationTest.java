package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@SpringBootTest
public class HmDianPingApplicationTest {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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


    @Test
    public void testSaveUsers() {
        for (int i = 0; i < 1000; i++) {
            // 1. 随机生成手机号 (11位数字，以1开头)
            String phone = "1" + String.format("%010d", (long) (Math.random() * 10000000000L));

            // 2. 创建用户并保存到数据库
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            userService.save(user);

            // 3. 生成token
            String token = UUID.randomUUID().toString(true);

            // 4. 准备用户信息Map
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create().
                            setIgnoreNullValue(true).
                            setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()));
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        }
    }

    @Test
    public void exportTokensToExcel() throws IOException {
        String filePath = "tokens_export.xlsx";
        String keyPattern = LOGIN_USER_KEY + "*";

        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(filePath)) {

            // 创建工作表和表头行
            Sheet sheet = workbook.createSheet("Tokens");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("token");

            // 获取所有匹配的Redis键
            Set<String> keys = stringRedisTemplate.keys(keyPattern);

            if (keys != null && !keys.isEmpty()) {
                int rowNum = 1;
                for (String key : keys) {
                    // 提取token部分
                    String token = key.substring(LOGIN_USER_KEY.length());

                    // 创建数据行
                    Row row = sheet.createRow(rowNum++);
                    row.createCell(0).setCellValue(token);
                }

                // 写入文件
                workbook.write(fileOut);
                System.out.println("成功导出 " + (rowNum - 1) + " 条token数据到 " + filePath);
            } else {
                System.out.println("未找到匹配的token数据");
            }
        }
    }


}
