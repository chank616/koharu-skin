package top.twinklestars.koharuskin.oauth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.player.PlayerService;
import java.util.List;

@RestController
@RequestMapping("/oauth")
public class OAuthClientsController {
    private final OAuthClients clients;
    private final OAuthScopes scopes;
    private final PlayerService players;
    public OAuthClientsController(OAuthClients clients,OAuthScopes scopes,PlayerService players) {
        this.clients=clients; this.scopes=scopes; this.players=players;
    }

    @ModelAttribute
    public void access(@AuthenticationPrincipal AccountUser user,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response) {
        if(!request.getRequestURI().equals(request.getContextPath()+"/oauth/scopes")) players.requireVerified(user.uid());
        response.setHeader("Cache-Control","no-store");
    }

    @GetMapping("/clients")
    public List<OAuthClient> list(@AuthenticationPrincipal AccountUser user) { return clients.list(user.uid()); }
    @PostMapping("/clients")
    public OAuthClient create(@AuthenticationPrincipal AccountUser user,@Valid @RequestBody ClientRequest input) {
        return clients.create(user.uid(),input.name(),input.redirect(),!Boolean.FALSE.equals(input.confidential()));
    }
    @PutMapping("/clients/{id}")
    public OAuthClient update(@AuthenticationPrincipal AccountUser user,@PathVariable long id,@Valid @RequestBody ClientRequest input) {
        return clients.update(user.uid(),id,input.name(),input.redirect());
    }
    @DeleteMapping("/clients/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AccountUser user,@PathVariable long id) { clients.revoke(user.uid(),id); }
    @GetMapping("/scopes")
    public List<OAuthScopes.Scope> scopes() { return scopes.list(); }

    public record ClientRequest(@NotBlank @Size(max=191) String name,@NotBlank @Size(max=16000) String redirect,Boolean confidential) {}
}
