package top.twinklestars.koharuskin.closet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.site.PageResult;
import java.util.List;
import java.util.Map;

@RestController
public class ClosetController {
    private final ClosetMapper closet;
    private final ClosetService service;
    public ClosetController(ClosetMapper closet, ClosetService service) { this.closet = closet; this.service = service; }

    @GetMapping({"/user/closet/list","/api/closet"})
    public PageResult<ClosetItem> list(@AuthenticationPrincipal AccountUser user,
            @RequestParam(defaultValue = "skin") @Pattern(regexp = "skin|cape") String category,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "6") @Min(1) @Max(100) int perPage) {
        return PageResult.of(closet.list(user.uid(), category, q, perPage, (page - 1) * perPage),
                page, perPage, closet.count(user.uid(), category, q));
    }

    @GetMapping("/user/closet/ids")
    public List<Integer> ids(@AuthenticationPrincipal AccountUser user) { return closet.ids(user.uid()); }

    @PostMapping({"/user/closet","/api/closet"})
    public Map<String, Object> add(@AuthenticationPrincipal AccountUser user, @Valid @RequestBody Add input) {
        service.add(user, input.tid(), input.name());
        return Map.of("code", 0, "message", "已加入衣柜");
    }
    @PutMapping({"/user/closet/{tid}","/api/closet/{tid}"})
    public Map<String, Object> rename(@AuthenticationPrincipal AccountUser user, @PathVariable int tid, @Valid @RequestBody Rename input) {
        service.rename(user.uid(), tid, input.name());
        return Map.of("code", 0, "message", "名称已修改");
    }
    @DeleteMapping({"/user/closet/{tid}","/api/closet/{tid}"})
    public Map<String, Object> remove(@AuthenticationPrincipal AccountUser user, @PathVariable int tid) {
        service.remove(user.uid(), tid);
        return Map.of("code", 0, "message", "已移出衣柜");
    }
    public record Add(@Positive int tid, @NotBlank @Size(max = 255) String name) {}
    public record Rename(@NotBlank @Size(max = 255) String name) {}
}
