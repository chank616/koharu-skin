package top.twinklestars.koharuskin.site;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

import java.util.Map;

@Controller
public class PageController {
    private final LoginAttempts attempts;
    private final SiteTranslations translations;
    private final SiteOptions options;
    private final PlayerMapper players;
    private final PlayerService playerService;
    private final TextureService textures;
    private final AccountMapper accounts;
    private final AccountMailService mail;
    private final ProfileMapper profiles;
    private final OfficialExtensions extensions;
    private final top.twinklestars.koharuskin.admin.AdminSettings settings;
    private final top.twinklestars.koharuskin.report.ReportService reports;
    private final top.twinklestars.koharuskin.notification.NotificationService notifications;
    private final top.twinklestars.koharuskin.admin.AdminDashboard dashboard;
    private final top.twinklestars.koharuskin.extension.XiqueerVerification xiqueer;
    private final top.twinklestars.koharuskin.extension.henan.HenanLogin henan;
    private final top.twinklestars.koharuskin.oauth.OAuthConsentPage oauthConsent;
    private final SitePresentation presentation;
    private final top.twinklestars.koharuskin.account.CaptchaService captcha;
    private final top.twinklestars.koharuskin.account.ProfileService profileService;
    private final org.springframework.security.web.context.SecurityContextRepository contexts;
    private final top.twinklestars.koharuskin.account.JsonRememberMeServices remember;
    private final top.twinklestars.koharuskin.yggdrasil.UnionMember union;
    private final top.twinklestars.koharuskin.yggdrasil.UnionProfiles unionProfiles;
    private final top.twinklestars.koharuskin.yggdrasil.UnionBlacklist unionBlacklist;
    private final top.twinklestars.koharuskin.oauth.YggConnect yggConnect;
    private final top.twinklestars.koharuskin.yggdrasil.YggLogs yggLogs;
    private final SiteUrls urls;

    public PageController(LoginAttempts attempts, SiteOptions options, PlayerMapper players, PlayerService playerService,
                          TextureService textures, AccountMapper accounts, AccountMailService mail,
                          ProfileMapper profiles, OfficialExtensions extensions, top.twinklestars.koharuskin.admin.AdminSettings settings,
                          top.twinklestars.koharuskin.report.ReportService reports,
                          top.twinklestars.koharuskin.notification.NotificationService notifications,
                          top.twinklestars.koharuskin.admin.AdminDashboard dashboard,
                          top.twinklestars.koharuskin.extension.XiqueerVerification xiqueer,
                          top.twinklestars.koharuskin.extension.henan.HenanLogin henan,
                          top.twinklestars.koharuskin.oauth.OAuthConsentPage oauthConsent,SitePresentation presentation,
                          top.twinklestars.koharuskin.account.CaptchaService captcha,
                          top.twinklestars.koharuskin.account.ProfileService profileService,
                          org.springframework.security.web.context.SecurityContextRepository contexts,
                          top.twinklestars.koharuskin.account.JsonRememberMeServices remember,
                          top.twinklestars.koharuskin.yggdrasil.UnionMember union,
                          top.twinklestars.koharuskin.yggdrasil.UnionProfiles unionProfiles,
                          top.twinklestars.koharuskin.yggdrasil.UnionBlacklist unionBlacklist,
                          top.twinklestars.koharuskin.oauth.YggConnect yggConnect,
                          top.twinklestars.koharuskin.yggdrasil.YggLogs yggLogs,SiteTranslations translations,SiteUrls urls) {
        this.attempts = attempts;
        this.options = options;
        this.players = players;
        this.playerService = playerService;
        this.textures = textures;
        this.accounts = accounts;
        this.mail = mail;
        this.profiles = profiles;
        this.extensions = extensions;
        this.settings = settings;
        this.reports = reports;
        this.notifications = notifications;
        this.dashboard = dashboard;
        this.xiqueer = xiqueer;
        this.henan = henan;
        this.oauthConsent=oauthConsent;
        this.presentation=presentation;
        this.captcha=captcha;
        this.profileService=profileService;
        this.contexts=contexts;
        this.remember=remember;
        this.union=union;
        this.unionProfiles=unionProfiles;
        this.unionBlacklist=unionBlacklist;
        this.yggConnect=yggConnect;
        this.yggLogs=yggLogs;
        this.translations=translations;this.urls=urls;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void appearance(Model model, @AuthenticationPrincipal AccountUser user,HttpServletRequest request) {
        model.addAttribute("translations",translations);
        model.addAttribute("siteBase",urls.webBase(request));
        model.addAttribute("publicSiteUrl",urls.publicUrl());
        model.addAttribute("publicSiteUrlOverridden",urls.overridden());
        model.addAttribute("darkMode", user != null && profiles.find(user.uid()).isDarkMode());
        model.addAttribute("notifications",user==null?null:notifications.unreadJson(user.uid()));
        model.addAttribute("xiqueerEnabled",user!=null&&extensions.enabled("xiqueer-verification"));
        model.addAttribute("henanEnabled",extensions.enabled("auth-henan"));
        model.addAttribute("hitokotoEnabled",extensions.enabled("hitokoto"));
        model.addAttribute("unionEnabled",extensions.enabled("yggdrasil-connect"));
        model.addAttribute("favicon",presentation.favicon());
        model.addAttribute("siteAssets",presentation.assets());
        var languageLinks=new java.util.LinkedHashMap<String,String>();
        SiteLocale.LANGUAGES.forEach((locale,label)->languageLinks.put(locale,
                org.springframework.web.util.UriComponentsBuilder.fromUriString(request.getRequestURI()).query(request.getQueryString())
                        .replaceQueryParam("lang",locale).build(true).toUriString()));
        languageLinks.replaceAll((locale,link)->SiteUrls.webLink(urls.webBase(request),request.getContextPath(),link));
        model.addAttribute("languageLinks",languageLinks);
        model.addAttribute("languages",SiteLocale.LANGUAGES);
    }

    @GetMapping({"/union","/union/"})
    public String unionProfiles(Model model,@AuthenticationPrincipal AccountUser user) {
        union.requireEnabled();
        page(model,"union",Map.of());
        model.addAttribute("unionBackends",unionProfiles.backends());
        model.addAttribute("unionNetworkEnabled",union.networkEnabled());
        model.addAttribute("unionProfiles",java.util.List.of());
        if(!union.networkEnabled()) model.addAttribute("unionError","当前环境已关闭联盟网络连接。");
        else try { model.addAttribute("unionProfiles",unionProfiles.profiles(user.uid())); }
        catch(org.springframework.web.server.ResponseStatusException error) { model.addAttribute("unionError",error.getReason()); }
        return "user/union";
    }

    @GetMapping("/admin/i18n")
    public String translationPage(Model model) {
        page(model,"admin/i18n",Map.of());
        model.addAttribute("title",translations.text("general.i18n"));
        model.addAttribute("languages",SiteLocale.LANGUAGES);
        if(!model.containsAttribute("translationForm")) model.addAttribute("translationForm",new top.twinklestars.koharuskin.admin.AdminTranslationsController.Form("","",""));
        return "admin/i18n";
    }

    @GetMapping("/admin/yggdrasil-log")
    public String yggLogs(Model model,@RequestParam(defaultValue="1") int page) {
        page(model,"admin/yggdrasil-log",Map.of());
        model.addAttribute("logs",yggLogs.page(page));
        model.addAttribute("recording",yggLogs.enabled());
        return "admin/yggdrasil-log";
    }

    @GetMapping("/admin/union/view/blacklist")
    public String unionBlacklist(Model model,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page) {
        union.requireEnabled();
        page(model,"admin/union/view/blacklist",Map.of());
        model.addAttribute("query",q); model.addAttribute("unionNetworkEnabled",union.networkEnabled());
        if(!model.containsAttribute("blacklistForm")) model.addAttribute("blacklistForm",new top.twinklestars.koharuskin.yggdrasil.UnionBlacklistController.Create("",""));
        try { model.addAttribute("blacklist",unionBlacklist.page(q,page)); }
        catch(org.springframework.web.server.ResponseStatusException error) { model.addAttribute("listError",error.getReason()); }
        return "admin/union-blacklist";
    }

    @GetMapping("/user/oauth/manage")
    public String oauth(Model model,@AuthenticationPrincipal AccountUser user) {
        playerService.requireVerified(user.uid());
        page(model,"user/oauth/manage",Map.of());
        return "user/oauth";
    }

    @GetMapping("/yggc/callback")
    public String yggCallback(Model model,@AuthenticationPrincipal AccountUser user,@RequestParam String state,
                              @RequestParam(required=false) String code,@RequestParam(required=false) String error,
                              jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); response.setHeader("Referrer-Policy","no-referrer");
        if(error!=null) return "redirect:"+yggConnect.redirect(state,"error",error);
        if(code==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        var callback=yggConnect.callback(user,code,state);
        if(callback.redirect()!=null) return "redirect:"+callback.redirect();
        page(model,"yggc/callback",Map.of()); model.addAttribute("selection",callback.selection()); return "auth/ygg-select";
    }
    @PostMapping("/yggc/callback")
    public String yggSelect(@AuthenticationPrincipal AccountUser user,@RequestParam("code_id") String codeId,
                            @RequestParam String state,@RequestParam String selectedProfile) {
        return "redirect:"+yggConnect.select(user,codeId,state,selectedProfile);
    }
    @GetMapping("/yggc/cancel")
    public String yggCancelPage(Model model,@AuthenticationPrincipal AccountUser user,@RequestParam("code_id") String codeId,@RequestParam String state) {
        page(model,"yggc/cancel",Map.of()); model.addAttribute("selection",yggConnect.show(user,codeId,state)); return "auth/ygg-select";
    }
    @PostMapping("/yggc/cancel")
    public String yggCancel(@AuthenticationPrincipal AccountUser user,@RequestParam("code_id") String codeId,@RequestParam String state) {
        return "redirect:"+yggConnect.cancel(user,codeId,state);
    }

    @GetMapping("/oauth/consent")
    public String consent(Model model,@AuthenticationPrincipal AccountUser user,@RequestParam("client_id") String clientId,@RequestParam String state) {
        playerService.requireVerified(user.uid());
        page(model,"oauth/consent",Map.of()); model.addAttribute("consent",oauthConsent.show(user,clientId,state));
        return "auth/oauth-consent";
    }
    @PostMapping("/oauth/deny")
    public String denyConsent(@AuthenticationPrincipal AccountUser user,@RequestParam String state) {
        return "redirect:"+oauthConsent.deny(user,state);
    }

    @GetMapping("/user/profile")
    public String profile(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model, "user/profile", Map.of());
        model.addAttribute("profile", profiles.find(user.uid()));
        model.addAttribute("singlePlayer", extensions.enabled("single-player-limit"));
        model.addAttribute("emailLocked",extensions.enabled("auth-henan"));
        return "user/profile";
    }

    @GetMapping("/user/xiqueer-verification")
    public String xiqueer(Model model,@AuthenticationPrincipal AccountUser user,jakarta.servlet.http.HttpSession session) {
        model.addAttribute("verification",xiqueer.page(user,session));
        page(model,"user/xiqueer-verification",Map.of());
        return "user/xiqueer";
    }
    @GetMapping({"/hegaolian","/user/hegaolian"})
    public String legacyXiqueer() { xiqueer.requireEnabled(); return "redirect:/user/xiqueer-verification"; }

    @GetMapping("/admin/users")
    public String adminUsers(Model model, @AuthenticationPrincipal AccountUser user) {
        page(model,"admin/users",Map.of("currentUser",profiles.find(user.uid())));
        model.addAttribute("title","用户管理");
        return "admin/management";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        page(model,"admin",Map.of());
        model.addAttribute("sum",dashboard.totals());
        if(!model.containsAttribute("notificationForm")) model.addAttribute("notificationForm",
                new top.twinklestars.koharuskin.notification.NotificationController.Send("all",null,"","",""));
        return "admin/index";
    }

    @GetMapping("/admin/status")
    public String adminStatus(Model model) throws java.sql.SQLException {
        page(model,"admin/status",Map.of());
        model.addAttribute("detail",dashboard.status());
        model.addAttribute("enabledExtensions",extensions.list().stream().filter(OfficialExtensions.Extension::enabled).toList());
        return "admin/status";
    }

    @GetMapping("/admin/players")
    public String adminPlayers(Model model) {
        page(model,"admin/players",Map.of());
        model.addAttribute("title","角色管理");
        return "admin/management";
    }

    @GetMapping("/admin/{section:options|score|customize|resource}")
    public String settings(Model model,@PathVariable String section) {
        page(model,"admin/"+(section.equals("extensions")?"extensions/config":section),Map.of());
        model.addAttribute("title",switch(section){case "options"->"站点设置";case "score"->"积分设置";case "customize"->"外观设置";case "resource"->"资源设置";default->"官方扩展配置";});
        model.addAttribute("section",section.equals("extensions")?"extensions/config":section);
        model.addAttribute("groups",settings.groups(section));
        model.addAttribute("values",settings.values(section));
        model.addAttribute("navbarColors",new java.util.TreeMap<>(SitePresentation.COLORS));
        model.addAttribute("unionNetworkEnabled",union.networkEnabled());
        model.addAttribute("unionEnabled",extensions.enabled("yggdrasil-connect"));
        return "admin/settings";
    }

    @PostMapping("/admin/union/member/{operation:updatelist|updateprivatekey|sync|diagnose}")
    public String unionOperation(@PathVariable String operation,org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            switch(operation) {
                case "updatelist" -> union.updateList();
                case "updateprivatekey" -> union.updatePrivateKey();
                case "sync" -> union.sync();
                case "diagnose" -> union.request("POST","/diagnose",Map.of());
            }
            flash.addFlashAttribute("unionMessage","联盟操作已完成");
        } catch(org.springframework.web.server.ResponseStatusException error) { flash.addFlashAttribute("error",error.getReason()); }
        return "redirect:/admin/extensions/config#union";
    }

    @PostMapping("/admin/{section:options|score|customize|resource}")
    public String saveSettings(Model model,@PathVariable String section,@RequestParam String option,@RequestParam Map<String,String> values) {
        try { settings.save(section,option,values); }
        catch(BusinessException error) {
            model.addAttribute("error",error.getMessage());
            String view=settings(model,section);
            var entered=new java.util.LinkedHashMap<>(settings.values(section));
            for(var group:settings.groups(section)) if(group.key().equals(option)) for(var field:group.fields())
                entered.put(field.key(),field.type().equals("password")?"":values.getOrDefault(field.key(),field.type().equals("checkbox")?"false":""));
            model.addAttribute("values",entered);
            return view;
        }
        return "redirect:/admin/"+(section.equals("extensions")?"extensions/config":section)+"?saved";
    }

    @GetMapping("/admin/extensions")
    public String extensionList(Model model) {
        page(model,"admin/extensions",Map.of()); model.addAttribute("title","官方扩展");
        return "admin/management";
    }
    @GetMapping("/admin/reports")
    public String adminReports(Model model) {
        page(model,"admin/reports",Map.of()); model.addAttribute("title","举报审核");
        return "admin/management";
    }
    @GetMapping("/user/reports")
    public String myReports(Model model,@AuthenticationPrincipal AccountUser user,@RequestParam(defaultValue="1") int page) {
        page(model,"user/reports",Map.of());
        model.addAttribute("reports",reports.mine(user.uid(),page));
        model.addAttribute("currentPage",Math.max(1,page)); model.addAttribute("totalPages",reports.minePages(user.uid()));
        return "user/reports";
    }
    @GetMapping("/admin/extensions/config")
    public String extensionConfig(Model model) { return settings(model,"extensions"); }
    @PostMapping("/admin/extensions/config")
    public String saveExtensionConfig(Model model,@RequestParam String option,@RequestParam Map<String,String> values) {
        return saveSettings(model,"extensions",option,values);
    }
    @GetMapping("/admin/plugins/manage")
    public String legacyExtensions() { return "redirect:/admin/extensions"; }

    @GetMapping("/auth/register")
    public String register(Model model, @AuthenticationPrincipal AccountUser user) {
        if (user != null) return "redirect:/user";
        page(model, "auth/register", Map.of("player", options.enabled("register_with_player_name", true),
                "recaptcha", captcha.siteKey(), "invisible", options.enabled("recaptcha_invisible",false)));
        return "auth/register";
    }

    @GetMapping("/auth/{operation:login|register}/henan")
    public String henan(Model model,@PathVariable String operation,@AuthenticationPrincipal AccountUser user) {
        henan.requireEnabled(); if(user!=null) return "redirect:/user";
        page(model,"auth/"+operation+"/henan",Map.of()); model.addAttribute("operation",operation);
        model.addAttribute("schools",top.twinklestars.koharuskin.extension.henan.HenanLogin.SCHOOLS);
        if(!model.containsAttribute("henanForm")) model.addAttribute("henanForm",Map.of("school","","identification","","player_name",""));
        return "auth/henan";
    }
    @GetMapping("/auth/mfa/henan/{token:[a-f0-9]{64}}")
    public String henanMfa(Model model,@PathVariable String token,@AuthenticationPrincipal AccountUser user,jakarta.servlet.http.HttpSession session,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        henan.requireEnabled(); if(user!=null) return "redirect:/user";
        var pending=henan.pending(session,token);
        if(pending==null) { flash.addFlashAttribute("henanError","安全验证已过期，请重新登录"); return "redirect:/auth/login/henan"; }
        page(model,"auth/mfa/henan",Map.of()); model.addAttribute("token",token);
        model.addAttribute("destination",pending.destination()); model.addAttribute("sent",pending.sentAt()!=null);
        return "auth/henan-mfa";
    }

    @GetMapping("/")
    public String home(Model model,@AuthenticationPrincipal AccountUser user) {
        var home=presentation.home();
        page(model,"/",Map.of("transparent_navbar",home.transparent()));
        model.addAttribute("home",home); model.addAttribute("loggedIn",user!=null);
        return "home";
    }

    @GetMapping("/auth/login")
    public String login(Model model, HttpServletRequest request, @AuthenticationPrincipal AccountUser user) {
        if (user != null && user.email().isEmpty()) return "redirect:/auth/bind";
        if (user != null) return "redirect:"+top.twinklestars.koharuskin.oauth.OAuthBrowserFilter.afterLogin(request.getSession());
        page(model, "auth/login", Map.of("tooManyFails", attempts.count(request.getRemoteAddr()) > 3,
                "recaptcha", captcha.siteKey(), "invisible", options.enabled("recaptcha_invisible",false)));
        return "auth/login";
    }

    @GetMapping("/user")
    public String user(Model model, @AuthenticationPrincipal AccountUser user) {
        if ((extensions.enabled("yggdrasil-api")||extensions.enabled("yggdrasil-connect")) && options.enabled("ygg_show_config_section",true)) {
            model.addAttribute("yggUrl",urls.publicUrl()+"/api/yggdrasil/");
        }
        model.addAttribute("yggPasswordLogin",!options.enabled("ygg_disable_authserver",false));
        page(model, "user", Map.of("unverified", options.enabled("require_verification", false) && !accounts.verified(user.uid())));
        model.addAttribute("account", Map.of("uid", user.uid(), "nickname", user.nickname(), "email", user.email()));
        model.addAttribute("admin", user.permission()>=1);
        model.addAttribute("announcement",presentation.announcement());
        model.addAttribute("rates", Map.of("角色", options.number("score_per_player", 100), "公开材质 / KB", options.number("score_per_storage", 1),
                "私密材质 / KB", options.number("private_score_per_storage", 10), "衣柜收藏", options.number("score_per_closet_item", 0)));
        return "user/index";
    }

    private void page(Model model, String route, Map<String, Object> extra) {
        String siteName=options.localized("site_name","Koharu Skin");
        model.addAttribute("siteName", siteName);
        model.addAttribute("siteDescription",options.localized("site_description",""));
        model.addAttribute("blessing", Map.of("base_url", model.getAttribute("siteBase"), "route", route, "site_name", siteName,
                "locale", SiteLocale.current(), "version", "0.0.1", "debug", false, "env", "production", "i18n", translations.group("front-end")));
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
        var texture = textures.view(tid, user);
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

    @GetMapping("/auth/bind")
    public String bindEmail(Model model,@AuthenticationPrincipal AccountUser user) {
        if (!user.email().isEmpty()) return "redirect:/user";
        page(model,"auth/bind",Map.of());
        return "auth/bind";
    }

    @PostMapping("/auth/bind")
    public String bindEmail(Model model,@AuthenticationPrincipal AccountUser user,
                            @jakarta.validation.Valid @org.springframework.web.bind.annotation.ModelAttribute("binding") EmailBindingForm form,
                            org.springframework.validation.BindingResult errors,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        if (!user.email().isEmpty()) return "redirect:/user";
        if (errors.hasErrors()) {
            model.addAttribute("errorMessage",errors.getAllErrors().getFirst().getDefaultMessage());
            return bindEmail(model,user);
        }
        try { profileService.bindEmail(user.uid(),form.email()); }
        catch (BusinessException error) {
            model.addAttribute("errorMessage",error.getMessage());
            return bindEmail(model,user);
        }
        var current=accounts.findById(user.uid());
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(current,null,current.getAuthorities()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        request.setAttribute("keep",Boolean.TRUE.equals(request.getSession().getAttribute("emailBinding.keep")));
        request.getSession().removeAttribute("emailBinding.keep");
        remember.loginSuccess(request,response,context.getAuthentication());
        return "redirect:"+top.twinklestars.koharuskin.oauth.OAuthBrowserFilter.afterLogin(request.getSession());
    }

    public record EmailBindingForm(@jakarta.validation.constraints.NotBlank(message="请输入邮箱地址")
                                   @jakarta.validation.constraints.Email(message="邮箱格式不正确")
                                   @jakarta.validation.constraints.Size(max=100,message="邮箱地址不能超过 100 个字符") String email) {}

    @GetMapping("/auth/forgot")
    public String forgot(Model model) {
        page(model, "auth/forgot", Map.of("recaptcha", captcha.siteKey(), "invisible", options.enabled("recaptcha_invisible",false)));
        model.addAttribute("title", translations.text("auth.forgot.title"));
        return "auth/form";
    }

    @GetMapping("/auth/reset/{uid}")
    public String reset(Model model, @PathVariable long uid, @RequestParam long expires, @RequestParam String signature) {
        mail.validate(uid, "reset", expires, signature);
        page(model, "auth/reset/" + uid, Map.of());
        model.addAttribute("title", translations.text("auth.reset.title"));
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
