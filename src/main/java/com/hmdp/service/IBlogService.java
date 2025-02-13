package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;
import io.swagger.models.auth.In;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞博客
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    Result queryHotBlog(Integer current);

    Result queryUsersLikeBlog(Long id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollows(Long lastId, Integer offset);
}
