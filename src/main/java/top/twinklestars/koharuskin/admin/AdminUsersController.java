package top.twinklestars.koharuskin.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.Map;

@RestController
@RequestMapping("/admin/users")
public class AdminUsersController {
    private final AdminUsersMapper users;
    private final AdminUsersService service;
    public AdminUsersController(AdminUsersMapper users, AdminUsersService service) {
        this.users=users; this.service=service;
    }

    @GetMapping("/list")
    public Map<String,Object> list(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="1") int page) {
        int current=Math.max(1,page);
        long total=users.count(q);
        return Map.of("data",users.list(q,(current-1L)*10),"current_page",current,"last_page",Math.max(1,(total+9)/10),
                "per_page",10,"total",total);
    }
    @PutMapping("/{uid}/email")
    public Map<String,Object> email(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid, @Valid @RequestBody Email form) {
        service.email(actor.uid(),uid,form.email()); return success("邮箱已更新");
    }
    @PutMapping("/{uid}/verification")
    public Map<String,Object> verification(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid) {
        service.verification(actor.uid(),uid); return success("验证状态已更新");
    }
    @PutMapping("/{uid}/nickname")
    public Map<String,Object> nickname(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid, @Valid @RequestBody Nickname form) {
        service.nickname(actor.uid(),uid,form.nickname()); return success("昵称已更新");
    }
    @PutMapping("/{uid}/password")
    public Map<String,Object> password(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid, @Valid @RequestBody Password form) {
        service.password(actor.uid(),uid,form.password()); return success("密码已重置");
    }
    @PutMapping("/{uid}/score")
    public Map<String,Object> score(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid, @Valid @RequestBody Score form) {
        service.score(actor.uid(),uid,form.score()); return success("积分已更新");
    }
    @PutMapping("/{uid}/permission")
    public Map<String,Object> permission(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid, @Valid @RequestBody Permission form) {
        service.permission(actor.uid(),uid,form.permission()); return success("权限已更新");
    }
    @DeleteMapping("/{uid}")
    public Map<String,Object> delete(@AuthenticationPrincipal AccountUser actor, @PathVariable long uid) {
        service.delete(actor.uid(),uid); return success("用户已删除");
    }
    @ExceptionHandler(AccessDeniedException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,Object> forbidden(AccessDeniedException error) { return Map.of("code",1,"message",error.getMessage()); }
    private Map<String,Object> success(String message) { return Map.of("code",0,"message",message); }

    public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=100) String email) {}
    public record Nickname(@NotBlank @Size(max=50) String nickname) {}
    public record Password(@NotBlank @Size(min=8,max=32) String password) {}
    public record Score(@NotNull Integer score) {}
    public record Permission(@NotNull @Min(-1) @Max(1) Integer permission) {}
}
