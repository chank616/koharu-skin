package top.twinklestars.koharuskin.extension;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import top.twinklestars.koharuskin.account.AccountUser;

@RestController
@RequestMapping("/user/xiqueer-verification")
public class XiqueerController {
    private final XiqueerVerification verification;
    public XiqueerController(XiqueerVerification verification) { this.verification=verification; }
    @GetMapping("/challenge")
    public XiqueerVerification.Result challenge(@AuthenticationPrincipal AccountUser user,HttpSession session) { return verification.challenge(user,session); }
    @PostMapping("/status")
    public XiqueerVerification.Result status(@AuthenticationPrincipal AccountUser user,HttpSession session) { return verification.status(user,session); }
}
