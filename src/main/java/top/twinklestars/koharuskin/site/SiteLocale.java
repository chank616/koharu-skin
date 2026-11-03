package top.twinklestars.koharuskin.site;

import jakarta.servlet.http.*;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Component("localeResolver")
public class SiteLocale implements LocaleResolver, WebMvcConfigurer {
    public static final Map<String,String> LANGUAGES=Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
            "zh_CN","中文（简体）","zh_TW","中文（正體）","en","English","es_ES","Español","ru_RU","Русский язык")));

    public static String normalize(String value) {
        return switch(value.replace('-','_').toLowerCase(Locale.ROOT)) {
            case "zh", "zh_cn", "zh_hans", "zh_hans_cn" -> "zh_CN";
            case "zh_tw", "zh_hant", "zh_hant_tw" -> "zh_TW";
            case "en", "en_us", "en_gb" -> "en";
            case "es", "es_es" -> "es_ES";
            case "ru", "ru_ru" -> "ru_RU";
            default -> "en";
        };
    }
    public static String current() { return LocaleContextHolder.getLocaleContext()==null?"zh_CN":normalize(LocaleContextHolder.getLocale().toLanguageTag()); }

    @Override public Locale resolveLocale(HttpServletRequest request) {
        String selected=request.getParameter("lang");
        if(selected==null&&request.getCookies()!=null)
            for(var cookie:request.getCookies()) if(cookie.getName().equals("locale")) { selected=cookie.getValue();break; }
        if(selected==null) selected=request.getHeader("Accept-Language")==null?"zh_CN":request.getLocale().toLanguageTag();
        return Locale.forLanguageTag(normalize(selected).replace('_','-'));
    }
    @Override public void setLocale(HttpServletRequest request,HttpServletResponse response,Locale locale) {
        var cookie=new Cookie("locale",normalize(locale.toLanguageTag()));
        cookie.setPath("/");cookie.setMaxAge(7200);cookie.setAttribute("SameSite","Lax");
        response.addCookie(cookie);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if(request.getParameter("lang")!=null) setLocale(request,response,resolveLocale(request));
                return true;
            }
        }).excludePathPatterns("/api/**","/app/**","/textures/**");
    }
}
