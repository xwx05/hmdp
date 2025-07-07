package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存空值解决缓存穿透
     * @param keyPrefix key的前缀
     * @param id 商铺id
     * @param type 实体类型
     * @param dbFallBack 数据库查询方法
     * @param time 过期时间
     * @param unit 时间单位
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
                                          Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 此处判断是不为空字符串才为true
        if (StrUtil.isNotBlank(json)) {
            // 2.redis中命中，直接返回客户端
            return JSONUtil.toBean(json, type);
        }
        // 由于可能存在数据库没查到并缓存了空值的情况，因此还需加一个判断
        if (json != null) {
            return null;
        }
        // 3.由于此处返回的是泛型，工具类无法知道具体的数据库查询方法，应由业务方调用时传入
        R r = dbFallBack.apply(id);

        if (r == null) {
            // 4.数据库中没查到，缓存空值到redis避免缓存穿透，并报错
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.命中，数据库中查到的数据保存到redis
        this.set(key, r, time, unit);

        return r;
    }


    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期解决缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
                                       Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.未命中，直接返回空，由于热点数据提前缓存且理论上永不过期，如果没查到可能说明没有这个商铺
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 3.命中，json反序列化为对象，根据expireTime字段判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.未过期，直接返回商铺信息
            return r;
        }
        // 5.过期，需要缓存重建
        // 5.1 获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        if (isLock) {
            // 5.2 获取锁成功
            // DoubleCheck
            json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(json)) {
                redisData = JSONUtil.toBean(json, RedisData.class);
                expireTime = redisData.getExpireTime();
                data = (JSONObject) redisData.getData();
                r = JSONUtil.toBean(data, type);
                if (expireTime.isAfter(LocalDateTime.now())) {
                    return r;
                }
            }

            // 开启独立线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 查数据库
                    R r1 = dbFallBack.apply(id);
                    // 存入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(LOCK_SHOP_KEY + id);
                }
            });
        }
        // 6.返回商铺信息（如果获取锁失败返回的就是旧的商铺信息）
        return r;
    }

    private boolean tryLock(String key) {
        // 判断是否获取到互斥锁
        // 如果返回1，说明由当前线程执行重建缓存，如果返回0，说明已有别的线程进行了缓存重建，当前线程休眠一段时间，重试从redis查询缓存
        // 注意这里只是判断是否获取到锁，设置的value为任意值，与实际业务无关
        // 为了防止异常情况导致锁始终释放不了，设置一个过期时间，大于业务执行时间，例如业务通常1s完成，设置过期时间为10s
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        stringRedisTemplate.opsForValue().setIfAbsent()方法返回的是Boolean（这是包装类），而方法的返回类型为boolean（这是基本数据类型）。
//        在返回时，Java 会自动把Boolean对象转换为boolean值，这就是所谓的自动拆箱。
//        当 Redis 操作失败或者出现异常时，setIfAbsent()可能会返回null。此时直接进行自动拆箱，就如同执行了null.booleanValue()，必然会触发NullPointerException。
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
