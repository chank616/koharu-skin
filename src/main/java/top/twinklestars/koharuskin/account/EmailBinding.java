package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.io.IOException;

@Configuration
public class EmailBinding implements WebMvcConfigurer, HandlerInterceptor {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).order(-100).addPathPatterns("/user/**","/admin/**","/oauth/**","/union/**","/texture/**","/skinlib/upload","/skinlib/report");
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof AccountUser user)||!user.email().isEmpty()) return true;
        if(request.getRequestURI().startsWith(request.getContextPath()+"/texture/")&&java.util.Set.of("GET","HEAD").contains(request.getMethod())) return true;
        if(request.getMethod().equals("GET")&&(request.getHeader("Accept")==null||!request.getHeader("Accept").contains("application/json"))) {
            response.sendRedirect(request.getContextPath()+"/auth/bind");
        } else {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1,\"message\":\"请先绑定邮箱\",\"data\":{\"redirectTo\":\"/auth/bind\"}}");
        }
        return false;
    }
}
