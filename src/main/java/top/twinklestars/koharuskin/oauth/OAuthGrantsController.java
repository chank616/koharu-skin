package top.twinklestars.koharuskin.oauth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.player.PlayerService;
import java.util.List;

@RestController
@RequestMapping("/oauth/tokens")
public class OAuthGrantsController {
    private final OAuthGrants grants;
    private final PlayerService players;
    public OAuthGrantsController(OAuthGrants grants,PlayerService players) { this.grants=grants; this.players=players; }
    @ModelAttribute
    public void access(@AuthenticationPrincipal AccountUser user,HttpServletResponse response) {
        players.requireVerified(user.uid()); response.setHeader("Cache-Control","no-store");
    }
    @GetMapping
    public List<OAuthGrants.Grant> list(@AuthenticationPrincipal AccountUser user) { return grants.list(user.uid()); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AccountUser user,@PathVariable String id) { grants.revoke(user.uid(),id); }
}
