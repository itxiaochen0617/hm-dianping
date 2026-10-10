package com.hmdp.ai.tool.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.tool.AiTool;
import com.hmdp.ai.tool.ToolResults;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 查单个订单的详情。
 * <p>
 * ⚠️ 归属校验<b>下推到 SQL 的 WHERE 里</b>（见
 * {@link IVoucherOrderService#getMyOrder}），而不是"查出来再比 userId"——
 * 数据库层面就不可能返回别人的行，不存在"忘了比较"的漏洞。
 * <p>
 * 「订单不存在」和「订单不是你的」返回<b>逐字节相同</b>的响应，防止被用来枚举别人的订单号。
 */
@Slf4j
@Component
public class QueryOrderDetailTool implements AiTool {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public String name() {
        return "queryOrderDetail";
    }

    @Override
    public String description() {
        return "根据订单号查询【当前登录用户自己】的某个订单详情，包含状态、下单时间、支付时间、核销时间。"
                + "orderId 必须是用户原话里给出的、或 queryMyOrders 返回的订单号，不要自己编造或改动任何一位。"
                + "只能查到当前登录用户自己的订单，其他用户的订单会返回「未找到相关记录」。";
    }

    @Override
    public JSONObject parametersJsonSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj().set("orderId", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "订单号，18 位数字字符串，必须原样使用，不要拼接、补零或改动任何一位")))
                .set("required", new JSONArray().put("orderId"));
    }

    @Override
    public String invoke(Long userId, JSONObject args) {
        Long orderId = ToolResults.parseId(args, "orderId");
        if (orderId == null) {
            // 参数非法 → 与「不属于你」返回同款响应，不做任何区分
            return ToolResults.notFound();
        }
        VoucherOrder order = voucherOrderService.getMyOrder(userId, orderId);
        if (order == null) {
            // ⚠️ 只进日志，绝不进响应体。同一次试探和试一个随机数，用户看到的字节完全相同
            log.debug("[AI] order not owned or missing userId={} orderId={}", userId, orderId);
            return ToolResults.notFound();
        }
        return ToolResults.ok(ToolResults.orderDetail(order));
    }
}
