package com.hmdp.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工具结果的统一包装，以及<b>所有对外字段的白名单投影</b>。
 * <p>
 * 把投影集中在这一个类里，是为了让"哪些字段会流向大模型"这件事可以被一眼审计完。
 * 换来的性质是：工具结果里出现的字符串<b>永远是我们自己构造的</b>，
 * 不存在"用户可控文本进入工具返回值"的路径——这是防提示词注入最硬的一层。
 */
public final class ToolResults {

    /**
     * 「资源不存在」和「资源存在但不属于你」<b>必须返回逐字节相同的响应</b>，
     * 否则就能被用来枚举别人的 orderId
     */
    public static final String NOT_FOUND_MSG = "未找到相关记录";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ToolResults() {
    }

    // ==================== 结果包装 ====================

    public static String ok(Object data) {
        return JSONUtil.createObj().set("found", true).set("data", data).toString();
    }

    /** 列表类结果。空列表也要返回 found=true，让模型能说"您还没有订单"而不是"未找到" */
    public static String okList(List<?> list) {
        return JSONUtil.createObj()
                .set("found", true)
                .set("total", list == null ? 0 : list.size())
                .set("data", list == null ? new JSONArray() : list)
                .toString();
    }

    public static String notFound() {
        return JSONUtil.createObj().set("found", false).set("message", NOT_FOUND_MSG).toString();
    }

    /** 模型调了不存在的工具名。正常模型不该产生，出现即注入信号 */
    public static String unknownTool() {
        return JSONUtil.createObj().set("found", false).set("error", "unknown_tool").toString();
    }

    /** 工具内部抛了异常。把错误<b>当作结果</b>回给模型，而不是让它冒泡成"服务器异常" */
    public static String toolFailed() {
        return JSONUtil.createObj().set("found", false).set("error", "tool_failed").toString();
    }

    // ==================== 参数校验 ====================

    /**
     * 解析模型给的资源 id。
     * <p>
     * ⚠️ 模型给的参数完全不可信。这里做三件事：
     * <ol>
     *   <li><b>只按名字取白名单字段</b>（而不是遍历 args），模型塞进来的
     *       额外字段（比如 userId）会被直接忽略</li>
     *   <li>逐字符判数字，避开 {@code +123}、{@code 1e5}、{@code " 123 "}、全角数字等边界</li>
     *   <li>长度与范围校验（Long 最多 19 位，id 必须为正）</li>
     * </ol>
     * 校验失败返回 {@code null}，由调用方统一走 {@link #notFound()}。
     * <b>不抛异常</b>——抛异常会让模型看到"工具失败了"并改参数重试，白烧轮数
     */
    public static Long parseId(JSONObject args, String field) {
        if (args == null) {
            return null;
        }
        String s = args.getStr(field);
        if (StrUtil.isBlank(s)) {
            return null;
        }
        s = s.trim();
        if (s.length() > 19 || !isAllDigits(s)) {
            return null;
        }
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 字段投影（白名单） ====================

    /**
     * ⚠️ {@code VoucherOrder.status} 是 Integer 且<b>项目里没有枚举</b>，
     * 状态含义只存在于实体注释里。映射表必须写在这里——别指望模型懂 {@code 2} 是什么
     */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "未支付";
            case 2:
                return "已支付";
            case 3:
                return "已核销";
            case 4:
                return "已取消";
            case 5:
                return "退款中";
            case 6:
                return "已退款";
            default:
                return "未知状态";
        }
    }

    /**
     * 订单列表项。不含 userId（对模型无意义且是信息泄露）、payType、updateTime
     */
    public static JSONObject orderBrief(VoucherOrder o) {
        return JSONUtil.createObj()
                .set("orderId", String.valueOf(o.getId()))
                .set("voucherId", String.valueOf(o.getVoucherId()))
                .set("status", statusText(o.getStatus()))
                .set("createTime", fmt(o.getCreateTime()));
    }

    public static JSONObject orderDetail(VoucherOrder o) {
        return orderBrief(o)
                .set("payTime", fmt(o.getPayTime()))
                .set("useTime", fmt(o.getUseTime()));
    }

    /** 只回答"到哪了"时用的最窄投影 */
    public static JSONObject orderStatus(VoucherOrder o) {
        return JSONUtil.createObj()
                .set("orderId", String.valueOf(o.getId()))
                .set("status", statusText(o.getStatus()))
                .set("createTime", fmt(o.getCreateTime()));
    }

    public static JSONObject voucherBrief(Voucher v) {
        return JSONUtil.createObj()
                .set("voucherId", String.valueOf(v.getId()))
                .set("shopId", String.valueOf(v.getShopId()))
                .set("title", v.getTitle())
                .set("subTitle", v.getSubTitle())
                .set("rules", v.getRules())
                // 金额库里存的是「分」，直接给模型会得到"支付金额 990"这种误读
                .set("payValueYuan", fen2yuan(v.getPayValue()))
                .set("actualValueYuan", fen2yuan(v.getActualValue()))
                .set("stock", v.getStock())
                .set("beginTime", fmt(v.getBeginTime()))
                .set("endTime", fmt(v.getEndTime()));
    }

    public static JSONObject shopBrief(Shop s) {
        return JSONUtil.createObj()
                .set("shopId", String.valueOf(s.getId()))
                .set("name", s.getName())
                .set("area", s.getArea())
                .set("address", s.getAddress())
                .set("avgPrice", s.getAvgPrice())
                // 评分是乘 10 保存的整数，还原成 4.5 这种可读形式
                .set("score", s.getScore() == null ? null : s.getScore() / 10.0)
                .set("openHours", s.getOpenHours())
                .set("sold", s.getSold())
                .set("comments", s.getComments());
    }

    // ==================== 小工具 ====================

    private static String fen2yuan(Long fen) {
        return fen == null ? null : String.format("%.2f", fen / 100.0);
    }

    /** 时间格式化成可读文本。返回 null 时 hutool 会直接省略该字段，这正是我们想要的 */
    private static String fmt(LocalDateTime t) {
        return t == null ? null : TIME_FMT.format(t);
    }
}
