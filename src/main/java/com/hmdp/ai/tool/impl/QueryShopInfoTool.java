package com.hmdp.ai.tool.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.tool.AiTool;
import com.hmdp.ai.tool.ToolResults;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 查商铺信息。公开数据，不需要归属校验。
 * <p>
 * 复用 {@link IShopService#queryById}——它已经带了缓存穿透/击穿的完整处理，
 * 没必要在工具里重写一遍查询逻辑。
 */
@Component
public class QueryShopInfoTool implements AiTool {

    @Resource
    private IShopService shopService;

    @Override
    public String name() {
        return "queryShopInfo";
    }

    @Override
    public String description() {
        return "根据商铺 id 查询商铺信息（名称、商圈、地址、均价、评分、营业时间、销量、评论数）。"
                + "当用户问「这家店在哪」「几点开门」「评分多少」时用这个工具。"
                + "shopId 必须是用户原话里给出的、或优惠券信息里返回的商铺 id。";
    }

    @Override
    public JSONObject parametersJsonSchema() {
        return JSONUtil.createObj()
                .set("type", "object")
                .set("properties", JSONUtil.createObj().set("shopId", JSONUtil.createObj()
                        .set("type", "string")
                        .set("description", "商铺 id，纯数字字符串")))
                .set("required", new JSONArray().put("shopId"));
    }

    @Override
    public String invoke(Long userId, JSONObject args) {
        Long shopId = ToolResults.parseId(args, "shopId");
        if (shopId == null) {
            return ToolResults.notFound();
        }
        // queryById 走到"店铺不存在"时返回 Result.fail，这里统一折叠成 notFound
        Result result = shopService.queryById(shopId);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || !(result.getData() instanceof Shop)) {
            return ToolResults.notFound();
        }
        return ToolResults.ok(ToolResults.shopBrief((Shop) result.getData()));
    }
}
