package top.twinklestars.koharuskin.player;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.List;
import java.util.Map;

@RestController
public class PlayerController {
    private final PlayerService service;
    private final PlayerMapper players;
    public PlayerController(PlayerService service, PlayerMapper players) { this.service = service; this.players = players; }

    @ModelAttribute
    public void verified(@AuthenticationPrincipal AccountUser user, jakarta.servlet.http.HttpServletRequest request) {
        if (!request.getRequestURI().equals(request.getContextPath()+"/user/player/bind")) service.requireVerified(user.uid());
    }

    @GetMapping({"/user/player/list","/api/players"})
    public List<Player> list(@AuthenticationPrincipal AccountUser user) { return players.list(user.uid()); }

    @PostMapping({"/user/player","/api/players"})
    public Map<String, Object> add(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Name input) {
        return success("角色添加成功", service.add(user.uid(), input.name(), false));
    }

    @PostMapping("/user/player/bind")
    public Map<String, Object> bind(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Bind input) {
        return success("角色绑定成功", service.add(user.uid(), input.player(), true));
    }

    @PutMapping({"/user/player/{pid}/name","/api/players/{pid}/name"})
    public Map<String, Object> rename(@AuthenticationPrincipal AccountUser user, @PathVariable long pid,
                                    @Valid @RequestBody Name input) {
        return success("角色名修改成功", service.rename(user.uid(), pid, input.name()));
    }

    @DeleteMapping({"/user/player/{pid}","/api/players/{pid}"})
    public Map<String, Object> delete(@AuthenticationPrincipal AccountUser user, @PathVariable long pid) {
        service.delete(user.uid(), pid);
        return Map.of("code", 0, "message", "角色已删除");
    }

    @PutMapping({"/user/player/{pid}/textures","/api/players/{pid}/textures"})
    public Map<String, Object> set(@AuthenticationPrincipal AccountUser user, @PathVariable long pid,
                                 @Valid @RequestBody Textures input) {
        return success("材质设置成功", service.setTextures(user.uid(), pid, input.skin(), input.cape()));
    }

    @DeleteMapping({"/user/player/{pid}/textures","/api/players/{pid}/textures"})
    public Map<String, Object> clear(@AuthenticationPrincipal AccountUser user, @PathVariable long pid,
                                   @RequestBody(required=false) Clear input,jakarta.servlet.http.HttpServletRequest request) {
        var types=new java.util.HashSet<String>();
        if(input!=null) types.addAll(input.type());
        for(String key:List.of("type","type[]")) {
            String[] values=request.getParameterValues(key);
            if(values!=null) types.addAll(java.util.Arrays.asList(values));
        }
        return success("材质已清空", service.setTextures(user.uid(), pid,
                request.getParameterMap().containsKey("skin") || (input!=null&&input.skin()!=null) || types.contains("skin") ? 0 : null,
                request.getParameterMap().containsKey("cape") || (input!=null&&input.cape()!=null) || types.contains("cape") ? 0 : null));
    }

    private Map<String, Object> success(String message, Player player) { return Map.of("code", 0, "message", message, "data", player); }
    public record Name(@NotBlank String name) {}
    public record Bind(@NotBlank String player) {}
    public record Textures(@PositiveOrZero Integer skin, @PositiveOrZero Integer cape) {}
    public record Clear(String skin,String cape,List<String> type) {
        public Clear { if(type==null) type=List.of(); }
    }
}
