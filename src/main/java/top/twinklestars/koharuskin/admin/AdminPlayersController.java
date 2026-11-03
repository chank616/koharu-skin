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
public class AdminPlayersController {
    private final AdminPlayersMapper players;
    private final AdminPlayersService service;
    public AdminPlayersController(AdminPlayersMapper players, AdminPlayersService service) {
        this.players=players; this.service=service;
    }
    @GetMapping({"/admin/players/list","/api/admin/players"})
    public Map<String,Object> list(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="1") int page) {
        int current=Math.max(1,page);
        long total=players.count(q);
        return Map.of("data",players.list(q,(current-1L)*10),"current_page",current,"last_page",Math.max(1,(total+9)/10),
                "per_page",10,"total",total);
    }
    @PutMapping({"/admin/players/{pid}/name","/api/admin/players/{pid}/name"})
    public Map<String,Object> name(@AuthenticationPrincipal AccountUser actor,@PathVariable long pid,@Valid @RequestBody Name form) {
        service.name(actor.uid(),pid,form.player_name()); return success("角色名已更新");
    }
    @PutMapping({"/admin/players/{pid}/owner","/api/admin/players/{pid}/owner"})
    public Map<String,Object> owner(@AuthenticationPrincipal AccountUser actor,@PathVariable long pid,@Valid @RequestBody Owner form) {
        service.owner(actor.uid(),pid,form.uid()); return success("角色归属已更新");
    }
    @PutMapping({"/admin/players/{pid}/textures","/api/admin/players/{pid}/textures"})
    public Map<String,Object> textures(@AuthenticationPrincipal AccountUser actor,@PathVariable long pid,@Valid @RequestBody Texture form) {
        service.texture(actor.uid(),pid,form.type(),form.tid()); return success("角色材质已更新");
    }
    @DeleteMapping({"/admin/players/{pid}","/api/admin/players/{pid}"})
    public Map<String,Object> delete(@AuthenticationPrincipal AccountUser actor,@PathVariable long pid) {
        service.delete(actor.uid(),pid); return success("角色已删除");
    }
    @ExceptionHandler(AccessDeniedException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,Object> forbidden(AccessDeniedException error) { return Map.of("code",1,"message",error.getMessage()); }
    private Map<String,Object> success(String message) { return Map.of("code",0,"message",message); }

    public record Name(@NotBlank String player_name) {}
    public record Owner(@NotNull @Positive Long uid) {}
    public record Texture(@NotNull @Min(0) Integer tid,@NotNull @Pattern(regexp="skin|cape") String type) {}
}
