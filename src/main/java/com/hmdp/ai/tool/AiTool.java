package com.hmdp.ai.tool;

import cn.hutool.json.JSONObject;

/**
 * 一个可以被大模型调用的工具。
 * <p>
 * 实现类都注册成 {@code @Component}，由 {@link ToolRegistry} 通过
 * {@code List<AiTool>} 收集成表。项目里<b>刻意不用注解 + 反射扫描</b>：
 * 5 个工具的场景下"新工具零改动"的收益等于零，而显式注册可读、可枚举、可审计——
 * 「工具集是编译期固定的闭集」正是本模块安全性的基石。
 */
public interface AiTool {

    /**
     * 工具名。<b>必须与模型看到的 function.name 完全一致</b>，也是注册表里的唯一键
     */
    String name();

    /**
     * 给模型看的用途说明。也是防注入的第一道措辞层（要明确划定工具边界）
     */
    String description();

    /**
     * 手写的 JSON Schema。参数形态只有"无参"和"单个 id"两种，手写字面量在
     * code review 时一眼可验，比反射生成更适合一个以安全可审计为卖点的模块
     */
    JSONObject parametersJsonSchema();

    /**
     * 执行查询。
     *
     * @param userId 当前登录用户 id，由 {@link ToolRegistry} 显式传入（来自
     *               {@code UserHolder}）。⚠️ <b>它绝不是 schema 里的属性</b>——
     *               模型无法指定查谁的数据，这是全部归属校验的前提
     * @param args   模型给的参数。⚠️ <b>完全不可信</b>，实现里只按名字取白名单字段
     * @return 一个 JSON 字符串，格式由 {@link ToolResults} 统一约定。
     * 实现方<b>允许</b>抛异常——{@link ToolRegistry#execute} 会兜住并转成结构化错误
     */
    String invoke(Long userId, JSONObject args);
}
