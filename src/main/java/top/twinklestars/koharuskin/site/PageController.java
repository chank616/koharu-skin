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
import top.twinklestars.koharuskin.texture.TextureService;
import top.twinklestars.koharuskin.account.AccountMapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import top.twinklestars.koharuskin.account.AccountMailService;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.account.ProfileMapper;
import top.twinklestars.koharuskin.extension.OfficialExtensions;

import java.io.IOException;
import java.util.Map;

@Controller
public class PageController {
    private final LoginAttempts attempts;
    private final Map<String, Object> translations;
    private final SiteOptions options;
    private final PlayerMapper players;
    private final PlayerService playerService;
    private final TextureService textures;
    private final AccountMapper accounts;
    private final AccountMailService mail;
    private final ProfileMapper profiles;
    private final OfficialExtensions extensions;

    public PageController(LoginAttempts attempts, SiteOptions options, PlayerMapper players, PlayerService playerService,
                          TextureService textures, AccountMapper accounts, AccountMailService mail,
                          ProfileMapper profiles, OfficialExtensions extensions) throws IOException {
        this.attempts = attempts;
        this.options = options;
        this.players = players;
        this.playerService = playerService;
        this.textures = textures;
        this.accounts = accounts;
        this.mail = mail;
        this.profiles = profiles;
        this.extensions = extensions;
        try (var input = new ClassPathResource("i18n/zh_CN/front-end.yml").getInputStream()) {
            translations = new Yaml().load(input);
        }
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void appearance(Model model, @AuthenticationPrincipal AccountUser user) {
        model.addAttribute("darkMode", user != null && profiles.find(user.uid()).isDarkMode());
    }

    @GetMapping("/user/profile")
    public String profile(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "user/profile", Map.of());
        model.addAttribute("profile", profiles.find(user.uid()));
        model.addAttribute("singlePlayer", extensions.enabled("single-player-limit"));
        return "user/profile";
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
        page(model, "user", Map.of("unverified", options.enabled("require_verification", false) && !accounts.verified(user.uid())));
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

    @GetMapping("/user/player/bind")
    public String bindPlayer(Model model, @AuthenticationPrincipal AccountUser user) {
        var owned = players.list(user.uid());
        if (!extensions.enabled("single-player-limit") || owned.size() == 1) return "redirect:/user";
        page(model, "user/player/bind", Map.of("players", owned,
                "min", options.number("player_name_length_min",3), "max", options.number("player_name_length_max",16)));
        model.addAttribute("title", "绑定唯一角色");
        return "auth/form";
    }

    @GetMapping("/user/closet")
    public String closet(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "user/closet", Map.of("unverified", options.enabled("require_verification", false) && !players.verified(user.uid()),
                "rule", "命名规则：" + options.text("player_name_rule", "official"),
                "length", "长度：" + options.number("player_name_length_min", 3) + "～" + options.number("player_name_length_max", 16)));
        return "user/closet";
    }

    @GetMapping("/skinlib")
    public String library(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "skinlib", Map.of("currentUid", user == null ? 0 : user.uid()));
        model.addAttribute("section", "library");
        model.addAttribute("title", "皮肤库");
        return "skinlib/index";
    }

    @GetMapping("/skinlib/show/{tid}")
    public String texture(Model model, @PathVariable int tid, @AuthenticationPrincipal AccountUser user) {
        var texture = textures.readable(tid, user);
        var uploader = accounts.findById(texture.uploader());
        page(model, "skinlib/show/" + tid, Map.of("download", options.enabled("allow_downloading_texture", true),
                "currentUid", user == null ? 0 : user.uid(), "admin", user != null && user.permission() >= 1,
                "inCloset", user != null && players.inCloset(user.uid(), tid) > 0, "uploaderExists", uploader != null,
                "nickname", uploader == null ? "已注销用户" : uploader.nickname(), "report", options.number("reporter_score_modification", 0),
                "badges", java.util.List.of()));
        model.addAttribute("section", "show");
        model.addAttribute("title", texture.name());
        return "skinlib/index";
    }

    @GetMapping("/skinlib/upload")
    public String upload(Model model, @AuthenticationPrincipal AccountUser user) {
        playerService.requireVerified(user.uid());
        page(model, "skinlib/upload", Map.of("rule", options.text("texture_name_regexp", "请输入材质名称"),
                "privacyNotice", "私密材质仅自己可在皮肤库中查看", "score", players.score(user.uid()),
                "scorePublic", options.number("score_per_storage", 1), "scorePrivate", options.number("private_score_per_storage", 10),
                "closetItemCost", options.number("score_per_closet_item", 0), "award", options.number("score_award_per_texture", 0),
                "contentPolicy", options.text("content_policy", "")));
        model.addAttribute("section", "upload");
        model.addAttribute("title", "上传材质");
        return "skinlib/index";
    }

    @GetMapping("/auth/forgot")
    public String forgot(Model model) {
        page(model, "auth/forgot", Map.of("recaptcha", "", "invisible", false));
        model.addAttribute("title", "找回密码");
        return "auth/form";
    }

    @GetMapping("/auth/reset/{uid}")
    public String reset(Model model, @PathVariable long uid, @RequestParam long expires, @RequestParam String signature) {
        mail.validate(uid, "reset", expires, signature);
        page(model, "auth/reset/" + uid, Map.of());
        model.addAttribute("title", "重置密码");
        return "auth/form";
    }

    @GetMapping("/auth/verify/{uid}")
    public String verify(Model model, @PathVariable long uid, @RequestParam long expires, @RequestParam String signature) {
        mail.validate(uid, "verify", expires, signature);
        page(model, "auth/verify/" + uid, Map.of());
        model.addAttribute("verificationUid", uid);
        model.addAttribute("expires", expires);
        model.addAttribute("signature", signature);
        return "auth/verify";
    }

    @PostMapping("/auth/verify/{uid}")
    public String verifyEmail(Model model, @PathVariable long uid, @RequestParam long expires, @RequestParam String signature,
                              @RequestParam String email) {
        try { mail.verify(uid, expires, signature, email); }
        catch (BusinessException error) {
            model.addAttribute("errorMessage", error.getMessage());
            return verify(model, uid, expires, signature);
        }
        return "redirect:/user";
    }
}
