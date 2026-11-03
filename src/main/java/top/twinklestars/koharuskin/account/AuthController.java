package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
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
    private final RegistrationService registrations;
    private final AccountMailService mail;
    private final CaptchaService captcha;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                          SessionAuthenticationStrategy sessions, JsonRememberMeServices remember, LoginAttempts attempts,
                          RegistrationService registrations, AccountMailService mail,CaptchaService captcha) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.sessions = sessions;
        this.remember = remember;
        this.attempts = attempts;
        this.registrations = registrations;
        this.mail = mail;
        this.captcha=captcha;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest input,
                                     HttpServletRequest request, HttpServletResponse response) {
        String address = request.getRemoteAddr();
        if (attempts.count(address) > 3 && !captcha.verify(request.getSession(), input.captcha())) {
            return Map.of("code", 1, "message", "请输入正确的验证码", "data", Map.of("login_fails", attempts.count(address)));
        }
        try {
            var result = authentication.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(input.identification(), input.password()));
            establishSession(result, request, response);
            request.setAttribute("keep", input.keep());
            remember.loginSuccess(request, response, result);
            attempts.clear(address);
            return Map.of("code", 0, "message", "登录成功", "data", Map.of("redirectTo", top.twinklestars.koharuskin.oauth.OAuthBrowserFilter.afterLogin(request.getSession())));
        } catch (AuthenticationException error) {
            int failures = attempts.failed(address);
            return Map.of("code", 1, "message", "账号、密码错误或账号已被禁用", "data", Map.of("login_fails", failures));
        }
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest input,
                                        HttpServletRequest request, HttpServletResponse response) {
        if (!captcha.verify(request.getSession(), input.captcha())) throw new BusinessException("请输入正确的验证码");
        var user = registrations.register(input.email(), input.password(), input.nickname(), input.player_name(), request.getRemoteAddr());
        establishSession(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()), request, response);
        return Map.of("code", 0, "message", mail.afterRegistration(user.uid()));
    }

    private void establishSession(org.springframework.security.core.Authentication result,
                                  HttpServletRequest request, HttpServletResponse response) {
        sessions.onAuthentication(result, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 100) String email,
                                  @NotBlank @Size(min = 8, max = 32) String password,
                                  String nickname, String player_name, String captcha) {}

    public record LoginRequest(@NotBlank @Size(max = 100) String identification,
                               @NotBlank @Size(min = 6, max = 32) String password, Boolean keep, String captcha) {}
}
