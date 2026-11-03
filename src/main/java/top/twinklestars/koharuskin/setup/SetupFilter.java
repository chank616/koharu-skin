package top.twinklestars.koharuskin.setup;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+50)
public class SetupFilter extends OncePerRequestFilter {
    private final SetupService setup;
    public SetupFilter(SetupService setup) { this.setup=setup; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.equals("/setup")||path.equals("/setup/")||path.startsWith("/app/")||path.equals("/error")||setup.installed()) {
            chain.doFilter(request,response);return;
        }
        if(request.getMethod().equals("GET")&&!path.startsWith("/api/")) response.sendRedirect(request.getContextPath()+"/setup");
        else {
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1,\"message\":\"请先完成站点安装\"}");
        }
    }
}
