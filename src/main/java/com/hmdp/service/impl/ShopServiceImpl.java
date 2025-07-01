package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     *
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 缓存穿透指客户端请求的数据既不在缓存中，也不在数据库中。
        // 这导致每次请求都会穿透缓存，直接访问数据库，从而可能使数据库承受过大压力，甚至引发性能问题或崩溃
        // 通过缓存空值/布隆过滤器（Bloom Filter）解决
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 缓存雪崩指大量key同时失效或redis宕机，大量请求直接到达数据库，可通过设置随机ttl，redis集群、多级缓存等方式解决

        // 缓存击穿也叫热点key问题，指被高并发访问且缓存重建业务复杂的key突然失效
        // 通过互斥锁/逻辑过期解决
        // Shop shop = queryWithMutex(id);

        // 基于逻辑过期方式解决缓存击穿问题
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }


    /**
     * 互斥锁解决缓存击穿+缓存空值解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        Shop shop = null;
        try {
            // 从redis查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            // 此处判断是不为空字符串才为true
            if (StrUtil.isNotBlank(shopJson)) {
                // redis中命中，直接返回客户端
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            // 由于可能存在数据库没查到并缓存了空值的情况，因此还需加一个空值判断
            if (shopJson != null) {
                return null;
            }

            // redis未命中，尝试获取互斥锁
            // 这个key保证每个店铺有一个自己的锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            if (!isLock) {
                // 获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 获取锁成功，先DoubleCheck redis缓存是否存在，如果存在则无需重建缓存
            // 因为有可能线程1刚构建完缓存并释放锁，而线程2正好执行到获取锁成功这一步，如果不查询缓存，会再访问数据库执行一次重建
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                // redis中命中，直接返回客户端
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }

            // 执行重建，根据id查询数据库
            shop = getById(id);
            // 模拟执行重建的延时
            Thread.sleep(200);

            if (shop == null) {
                // 数据库中没查到，缓存空值到redis避免缓存击穿，并报错
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 数据库中查到的数据保存到redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 写完释放互斥锁
            unlock(LOCK_SHOP_KEY + id);
        }
        // 返回客户端
        return shop;
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


    // 提前缓存热点数据
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 根据id查询shop
        Shop shop = getById(id);
        // 模拟执行重建的延时
        Thread.sleep(100);
        // 2. 封装成含逻辑过期时间的数据（RedisData）
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY +id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 更新商铺信息
     *
     * @param shop
     * @return
     */
    @Transactional
    public Result updateShop(Shop shop) {
        Long id  = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

}
