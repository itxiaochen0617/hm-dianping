package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Override
    @Transactional
    public Result saveComment(BlogComments comment) {
        // 1.校验内容
        if (comment == null || StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空！");
        }
        // 2.校验笔记是否存在
        Blog blog = blogService.getById(comment.getBlogId());
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 3.补全服务端字段，忽略前端传入的 id / userId
        comment.setId(null);
        comment.setUserId(UserHolder.getUser().getId());
        if (comment.getParentId() == null) {
            comment.setParentId(0L);
        }
        if (comment.getAnswerId() == null) {
            comment.setAnswerId(0L);
        }
        comment.setLiked(0);
        comment.setStatus(false);
        // 4.保存评论
        save(comment);
        // 5.笔记的评论数 +1（comments 列可为 NULL，用 IFNULL 兜底）
        blogService.update()
                .setSql("comments = IFNULL(comments, 0) + 1")
                .eq("id", comment.getBlogId())
                .update();
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryCommentById(Long id) {
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在！");
        }
        return Result.ok(comment);
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer current) {
        // 按时间倒序分页，只取状态正常的（status = 0）
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("status", 0)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result updateComment(BlogComments comment) {
        // 1.参数校验
        if (comment == null || comment.getId() == null) {
            return Result.fail("评论id不能为空！");
        }
        if (StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空！");
        }
        // 2.校验评论存在且属于当前用户
        BlogComments old = getById(comment.getId());
        if (old == null) {
            return Result.fail("评论不存在！");
        }
        if (!old.getUserId().equals(UserHolder.getUser().getId())) {
            return Result.fail("只能修改自己的评论！");
        }
        // 3.只允许改内容，其余字段不接受前端覆盖
        updateById(new BlogComments().setId(comment.getId()).setContent(comment.getContent()));
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteComment(Long id) {
        // 1.校验评论存在且属于当前用户
        BlogComments old = getById(id);
        if (old == null) {
            return Result.fail("评论不存在！");
        }
        if (!old.getUserId().equals(UserHolder.getUser().getId())) {
            return Result.fail("只能删除自己的评论！");
        }
        // 2.删除评论
        removeById(id);
        // 3.笔记的评论数 -1，gt 兜底防止减成负数
        blogService.update()
                .setSql("comments = IFNULL(comments, 0) - 1")
                .eq("id", old.getBlogId())
                .gt("comments", 0)
                .update();
        return Result.ok();
    }
}
