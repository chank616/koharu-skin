package top.twinklestars.koharuskin.yggdrasil;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping(UnionOAuth.ROOT)
public class UnionOAuthController {
    private final UnionOAuth oauth;
    public UnionOAuthController(UnionOAuth oauth) { this.oauth=oauth; }
    @GetMapping({"","/"}) public Map<String,String> publicKey() { return Map.of("signaturePublicKey",oauth.publicKey()); }
    @GetMapping("/grant")
    public ResponseEntity<Void> grant(@AuthenticationPrincipal AccountUser user,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        oauth.requireEnabled();
        if(user.email().isEmpty()) {
            request.getSession().setAttribute("oauth.returnTo",request.getRequestURI()+(request.getQueryString()==null?"":"?"+request.getQueryString()));
            return ResponseEntity.status(302).location(URI.create(request.getContextPath()+"/auth/bind")).build();
        }
        return ResponseEntity.status(302).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").location(URI.create(oauth.grant(user,query))).build();
    }
}
