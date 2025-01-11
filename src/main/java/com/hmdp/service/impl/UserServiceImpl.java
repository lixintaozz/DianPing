package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

        // 2. 随机生成6位验证码，并将其保存至session
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code", code);

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
        if (!loginForm.getCode().equals(session.getAttribute("code")))
            return Result.fail("验证码错误！");

        //3. 检查用户是否存在，如果不存在需要插入用户数据
        User user = this.lambdaQuery().eq(User::getPhone, loginForm.getPhone()).one();
        if (user == null) {
            user = new User();
            user.setPhone(loginForm.getPhone());
            user.setPassword(loginForm.getPassword());
            this.save(user);
        }

        //4. 将用户数据保存至session
        session.setAttribute("userInfo", BeanUtil.copyProperties(user, UserDTO.class));

        //5. 返回ok
        return Result.ok();
    }
}
