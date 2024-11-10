package com.zsdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.zsdp.dto.Result;
import com.zsdp.entity.VoucherOrder;
import com.zsdp.mapper.VoucherOrderMapper;
import com.zsdp.service.ISeckillVoucherService;
import com.zsdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zsdp.utils.RedisIdWorker;
import com.zsdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired  //全局ID
    private RedisIdWorker redisIdWorker;
    @Autowired  //redis操作对象
    private StringRedisTemplate stringRedisTemplate;

    @Autowired //redisson 分布式锁
    private RedissonClient redissonClient;
    //代理对象
    private IVoucherOrderService proxy ;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;//移出锁脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //阻塞队列
    //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {
        String queneName="stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取redis队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1
                    // BLOCK 2000 STREAM streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queneName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        //失败 说明没有消息 继续等待
                        continue;
                    }
                    //成功
                    //解析消息队列中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);

                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("消费消息队列异常", e);
                    handlePendingList();

                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pendinglist队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAM streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queneName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        //如果为空 说明没有异常信息 结束循环
                        break;
                    }
                    //成功
                    //解析消息队列中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);

                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("确认pendinglist异常", e);
                }
            }
        }
    }


/*// 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/
    //处理订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户 全新的线程 无法从ThreadLocal获取id
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock() ;
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    //redis stream消息队列
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId =redisIdWorker.nextId("Order");
        //1.执行lua脚本
        Long result =stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),voucherId.toString(),userId.toString(),String.valueOf(orderId));
        //2.判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            //3.不为0 代表没有购买资格
            return Result.fail(r==1? "库存不足！":"不能重复下单！");
        }
        log.info("购买！");

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //6.返回订单信息
        return Result.ok(orderId);
    }


    //秒杀优化
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId =redisIdWorker.nextId("Order");
        //1.执行lua脚本
        Long result =stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),voucherId.toString(),userId.toString(),String.valueOf(orderId));
        //2.判断结果是否为0
        int r = result.intValue();
        if(r!=0){
            //3.不为0 代表没有购买资格
            return Result.fail(r==1? "库存不足！":"不能重复下单！");
        }


        log.info("购买！");

        //4.为0 有购买资格  把下单信息保存到阻塞队列

        //TODO 阻塞队列
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 5.1.订单id
        voucherOrder.setId(orderId);
        // 5.2.用户id
        voucherOrder.setUserId(userId);
        // 5.3.代金券id
        voucherOrder.setVoucherId(voucherId);

        //保存到阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //6.返回订单信息
        return Result.ok(orderId);
    }*/
    /**
     * @param voucherOrder
     */
   /*//多表操作 添加事务注解
    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //不存在 返回错误信息
        if (voucher == null) {
            return Result.fail("优惠券不存在！");
        }
        //存在
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        //查看是否还有库存
        if (voucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        //悲观锁 加载用户id上 限定同一个用户  一人一单 intern可以把字符串规范表达相同字符串是一个对象
        //锁加载方法外  避免被方法的事务包裹导致超卖
        *//*synchronized(userId.toString().intern()){
            //获得代理对象 避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*
        //获取锁对象来创建锁
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取Redisson分布式锁
        RLock rLock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        //boolean isLock = lock.tryLock(5);
        boolean isLock = rLock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            return Result.fail("重复下单！");
        }
        try{
            //获得代理对象 避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            rLock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return ;
        }
        //5，扣减库存
        //数据库锁解决超卖
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).gt("stock",0).update();

        if (!success) {
            //扣减库存
            log.error("库存不足！");
            return ;
        }

        save(voucherOrder);
    }
}
