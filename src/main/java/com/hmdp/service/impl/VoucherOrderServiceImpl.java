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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String STREAM_ORDERS = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 单线程异步消费者：串行消费 Stream 中的订单，避免并发落库造成重复下单。
    // 用 daemon 线程：万一关停逻辑失效，也不会把 JVM 挂住
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seckill-order-consumer");
        t.setDaemon(true);
        return t;
    });

    // 消费线程的运行标志。容器关停时置为 false 让循环及时退出。
    // 不这样做的话，线程会继续拿着已销毁的 LettuceConnectionFactory 反复报错，
    // 而且因为是非守护线程，JVM 根本退不出去
    private volatile boolean running = true;

    // 是否启用秒杀订单消费者。Redis 低于 5.0 时没有 Stream，消费线程会持续报错，
    // 此时可在配置里设为 false 关掉它（本机 Redis 3.2 就是这么用的）
    @Value("${hmdp.seckill.consumer-enabled:true}")
    private boolean consumerEnabled;

    // 类初始化完成后就拉起后台消费线程
    @PostConstruct
    private void init() {
        if (!consumerEnabled) {
            log.warn("秒杀订单消费者未启用（hmdp.seckill.consumer-enabled=false），秒杀下单不会异步落库");
            return;
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 容器关停时停止消费线程
    @PreDestroy
    public void destroy() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
        log.info("正在停止秒杀订单消费者...");
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (running) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDERS, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.创建订单
                    createVoucherOrder(voucherOrder);
                    // 5.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    if (!running) {
                        // 关停中：连接工厂已销毁，这里报错属预期，不记日志
                        break;
                    }
                    log.error("处理订单异常", e);
                    // 处理异常消息
                    handlePendingList();
                    // 退避 1 秒：Redis 持续报错时（版本不支持 Stream、网络断、消费组没建），
                    // 不退避会形成每秒几十次的紧循环，日志瞬间涨到几百 MB
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void handlePendingList() {
            // 连续失败计数：读 pending-list 本身一直失败（比如 Redis 不支持 Stream），
            // 说明是环境问题而不是某条消息的问题，重试几次就放弃，否则会无限刷日志
            int failCount = 0;
            while (running) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_ORDERS, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 3.解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4.创建订单
                    createVoucherOrder(voucherOrder);
                    // 5.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDERS, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    if (!running) {
                        // 关停中，直接退出
                        break;
                    }
                    if (++failCount >= 3) {
                        log.error("pending-list 连续 {} 次处理失败，放弃本轮补偿（检查 Redis 是否支持 Stream、消费组是否已创建）", failCount);
                        break;
                    }
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("用户已经购买过了");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }
}
