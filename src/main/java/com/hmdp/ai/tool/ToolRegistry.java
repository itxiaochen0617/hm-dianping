package com.hmdp.ai.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：收集所有 {@link AiTool} Bean，生成给模型看的 tools 定义，并统一执行入口。
 * <p>
 * 两个关键设计：
 * <ul>
 *   <li>工具定义在构造时生成<b>一次</b>，后续每次请求直接复用（不可变）</li>
 *   <li>{@link #execute} 内部 <b>catch 一切异常</b>并转成结构化 JSON 回给模型。
 *       若让异常冒泡，{@link com.hmdp.config.WebExceptionAdvice} 会把它变成
 *       含糊的"服务器异常"，用户拿不到任何有用信息</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    private final JSONArray definitions = new JSONArray();

    public ToolRegistry(List<AiTool> toolBeans) {
        if (toolBeans != null) {
            for (AiTool tool : toolBeans) {
                if (tools.putIfAbsent(tool.name(), tool) != null) {
                    // 工具名重复会让模型拿到两份同名定义，行为不可预期。宁可启动失败
                    throw new IllegalStateException("AI 工具名重复：" + tool.name());
                }
                JSONObject function = JSONUtil.createObj()
                        .set("name", tool.name())
                        .set("description", tool.description())
                        .set("parameters", tool.parametersJsonSchema());
                definitions.add(JSONUtil.createObj().set("type", "function").set("function", function));
            }
        }
        log.info("[AI] 已注册 {} 个工具：{}", tools.size(), tools.keySet());
    }

    public JSONArray toolDefinitions() {
        return definitions;
    }

    /**
     * 执行一次工具调用。
     *
     * @param userId   当前登录用户 id，来自 UserHolder（不是来自模型参数）
     * @param name     模型指定的工具名
     * @param argsJson 模型给的参数，是一个 JSON 字符串。<b>完全不可信</b>
     * @return 工具结果的 JSON 字符串。这个方法的契约是：<b>永不抛异常</b>
     */
    public String execute(Long userId, String name, String argsJson) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            // 正常模型不该产生这种调用。出现即意味着提示词注入或厂商行为异常
            log.warn("[AI] unknown tool name={} args={}", name, StrUtil.maxLength(argsJson, 200));
            return ToolResults.unknownTool();
        }

        long start = System.currentTimeMillis();
        String result;
        try {
            JSONObject args = StrUtil.isBlank(argsJson) ? new JSONObject() : JSONUtil.parseObj(argsJson);
            result = tool.invoke(userId, args);
        } catch (Exception e) {
            log.error("[AI] tool_call 执行失败 name={}", name, e);
            result = ToolResults.toolFailed();
        }

        // ★ 验收标准「能看到模型调了哪个函数、传了什么参数」靠的就是这一行。
        //   args 是模型可控字符串，必须截断再打，否则模型塞个换行就能伪造日志行
        log.info("[AI] tool_call name={} args={} cost={}ms result={}",
                name, StrUtil.maxLength(argsJson, 200), System.currentTimeMillis() - start,
                StrUtil.maxLength(result, 120));
        return result;
    }
}
