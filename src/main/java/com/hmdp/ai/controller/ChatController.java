package com.hmdp.ai.controller;

import com.hmdp.ai.service.ChatService;
import com.hmdp.dto.ChatFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 智能客服入口。
 * <p>
 * ⚠️ {@code /chat} <b>刻意不加入</b> {@code MvcConfig.excludePathPatterns}：
 * 它天然受 LoginInterceptor(order 1) 保护，拿不到 {@code UserHolder} 就拿不到 userId，
 * 全部归属校验也就无从谈起。为了"顺便支持匿名客服"而把它加进白名单，
 * 会让整个模块的安全设计失效。
 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private ChatService chatService;

    @PostMapping
    public Result chat(@RequestBody ChatFormDTO form) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 防御性判断：正常情况下 LoginInterceptor 已经挡掉了
            return Result.fail("请先登录");
        }
        return chatService.chat(user.getId(), form);
    }
}
