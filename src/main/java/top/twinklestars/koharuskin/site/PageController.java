package top.twinklestars.koharuskin.site;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.yaml.snakeyaml.Yaml;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.LoginAttempts;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.player.PlayerService;

import java.io.IOException;
import java.util.Map;

@Controller
public class PageController {
    private final LoginAttempts attempts;
    private final Map<String, Object> translations;
    private final SiteOptions options;
    private final PlayerMapper players;
    private final PlayerService playerService;

    public PageController(LoginAttempts attempts, SiteOptions options, PlayerMapper players, PlayerService playerService) throws IOException {
        this.attempts = attempts;
        this.options = options;
        this.players = players;
        this.playerService = playerService;
        try (var input = new ClassPathResource("i18n/zh_CN/front-end.yml").getInputStream()) {
            translations = new Yaml().load(input);
        }
    }

    @GetMapping("/auth/register")
    public String register(Model model, @AuthenticationPrincipal AccountUser user) {
        if (user != null) return "redirect:/user";
        page(model, "auth/register", Map.of("player", options.enabled("register_with_player_name", true),
                "recaptcha", "", "invisible", false));
        return "auth/register";
    }

    @GetMapping("/")
    public String home() { return "redirect:/user"; }

    @GetMapping("/auth/login")
    public String login(Model model, HttpServletRequest request, @AuthenticationPrincipal AccountUser user) {
        if (user != null) return "redirect:/user";
        page(model, "auth/login", Map.of("tooManyFails", attempts.count(request.getRemoteAddr()) > 3,
                "recaptcha", "", "invisible", false));
        return "auth/login";
    }

    @GetMapping("/user")
    public String user(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "user", Map.of());
        model.addAttribute("account", Map.of("uid", user.uid(), "nickname", user.nickname(), "email", user.email()));
        model.addAttribute("rates", Map.of("角色", options.number("score_per_player", 100), "公开材质 / KB", options.number("score_per_storage", 1),
                "私密材质 / KB", options.number("private_score_per_storage", 10), "衣柜收藏", options.number("score_per_closet_item", 0)));
        return "user/index";
    }

    private void page(Model model, String route, Map<String, Object> extra) {
        model.addAttribute("siteName", "Koharu Skin");
        model.addAttribute("blessing", Map.of("base_url", "", "route", route, "site_name", "Koharu Skin",
                "locale", "zh_CN", "version", "0.0.1", "debug", false, "env", "production", "i18n", translations));
        model.addAttribute("extra", extra);
    }

    @GetMapping("/user/player")
    public String players(Model model, @AuthenticationPrincipal AccountUser user) {
        playerService.requireVerified(user.uid());
        page(model, "user/player", Map.of("count", players.list(user.uid()).size(), "score", players.score(user.uid()),
                "cost", options.number("score_per_player", 100), "rule", "命名规则：" + options.text("player_name_rule", "official"),
                "length", "长度：" + options.number("player_name_length_min", 3) + "～" + options.number("player_name_length_max", 16)));
        return "user/players";
    }

    @GetMapping("/user/closet")
    public String closet(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "user/closet", Map.of("unverified", options.enabled("require_verification", false) && !players.verified(user.uid()),
                "rule", "命名规则：" + options.text("player_name_rule", "official"),
                "length", "长度：" + options.number("player_name_length_min", 3) + "～" + options.number("player_name_length_max", 16)));
        return "user/closet";
    }
}
