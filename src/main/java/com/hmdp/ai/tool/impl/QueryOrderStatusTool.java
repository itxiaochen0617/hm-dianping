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
 * 只查订单状态（"我的订单到哪了"）。
 * <p>
 * 与 {@link QueryOrderDetailTool} 数据上高度重叠，保留成独立的工具是为了给模型
 * 更清晰的意图边界——「到哪了」命中这个，「详情」命中那个，能减少选错工具的概率。
 * 投影更窄，也少喂一些不必要的数据进上下文。
 */
@Slf4j
@Component
public class QueryOrderStatusTool implements AiTool {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public String name() {
        return "queryOrderStatus";
    }

    @Override
    public String description() {
        return "只查询【当前登录用户自己】某个订单的当前状态（未支付/已支付/已核销/已取消/退款中/已退款）。"
                + "当用户问「我的订单到哪了」「这个订单状态是什么」时用这个工具，比 queryOrderDetail 更轻量。"
                + "orderId 必须原样使用用户给出的值。";
    }

    @Override
    public JSONObject parametersJsonSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj().set("orderId", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "订单号，18 位数字字符串，必须原样使用，不要改动任何一位")))
                .set("required", new JSONArray().put("orderId"));
    }

    @Override
    public String invoke(Long userId, JSONObject args) {
        Long orderId = ToolResults.parseId(args, "orderId");
        if (orderId == null) {
            return ToolResults.notFound();
        }
        VoucherOrder order = voucherOrderService.getMyOrder(userId, orderId);
        if (order == null) {
            log.debug("[AI] order not owned or missing userId={} orderId={}", userId, orderId);
            return ToolResults.notFound();
        }
        return ToolResults.ok(ToolResults.orderStatus(order));
    }
}
