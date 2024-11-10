package com.zsdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zsdp.dto.LoginFormDTO;
import com.zsdp.dto.Result;
import com.zsdp.dto.UserDTO;
import com.zsdp.entity.User;
import com.zsdp.mapper.UserMapper;
import com.zsdp.service.IUserService;
import com.zsdp.utils.RedisConstants;
import com.zsdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.zsdp.utils.RedisConstants.*;
import static com.zsdp.utils.SystemConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sentCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合返回错误信息
            return Result.fail("手机号错误");
        }
        //符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存到redis  并设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
        //session.setAttribute("code", code);
        //发送验证码
        log.info("验证码发生成功 为： {}",code);

        return Result.ok();
    }

    /**
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            log.info("手机号错误");
            return Result.fail("手机号错误");
        }

        String code = loginForm.getCode(); //传入的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);//Redis保存的验证码

        if (code==null){
            log.info("验证码为空");
            return Result.fail("验证码为空");
        } else if (!cacheCode.equals(code)) {
            log.info("验证码错误");
            return Result.fail("验证码错误");
        }

        //验证码正确  判断该手机号是否存在用户
        User user = query().eq("phone",phone).one(); //mybatis plus

        if (user==null) { //不存在则新建用户
            log.info("不存在 创建新用户");
            user = createUserWithPhone(phone);
        }
        //保存到redis
        //生成随机token 充当令牌
        String token = UUID.randomUUID().toString(true);
        //对象转换为hash
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //保存redis 类型为hash
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+ token, userMap);
        //session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        //设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+ token,LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //新建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        //保存新用户到数据库
        save(user);
        log.info("保存新用户到数据库");
        return user;
    }
}
