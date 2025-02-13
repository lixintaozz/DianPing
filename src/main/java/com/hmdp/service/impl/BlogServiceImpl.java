package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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

    @Autowired
    private IFollowService followService;

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
        isLike(blog);
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
        Double aDouble = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (aDouble != null)
        {
            boolean update = lambdaUpdate().setSql("liked = liked - 1").eq(Blog::getId, id).update();
            if (update)
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }else
        {
            boolean update = lambdaUpdate().setSql("liked = liked + 1").eq(Blog::getId, id).update();
            if (update)
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
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
            isLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryUsersLikeBlog(Long id) {
        //1. 查询Top5的用户id
        Set<String> ids = stringRedisTemplate.opsForZSet().range("blog:like:" + id, 0, 4);

        if (ids == null || ids.isEmpty())
            return Result.ok(Collections.emptyList());

        //2. 将ids转换为List<Long>
        List<Long> userIds = new ArrayList<>();
        for (String userId : ids)
            userIds.add(Long.valueOf(userId));
        String join = StringUtils.join(userIds, ",");


        //3. 去数据库中查询用户数据
        List<User> users = userService.lambdaQuery()
                .in(User::getId, userIds)
                .last("order by field(" + "id," + join +")")
                .list();
        //4. 返回用户数据
        List<UserDTO> userDTOS = new ArrayList<>();
        users.forEach(
                user -> {
                    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                    userDTOS.add(userDTO);
                }
        );
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        //先查询当前用户的粉丝
        List<Follow> follows = followService.lambdaQuery().eq(Follow::getFollowUserId, user.getId()).list();
        if (!follows.isEmpty()) {
            //将博文保存至粉丝的邮箱
            for (Follow follow : follows) {
                stringRedisTemplate.opsForZSet()
                        .add("feed:" + follow.getUserId(), blog.getId().toString(), System.currentTimeMillis());
            }
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollows(Long lastId, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 3);

        if (typedTuples == null || typedTuples.isEmpty())
            return Result.ok();

        List<Long> ids = new ArrayList<>();
        long min_time = 0L;
        int cnt = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long score = tuple.getScore().longValue();
            if (score == min_time)
                ++ cnt;
            else
            {
                min_time = score;
                cnt = 1;
            }
        }

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = lambdaQuery().in(Blog::getId, ids).last("order by field(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            Long UserId = blog.getUserId();
            //2. 然后查询用户信息
            User user = userService.getById(UserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            //3. 检查博客是否已经被点赞
            isLike(blog);
        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setData(blogs);
        scrollResult.setOffset(cnt);
        scrollResult.setMinTime(min_time);

        return Result.ok(scrollResult);
    }

    private void isLike(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            Long UserId = user.getId();
            String key = "blog:like:" + blog.getId();
            Double aBoolean = stringRedisTemplate.opsForZSet().score(key, UserId.toString());
            blog.setIsLike(aBoolean != null);
        }
    }
}
