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

import java.io.IOException;
import java.util.Map;

@Controller
public class PageController {
    private final LoginAttempts attempts;
    private final Map<String, Object> translations;
    private final SiteOptions options;

    public PageController(LoginAttempts attempts, SiteOptions options) throws IOException {
        this.attempts = attempts;
        this.options = options;
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
        page(model, "account-overview", Map.of());
        model.addAttribute("account", Map.of("uid", user.uid(), "nickname", user.nickname(), "email", user.email()));
        return "user/index";
    }

    private void page(Model model, String route, Map<String, Object> extra) {
        model.addAttribute("siteName", "Koharu Skin");
        model.addAttribute("blessing", Map.of("base_url", "", "route", route, "site_name", "Koharu Skin",
                "locale", "zh_CN", "version", "0.0.1", "debug", false, "env", "production", "i18n", translations));
        model.addAttribute("extra", extra);
    }
}
