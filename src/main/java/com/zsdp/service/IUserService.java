package com.zsdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zsdp.dto.LoginFormDTO;
import com.zsdp.dto.Result;
import com.zsdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sentCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
