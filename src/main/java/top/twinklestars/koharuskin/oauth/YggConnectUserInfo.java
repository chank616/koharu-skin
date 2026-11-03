package top.twinklestars.koharuskin.oauth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.Map;

@RestController
public class YggConnectUserInfo {
    private final YggConnect connect;
    public YggConnectUserInfo(YggConnect connect) { this.connect=connect; }
    @GetMapping("/yggc/userinfo")
    public Map<String,Object> info(@AuthenticationPrincipal AccountUser user,Authentication authentication) {
        if(authentication.getDetails() instanceof JanusTokens.Access token) return connect.userInfo(user,token.scopes(),token.selected());
        return connect.userInfo(user,(String)authentication.getDetails());
    }
}
