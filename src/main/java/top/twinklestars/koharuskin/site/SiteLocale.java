package top.twinklestars.koharuskin.site;

import jakarta.servlet.http.*;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.ProfileMapper;
import java.util.*;

@Component("localeResolver")
public class SiteLocale implements LocaleResolver, WebMvcConfigurer {
    private final ProfileMapper profiles;
    public SiteLocale(ProfileMapper profiles) { this.profiles=profiles; }
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
        var user=account();
        if(selected==null&&user!=null) {
            String saved=profiles.find(user.uid()).locale();
            if(saved!=null&&!saved.isBlank()) selected=saved;
        }
        if(selected==null&&request.getCookies()!=null)
            for(var cookie:request.getCookies()) if(cookie.getName().equals("locale")) { selected=cookie.getValue();break; }
        if(selected==null) selected=request.getHeader("Accept-Language")==null?"zh_CN":request.getLocale().toLanguageTag();
        return Locale.forLanguageTag(normalize(selected).replace('_','-'));
    }
    @Override public void setLocale(HttpServletRequest request,HttpServletResponse response,Locale locale) {
        String selected=normalize(locale.toLanguageTag());
        var cookie=new Cookie("locale",selected);
        cookie.setPath("/");cookie.setMaxAge(7200);cookie.setAttribute("SameSite","Lax");
        response.addCookie(cookie);
        var user=account();
        if(user!=null) profiles.locale(user.uid(),selected);
    }
    private AccountUser account() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication!=null&&authentication.getPrincipal() instanceof AccountUser user?user:null;
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
