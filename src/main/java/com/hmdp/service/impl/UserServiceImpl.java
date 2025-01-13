package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result send(String phone, HttpSession session) {
        // 1. 首先验证手机号，是否合法，非法则返回Result.fail()
        if (RegexUtils.isPhoneInvalid(phone))
            return Result.fail("手机号格式错误！");

        // 2. 随机生成6位验证码，并将其保存至redis
        String code = RandomUtil.randomNumbers(6);
        String key = RedisConstants.LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 3. 将验证码发送给用户，此处仅做模拟
        log.info("验证码成功发送至用户：{}", code);

        // 4. 返回Result.ok()
        return Result.ok();
    }

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 检查手机号是否合法，不合法则返回Result.fail();
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone()))
            return Result.fail("手机号格式无效!");

        //2. 检验验证码是否有效，无效则返回Result.fail();
        String code = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + loginForm.getPhone());
        if (!loginForm.getCode().equals(code))
            return Result.fail("验证码错误！");

        //3. 检查用户是否存在，如果不存在需要插入用户数据
        User user = this.lambdaQuery().eq(User::getPhone, loginForm.getPhone()).one();
        if (user == null) {
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setNickName("user_" + RandomUtil.randomString(10));
            user.setPassword(loginForm.getPassword());
            this.save(user);
        }

        //4. 将用户数据保存至redis
        String key = RedisConstants.LOGIN_USER_KEY + UUID.randomUUID();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                new CopyOptions().ignoreNullValue().setFieldValueEditor((field, value) -> value.toString()));
        stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //5. 返回ok
        return Result.ok(key);
    }
}
