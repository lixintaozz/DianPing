package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //1. 首先查询博客信息
        Blog blog = getById(id);
        if (blog == null)
            return Result.fail("博客不存在！");
        Long userId = blog.getUserId();
        //2. 然后查询用户信息
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());

        //3. 检查博客是否已经被点赞
        Long UserId = UserHolder.getUser().getId();
        String key = "blog:like:" + id;
        Boolean aBoolean = stringRedisTemplate.opsForSet().isMember(key, UserId.toString());
        blog.setIsLike(aBoolean);
        return Result.ok(blog);
    }

    /**
     * 点赞博客
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like:" + id;
        Boolean aBoolean = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        if (BooleanUtil.isTrue(aBoolean))
        {
            boolean update = lambdaUpdate().setSql("liked = liked - 1").eq(Blog::getId, id).update();
            if (update)
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
        }else
        {
            boolean update = lambdaUpdate().setSql("liked = liked + 1").eq(Blog::getId, id).update();
            if (update)
                stringRedisTemplate.opsForSet().add(key, userId.toString());
        }

        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            Long UserId = UserHolder.getUser().getId();
            String key = "blog:like:" + blog.getId();
            Boolean aBoolean = stringRedisTemplate.opsForSet().isMember(key, UserId.toString());
            blog.setIsLike(aBoolean);
        });
        return Result.ok(records);
    }
}
