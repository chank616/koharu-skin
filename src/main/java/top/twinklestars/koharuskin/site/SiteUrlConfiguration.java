package top.twinklestars.koharuskin.site;

import jakarta.servlet.Filter;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration
public class SiteUrlConfiguration {
    @Bean FilterRegistrationBean<Filter> siteUrlLinks(SiteUrls urls) {
        Filter filter=(request,response,chain)->{
            var http=(HttpServletRequest)request;
            String base=urls.webBase(http),context=http.getContextPath();
            chain.doFilter(request,new HttpServletResponseWrapper((HttpServletResponse)response) {
                @Override public String encodeURL(String url) { return super.encodeURL(SiteUrls.webLink(base,context,url)); }
                @Override public String encodeRedirectURL(String url) { return super.encodeRedirectURL(SiteUrls.webLink(base,context,url)); }
            });
        };
        var registration=new FilterRegistrationBean<>(filter);
        // 在安全过滤器之后包装响应，避免 DisableEncodeUrlFilter 覆盖页面链接处理。
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);return registration;
    }
}
