package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class AccountMailController {
    private final AccountMailService mail;
    private final CaptchaService captcha;
    public AccountMailController(AccountMailService mail,CaptchaService captcha) { this.mail = mail; this.captcha=captcha; }

    @PostMapping("/auth/forgot")
    public Map<String, Object> forgot(@Valid @RequestBody Forgot input, HttpServletRequest request) {
        if (!captcha.verify(request.getSession(), input.captcha())) throw new BusinessException("请输入正确的验证码");
        mail.forgot(input.email(), request.getRemoteAddr());
        return Map.of("code", 0, "message", "密码重置邮件已发送，请查收邮箱");
    }

    @PostMapping("/auth/reset/{uid}")
    public Map<String, Object> reset(@PathVariable long uid, @RequestParam long expires, @RequestParam String signature,
                                     @Valid @RequestBody Reset input) {
        mail.reset(uid, expires, signature, input.password());
        return Map.of("code", 0, "message", "密码已重置，请重新登录");
    }

    @PostMapping("/user/email-verification")
    public Map<String, Object> verification(@AuthenticationPrincipal AccountUser user) {
        mail.sendVerification(user.uid());
        return Map.of("code", 0, "message", "验证邮件已发送，请查收邮箱");
    }

    public record Forgot(@NotBlank @Email @Size(max = 100) String email, String captcha) {}
    public record Reset(@NotBlank @Size(min = 8, max = 32) String password) {}
}
