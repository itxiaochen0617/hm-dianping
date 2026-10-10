package com.hmdp.ai.tool.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.tool.AiTool;
import com.hmdp.ai.tool.ToolResults;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 查优惠券信息。公开数据，不需要归属校验。
 */
@Component
public class QueryVoucherInfoTool implements AiTool {

    @Resource
    private IVoucherService voucherService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public String name() {
        return "queryVoucherInfo";
    }

    @Override
    public String description() {
        return "根据优惠券 id 查询优惠券信息（标题、副标题、使用规则、支付金额、抵扣金额、库存、生效/失效时间）。"
                + "当用户问「这张券是什么」「还有多少张」「什么时候能用」时用这个工具。"
                + "voucherId 必须是用户原话里给出的、或订单信息里返回的优惠券 id。";
    }

    @Override
    public JSONObject parametersJsonSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj().set("voucherId", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "优惠券 id，纯数字字符串")))
                .set("required", new JSONArray().put("voucherId"));
    }

    @Override
    public String invoke(Long userId, JSONObject args) {
        Long voucherId = ToolResults.parseId(args, "voucherId");
        if (voucherId == null) {
            return ToolResults.notFound();
        }
        Voucher voucher = voucherService.getById(voucherId);
        // 只暴露上架中的券，下架/审核中的当作不存在
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getStatus())) {
            return ToolResults.notFound();
        }

        // ⚠️ Voucher.stock / beginTime / endTime 都标了 @TableField(exist = false)，
        //    getById(voucherId) 查不出来（恒为 null）。这几个字段在 tb_seckill_voucher 里，
        //    用主键直查一次补上——不补的话会回答出「库存：null」这种荒谬结果。
        //    普通券（非秒杀券）没有这条记录，补不上就是真的没有库存概念。
        SeckillVoucher seckill = seckillVoucherService.getById(voucherId);
        if (seckill != null) {
            voucher.setStock(seckill.getStock())
                    .setBeginTime(seckill.getBeginTime())
                    .setEndTime(seckill.getEndTime());
        }
        return ToolResults.ok(ToolResults.voucherBrief(voucher));
    }
}
