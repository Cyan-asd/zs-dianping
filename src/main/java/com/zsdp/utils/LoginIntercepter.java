package com.zsdp.utils;


import com.zsdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author cyan
 * @version 1.0
 */
public class LoginIntercepter implements HandlerInterceptor {



    //构造器注入
    public LoginIntercepter() {

    }

    /**
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从ThreadLocal获取对象
        UserDTO user = UserHolder.getUser();

        if (user == null) {
         response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }

        return true;
    }

    /**
     * @param request
     * @param response
     * @param handler
     * @param ex
     * @throws Exception
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
