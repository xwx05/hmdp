package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    // 初始化lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 使用阻塞队列实现异步秒杀存在的问题：1、阻塞队列存在jvm内存里面，内存有限制
    // 2、数据安全问题，例如返回了订单号，但异步线程往数据库里插入数据失败，或者队列取出了任务，但突然异常没有执行，该任务丢失

    // 创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 创建线程池，此处用单线程就够了
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 保证在当前类初始化完成后就运行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }


    /**
     * 秒杀券下单v3（基于redis的stream作为消息队列实现异步优化）
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.next("order");

        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());

        // 2. 判断用户是否有下单资格
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r== 1 ? "库存不足！" : "不允许重复下单！");
        }

        // 获取代理对象，放进成员变量，这样后续业务方便获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回订单id
        return Result.ok(orderId);

    }


    // 创建线程任务从消息队列（mq）中获取订单
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从消息队列中获取订单  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1L).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        // list为空说明没有消息，继续循环进行下一次读取
                        continue;
                    }
                    // 2. 解析消息队列里取出的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);

                    // 3. 执行任务，创建订单
                    handleVoucherOrder(voucherOrder);

                    // 4. ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        // 处理pending-list里的消息
        private void handlePendingList() throws InterruptedException {
            while (true) {
                try {
                    // 1. 从pending-list中获取订单  XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1L),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        // list为空说明pending-list里已经没有异常消息，结束循环
                        break;
                    }
                    // 2. 解析消息队列里取出的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> map = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(map, new VoucherOrder(), true);

                    // 3. 执行任务，创建订单
                    handleVoucherOrder(voucherOrder);

                    // 4. ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    Thread.sleep(20);
                }
            }
        }
    }



    /**
     * 秒杀券下单v2（基于阻塞队列实现异步优化）
     * @param
     * @return
     */
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//
//        // 1. 执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(), voucherId.toString(), userId.toString());
//
//        // 2. 判断用户是否有下单资格
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r== 1 ? "库存不足！" : "不允许重复下单！");
//        }
//
//        // 3. 允许下单，保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        Long orderId = redisIdWorker.next("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//        orderTasks.add(voucherOrder);
//
//        // 获取代理对象，放进成员变量，这样后续业务方便获取
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 4. 返回订单id
//        return Result.ok(orderId);
//
//    }

    // 创建线程任务从阻塞队列中获取订单
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // 从阻塞队列中获取订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 执行任务，创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常", e);
//                    throw new RuntimeException(e);
//                }
//            }
//        }
//    }


    //执行线程任务，实现异步下单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户，不能从线程中取，因为现在是专门执行异步任务的线程，不是主线程
        Long userId = voucherOrder.getUserId();

        // 2.创建锁对象，这里是用锁是作为兜底方案，理论上不会出现问题
        RLock lock = redissonClient.getLock("lock:order" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 异步任务，返回结果没有意义，通过日志记录即可
            log.error("不允许重复下单");
            return;
        }
        try {
            // 代理对象不能在这个负责执行异步任务的线程中获取，要在主线程中获取
            proxy.createVoucherOrder2(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }


    @Transactional
    public void createVoucherOrder2(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
        }

        // 创建订单
        save(voucherOrder);
    }



    /**
     * 秒杀券下单v1
     *
     * @param voucherId
     * @return
     */
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2.判断是否在可购买时间
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束！");
//        }
//        // 3.判断是否还有库存
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        // 7.返回订单id
//        Long userId = UserHolder.getUser().getId();
//        // 锁不应该加在方法上，锁的对象是this，意味着任意用户的请求都是串行执行，性能下降
//        // 应对不同的用户各自加锁，但是同一个id的用户每次请求都是新对象，即使加上toString()也是每次new一个对象
//        // intern()方法是String类的一个原生方法，它的作用是将字符串对象加入到 JVM 的字符串常量池中，并返回常量池中的引用
//        // 字符串值相同，返回的引用就相同，因此可以实现同一用户 ID 的多个线程串行执行，而不同用户 ID 的线程可以并发执行
//
//        // 但是也不能加在方法内部，因为@Transactional加在整个方法上，有可能锁释放了，但事务还没提交，数据库还没更改
//        // 此时锁释放意味着别的线程可以进行查询了，但查到的还是没有更新的数据库，存在并发安全问题
//        // 下面这种方式可以保证锁在事务提交后才释放
////        synchronized (userId.toString().intern()) {
////            // @Transactional的实现是Spring对某类的代理对象进行管理而实现的
////            // 此处其实是调用的this.createVoucherOrder方法，是本类内部方法调用，而非通过 Spring AOP 代理对象调用
////            // 加在这个方法上的@Transactional实际失效
////            // 拿到当前对象的代理对象，当前对象就是IVoucherOrderService接口
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//
//        // 分布式锁解决集群时会出现的并发安全问题
//        // Redis实现分布式锁
//        // SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
//
//        // 改用Redisson实现
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//
//        // 修改为Redisson的tryLock()方法
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 4.保证一人一单，判断用户是否已下过单，根据user_id+voucher_id查询order_id
        // 此处是要判断是否新增了一条数据，不能再使用乐观锁判断是否修改某个值，应使用悲观锁
        Long userId = UserHolder.getUser().getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }

        // 5.有库存且该用户未下过单，扣减库存
        // CAS方法实现乐观锁，执行扣减操作时，可以使用eq("stock", seckillVoucher.getStock())判断此时stock是否与之前查到的一致
        // 但从业务安全角度，只要库存大于0，所有线程都可以执行修改操作
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.next("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(orderId);
    }

}
