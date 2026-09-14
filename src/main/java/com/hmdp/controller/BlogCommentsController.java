package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /**
     * 新增评论 / 回复
     */
    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        return blogCommentsService.saveComment(comment);
    }

    /**
     * 查询单条评论
     */
    @GetMapping("/{id}")
    public Result queryCommentById(@PathVariable("id") Long id) {
        return blogCommentsService.queryCommentById(id);
    }

    /**
     * 分页查询某篇笔记的评论
     */
    @GetMapping("/of/blog/{blogId}")
    public Result queryCommentsByBlogId(
            @PathVariable("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current);
    }

    /**
     * 修改自己的评论（只允许改内容）
     */
    @PutMapping
    public Result updateComment(@RequestBody BlogComments comment) {
        return blogCommentsService.updateComment(comment);
    }

    /**
     * 删除自己的评论
     */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }
}
