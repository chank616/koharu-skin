package top.twinklestars.koharuskin.extension;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.player.PlayerMapper;
import java.io.IOException;

@Configuration
public class PlayerBinding implements WebMvcConfigurer, HandlerInterceptor {
    private final OfficialExtensions extensions;
    private final PlayerMapper players;
    public PlayerBinding(OfficialExtensions extensions, PlayerMapper players) { this.extensions=extensions; this.players=players; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/user/**").excludePathPatterns("/user/player/list", "/user/player/bind");
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        var user = (AccountUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!extensions.enabled("single-player-limit") || players.list(user.uid()).size() == 1) return true;
        if (request.getMethod().equals("GET") && !"application/json".equals(request.getHeader("Accept"))) {
            response.sendRedirect(request.getContextPath()+"/user/player/bind");
        } else {
            response.setStatus(409);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1,\"message\":\"请先绑定唯一角色\",\"data\":{\"redirectTo\":\"/user/player/bind\"}}");
        }
        return false;
    }
}
