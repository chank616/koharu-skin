package top.twinklestars.koharuskin.account;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ScoreController {
    private final ScoreService scores;
    public ScoreController(ScoreService scores) { this.scores = scores; }
    @GetMapping("/user/score-info")
    public Map<String, Object> info(@AuthenticationPrincipal AccountUser user) { return scores.info(user.uid()); }
    @PostMapping("/user/sign")
    public Map<String, Object> sign(@AuthenticationPrincipal AccountUser user) { return scores.sign(user.uid()); }
}
