package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result saveComment(BlogComments comment);

    Result queryCommentById(Long id);

    Result queryCommentsByBlogId(Long blogId, Integer current);

    Result updateComment(BlogComments comment);

    Result deleteComment(Long id);
}
