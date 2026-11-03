package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final JsonRememberMeServices remember;
    private final LoginAttempts attempts;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                          SessionAuthenticationStrategy sessions, JsonRememberMeServices remember, LoginAttempts attempts) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.sessions = sessions;
        this.remember = remember;
        this.attempts = attempts;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest input,
                                     HttpServletRequest request, HttpServletResponse response) {
        String address = request.getRemoteAddr();
        if (attempts.count(address) > 3 && !CaptchaController.verify(request.getSession(), input.captcha())) {
            return Map.of("code", 1, "message", "请输入正确的验证码", "data", Map.of("login_fails", attempts.count(address)));
        }
        try {
            var result = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(input.identification(), input.password()));
            sessions.onAuthentication(result, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            request.setAttribute("keep", input.keep());
            remember.loginSuccess(request, response, result);
            attempts.clear(address);
            return Map.of("code", 0, "message", "登录成功", "data", Map.of("redirectTo", "/user"));
        } catch (AuthenticationException error) {
            int failures = attempts.failed(address);
            return Map.of("code", 1, "message", "账号、密码错误或账号已被禁用", "data", Map.of("login_fails", failures));
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String identification,
                               @NotBlank @Size(min = 6, max = 32) String password, Boolean keep, String captcha) {}
}
