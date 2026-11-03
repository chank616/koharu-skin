package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ProfileController {
    private final ProfileService service;
    private final ProfileMapper profiles;
    private final JsonRememberMeServices remember;

    public ProfileController(ProfileService service, ProfileMapper profiles, JsonRememberMeServices remember) {
        this.service=service; this.profiles=profiles; this.remember=remember;
    }

    @GetMapping("/api/user")
    public ProfileMapper.Profile me(@AuthenticationPrincipal AccountUser user) { return profiles.find(user.uid()); }

    @PostMapping(value="/user/profile", params="action=nickname")
    public Map<String,Object> nickname(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Nickname form) {
        service.nickname(user.uid(), form.new_nickname());
        return success("昵称已更新");
    }

    @PostMapping(value="/user/profile", params="action=password")
    public Map<String,Object> password(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Password form,
                                      HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        service.password(user.uid(), form.current_password(), form.new_password());
        logout(request,response,auth);
        return success("密码已更新，请重新登录");
    }

    @PostMapping(value="/user/profile", params="action=email")
    public Map<String,Object> email(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody EmailChange form,
                                   HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        service.email(user.uid(), form.email(), form.password());
        logout(request,response,auth);
        return success("邮箱已更新，请使用新邮箱登录并重新验证");
    }

    @PostMapping(value="/user/profile", params="action=delete")
    public Map<String,Object> delete(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Delete form,
                                    HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        service.delete(user.uid(), form.password());
        logout(request,response,auth);
        return success("账号已注销");
    }

    @PutMapping("/user/dark-mode") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void darkMode(@AuthenticationPrincipal AccountUser user) { profiles.toggleDarkMode(user.uid()); }

    private void logout(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        remember.logout(request,response,auth);
        new SecurityContextLogoutHandler().logout(request,response,auth);
    }
    private Map<String,Object> success(String message) { return Map.of("code",0,"message",message); }

    public record Nickname(@NotBlank @Size(max=50) String new_nickname) {}
    public record Password(@NotBlank @Size(min=6,max=32) String current_password,
                           @NotBlank @Size(min=8,max=32) String new_password) {}
    public record EmailChange(@NotBlank @Email @Size(max=100) String email,
                              @NotBlank @Size(min=6,max=32) String password) {}
    public record Delete(@NotBlank @Size(min=6,max=32) String password) {}
}
