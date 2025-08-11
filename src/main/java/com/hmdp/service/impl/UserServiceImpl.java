package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

import cn.hutool.core.bean.BeanUtil;


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
    @Override
    public Result sendcode(String phone, HttpSession session) {
        // 验证手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code", code);
        // 发送验证码
        log.info("验证码发送成功：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 验证手机号格式
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        Object code = session.getAttribute("code");
        if (code == null || !loginForm.getCode().equals(code)) {
            return Result.fail("验证码错误或无效");
        }
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null){
            user = createUser(loginForm);
        }
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUser(LoginFormDTO loginForm) {
        User user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
