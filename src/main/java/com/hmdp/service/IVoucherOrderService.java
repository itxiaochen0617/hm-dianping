package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 查某个用户最近的订单。给智能客服的 queryMyOrders 工具用。
     * <p>
     * ⚠️ userId 由调用方从登录态取得，绝不来自请求参数。
     *
     * @param limit 条数上限，方法内部会再夹到 {@code SystemConstants.MAX_PAGE_SIZE}
     */
    List<VoucherOrder> listMyOrders(Long userId, int limit);

    /**
     * 查某个用户自己的某一笔订单。给智能客服的 queryOrderDetail / queryOrderStatus 用。
     * <p>
     * ⚠️ 归属校验直接下推到 SQL 的 WHERE 里，返回 null 同时代表
     * 「不存在」和「不是你的」——调用方必须让这两种情况返回一模一样的响应，
     * 否则可以被用来枚举别人的订单号。
     */
    VoucherOrder getMyOrder(Long userId, Long orderId);
}
