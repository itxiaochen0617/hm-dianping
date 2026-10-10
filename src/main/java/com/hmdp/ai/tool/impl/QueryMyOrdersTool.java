package com.hmdp.ai.tool.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.tool.AiTool;
import com.hmdp.ai.tool.ToolResults;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SystemConstants;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查「我的订单列表」。
 * <p>
 * ⚠️ <b>无参</b>——用户身份从登录态取，绝不作为工具参数。
 * 这是清单 4.5① 的要求：模型和前端都不能指定查谁的订单。
 */
@Component
public class QueryMyOrdersTool implements AiTool {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public String name() {
        return "queryMyOrders";
    }

    @Override
    public String description() {
        return "查询【当前登录用户自己】的订单列表，按下单时间倒序，最多返回 5 条。"
                + "不需要任何参数：用户身份由系统自动注入，你无法也无需指定。"
                + "当用户问「我最近有什么订单」「我的订单」时用这个工具。"
                + "如果你需要某个订单的完整信息，先用这个工具拿到 orderId，再调 queryOrderDetail。";
    }

    @Override
    public JSONObject parametersJsonSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", new JSONObject())
                .set("required", new JSONArray());
    }

    @Override
    public String invoke(Long userId, JSONObject args) {
        // ★ userId 只来自登录态。args 里就算有 userId 也不会被读
        List<VoucherOrder> orders = voucherOrderService.listMyOrders(userId, SystemConstants.DEFAULT_PAGE_SIZE);
        if (orders == null || orders.isEmpty()) {
            // 空列表也返回 found=true，让模型能说「您还没有订单」而不是「未找到」
            return ToolResults.okList(null);
        }
        List<JSONObject> briefs = orders.stream().map(ToolResults::orderBrief).collect(Collectors.toList());
        return ToolResults.okList(briefs);
    }
}
