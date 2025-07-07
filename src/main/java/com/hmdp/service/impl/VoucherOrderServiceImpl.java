package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 秒杀券下单
     *
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2.判断是否在可购买时间
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束！");
        }
        // 3.判断是否还有库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        // 7.返回订单id
        Long userId = UserHolder.getUser().getId();
        // 锁不应该加在方法上，锁的对象是this，意味着任意用户的请求都是串行执行，性能下降
        // 应对不同的用户各自加锁，但是同一个id的用户每次请求都是新对象，即使加上toString()也是每次new一个对象
        // intern()方法是String类的一个原生方法，它的作用是将字符串对象加入到 JVM 的字符串常量池中，并返回常量池中的引用
        // 字符串值相同，返回的引用就相同，因此可以实现同一用户 ID 的多个线程串行执行，而不同用户 ID 的线程可以并发执行

        // 但是也不能加在方法内部，因为@Transactional加在整个方法上，有可能锁释放了，但事务还没提交，数据库还没更改
        // 此时锁释放意味着别的线程可以进行查询了，但查到的还是没有更新的数据库，存在并发安全问题
        // 下面这种方式可以保证锁在事务提交后才释放
//        synchronized (userId.toString().intern()) {
//            // @Transactional的实现是Spring对某类的代理对象进行管理而实现的
//            // 此处其实是调用的this.createVoucherOrder方法，是本类内部方法调用，而非通过 Spring AOP 代理对象调用
//            // 加在这个方法上的@Transactional实际失效
//            // 拿到当前对象的代理对象，当前对象就是IVoucherOrderService接口
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        // 分布式锁解决集群时会出现的并发安全问题
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        boolean isLock = lock.tryLock(1200L);
        if (!isLock) {
            return Result.fail("不允许重复下单！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

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
