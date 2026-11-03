package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

public class JsonRememberMeServices extends TokenBasedRememberMeServices {
    public JsonRememberMeServices(String key, UserDetailsService users) {
        super(key, users);
    }

    @Override
    protected boolean rememberMeRequested(HttpServletRequest request, String parameter) {
        return Boolean.TRUE.equals(request.getAttribute("keep"));
    }
}
