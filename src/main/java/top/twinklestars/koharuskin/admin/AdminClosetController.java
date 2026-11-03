package top.twinklestars.koharuskin.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.closet.ClosetItem;
import java.util.*;

@RestController
public class AdminClosetController {
    private final AdminCloset closet;
    public AdminClosetController(AdminCloset closet) { this.closet=closet; }
    @GetMapping("/api/admin/closet/{uid}")
    public List<ClosetItem> list(@AuthenticationPrincipal AccountUser actor,@PathVariable long uid) { return closet.list(actor.uid(),uid); }
    @PostMapping({"/admin/closet/{uid}","/api/admin/closet/{uid}"})
    public Map<String,Object> add(@AuthenticationPrincipal AccountUser actor,@PathVariable long uid,@Valid @RequestBody Item input) { return closet.change(actor.uid(),uid,input.tid(),true); }
    @DeleteMapping({"/admin/closet/{uid}","/api/admin/closet/{uid}"})
    public Map<String,Object> remove(@AuthenticationPrincipal AccountUser actor,@PathVariable long uid,@Valid @RequestBody Item input) { return closet.change(actor.uid(),uid,input.tid(),false); }
    public record Item(@Positive int tid) {}
}
