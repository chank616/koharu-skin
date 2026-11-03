package top.twinklestars.koharuskin.oauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.AccountMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class OAuthBrowserFilter extends OncePerRequestFilter {
    private final HttpSessionCsrfTokenRepository csrf;
    private final AccountMapper users;
    private final SiteOptions options;
    public OAuthBrowserFilter(HttpSessionCsrfTokenRepository csrf,AccountMapper users,SiteOptions options) {
        this.csrf=csrf; this.users=users; this.options=options;
    }
    public static String afterLogin(HttpSession session) {
        String target=(String)session.getAttribute("oauth.returnTo"); session.removeAttribute("oauth.returnTo");
        return target==null?"/user":target;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!request.getRequestURI().equals(request.getContextPath()+"/oauth/authorize")) { chain.doFilter(request,response); return; }
        var previous=SecurityContextHolder.getContext(); var authentication=previous.getAuthentication();
        if(authentication==null||!(authentication.getPrincipal() instanceof AccountUser user)) {
            if(request.getMethod().equals("GET")) {
                request.getSession().setAttribute("oauth.returnTo",request.getContextPath()+"/oauth/authorize"+(request.getQueryString()==null?"":"?"+request.getQueryString()));
                response.sendRedirect(request.getContextPath()+"/auth/login");
            } else response.sendError(401);
            return;
        }
        if(user.email().isEmpty()) {
            if(request.getMethod().equals("GET")) {
                request.getSession().setAttribute("oauth.returnTo",request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));
                response.sendRedirect(request.getContextPath()+"/auth/bind");
            } else response.sendError(409,"请先绑定邮箱");
            return;
        }
        if(options.enabled("require_verification",false)&&!users.verified(user.uid())) { response.sendError(403,"请先验证邮箱"); return; }
        if(request.getMethod().equals("POST")) {
            var expected=csrf.loadDeferredToken(request,response).get();
            String provided=new XorCsrfTokenRequestAttributeHandler().resolveCsrfTokenValue(request,expected);
            if(provided==null||!MessageDigest.isEqual(expected.getToken().getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(403,"授权表单已失效，请重新打开授权页面"); return;
            }
        }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()) {
            @Override public String getName() { return Long.toString(user.uid()); }
        });
        SecurityContextHolder.setContext(context);
        if("code".equals(request.getParameter("response_type"))&&request.getParameter("scope")==null) {
            request=new HttpServletRequestWrapper(request) {
                @Override public String getQueryString() { return getMethod().equals("GET")?(super.getQueryString()==null?"":super.getQueryString()+"&")+"scope=User.Read":super.getQueryString(); }
                @Override public String getParameter(String name) { return name.equals("scope")?"User.Read":super.getParameter(name); }
                @Override public String[] getParameterValues(String name) { return name.equals("scope")?new String[]{"User.Read"}:super.getParameterValues(name); }
                @Override public Map<String,String[]> getParameterMap() { var map=new HashMap<>(super.getParameterMap()); map.put("scope",new String[]{"User.Read"}); return map; }
                @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(getParameterMap().keySet()); }
            };
        }
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.setContext(previous); }
    }
}
