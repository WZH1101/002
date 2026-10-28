package com.ctrip.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时未支付订单的自动取消任务。
 *
 * <p>为什么必须有它：下单只是锁库存（状态「待支付」），用户关掉页面就没人来释放这部分库存。
 * 以前不存在这个问题——下单即「已支付」，钱和库存同时定了。现在把支付拆成一步，
 * 就必须给未支付订单一 expire 出口，否则酒店房型、车票余座会被空占。
 *
 * <p>每分钟扫一次（fixedDelay：上一轮跑完再计时，避免慢查询叠加）。
 * 单机部署够用；多实例时要么加分布式锁，要么让 UPDATE 的 {@code status='待支付'} 条件兜底
 * ——两个实例同时扫到同一单，后动手的那个 changed=0，不会二次回补库存。
 */
@Component
@EnableScheduling
public class OrderExpiryTask {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryTask.class);

    private final OrderService orderService;

    public OrderExpiryTask(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${ctrip.order.expiry-scan-ms:60000}", initialDelay = 15_000)
    public void cancelExpired() {
        try {
            int n = orderService.cancelExpiredOrders();
            if (n > 0) {
                log.info("支付超时自动取消 {} 条订单（库存已回补），支付窗口 {} 分钟",
                        n, orderService.getPayDeadlineMinutes());
            }
        } catch (Exception e) {
            // 定时任务里抛出去会终止后续调度，这里兜住并记日志
            log.warn("超时订单扫描失败，下一轮重试", e);
        }
    }
}
