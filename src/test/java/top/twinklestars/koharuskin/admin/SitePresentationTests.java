package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SitePresentationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AdminSettings settings;
    @Autowired OfficialExtensions extensions;
    final AccountUser admin=new AccountUser(2101,"admin2101@example.invalid","Admin","unused",2);
    final AccountUser member=new AccountUser(2102,"member2102@example.invalid","Member","unused",0);
    @BeforeEach void fixture() {
        for(String table:List.of("notifications","user_closet","players","users","options")) jdbc.update("DELETE FROM "+table);
        for(var account:List.of(admin,member)) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?,'unused',?,1000,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",account.uid(),account.email(),account.nickname(),account.permission());
    }
    @Test void publicHomepageUsesLocalizedLegacyContentAndSwitchesCallToActionForSignedInUser() throws Exception {
        options.insert("site_name","Base title"); options.insert("site_name_zh_CN","<b>测试皮肤站</b>");
        options.insert("site_description_zh_CN","已有中文介绍"); options.insert("home_pic_url","./app/bg.webp");
        var guest=mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("home")).andReturn().getResponse().getContentAsString();
        assertThat(guest).contains("&lt;b&gt;测试皮肤站&lt;/b&gt;","已有中文介绍","/official/site/bg.webp","/app/home.css","/app/home.js","现在注册","id=\"intro\"")
                .doesNotContain("<b>测试皮肤站</b>","/official/hitokoto.js");
        var logged=mvc.perform(get("/").with(user(member))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(logged).contains("用户中心").doesNotContain("现在注册");
        mvc.perform(get("/official/site/bg.webp")).andExpect(status().isOk()).andExpect(content().contentType("image/webp"));
    }
    @Test void adminCanSaveHomepageSettingsAndInvalidStyleDoesNotPartiallySave() throws Exception {
        mvc.perform(get("/admin/customize").with(user(member))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/customize").with(user(admin))).andExpect(status().isOk());
        var values=settings.values("customize"); values.put("hide_intro","true"); values.put("fixed_bg","true"); values.put("transparent_navbar","true");
        values.put("home_pic_url","https://images.example.invalid/background.png"); values.put("copyright_text","<b>{year} {site_name}</b> <a href=\"{site_url}\">首页</a>");
        var request=post("/admin/customize").with(user(admin)).with(csrf()).param("option","homepage");
        values.forEach(request::param);
        mvc.perform(request).andExpect(redirectedUrl("/admin/customize?saved"));
        assertThat(options.get("copyright_text_zh_CN")).isEqualTo(values.get("copyright_text"));
        var page=mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("home-background fixed","navbar-dark transparent","https://images.example.invalid/background.png","without-intro","<b>"+java.time.Year.now()+" Koharu Skin</b>")
                .doesNotContain("id=\"intro\"","{year}","{site_name}");
        values.put("home_pic_url","must-not-save"); values.put("copyright_prefer","7");
        assertThatThrownBy(()->settings.save("customize","homepage",values)).isInstanceOf(top.twinklestars.koharuskin.account.BusinessException.class);
        assertThat(options.get("home_pic_url")).isEqualTo("https://images.example.invalid/background.png");
    }
    @Test void announcementMigratesLocalizedMarkdownAndUpdatesThroughProtectedSettingsForm() throws Exception {
        options.insert("announcement_zh_CN","## 旧公告\n\n**迁移内容** <em>HTML 内容</em>");
        var old=mvc.perform(get("/user").with(user(member))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(old).contains("<h2>旧公告</h2>","<strong>迁移内容</strong>","<em>HTML 内容</em>");
        mvc.perform(post("/admin/options").with(user(admin)).param("option","announ").param("announcement","未授权更改"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/options").with(user(admin)).with(csrf()).param("option","announ").param("announcement","# 新公告\n\n版本 {version}"))
                .andExpect(redirectedUrl("/admin/options?saved"));
        var updated=mvc.perform(get("/user").with(user(member))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(updated).contains("<h1>新公告</h1>").doesNotContain("{version}","旧公告");
        assertThat(settings.values("options").get("announcement")).isEqualTo("# 新公告\n\n版本 {version}");
    }
    @Test void customCodeIsSavedOnlyByAdminAndServedAsSeparateUncachedResources() throws Exception {
        String css=".navbar::after { content: '<中文>'; }";
        String js="window.customMessage = '</script><div>中文</div>';";
        mvc.perform(post("/admin/customize").with(user(member)).with(csrf()).param("option","customJsCss").param("custom_css",css).param("custom_js",js))
                .andExpect(status().isForbidden());
        assertThat(options.get("custom_js")).isNull();
        mvc.perform(post("/admin/customize").with(user(admin)).with(csrf()).param("option","customJsCss").param("custom_css",css).param("custom_js",js)
                .param("remember-key","not-an-option"))
                .andExpect(redirectedUrl("/admin/customize?saved"));
        assertThat(options.get("remember-key")).isNull();
        mvc.perform(get("/official/site/custom.css")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(css));
        mvc.perform(get("/official/site/custom.js")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/javascript"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string(js));
        mvc.perform(head("/official/site/custom.js")).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length",js.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        var page=mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("/official/site/custom.css","/official/site/custom.js").doesNotContain(js,css);
        settings.save("customize","customJsCss",Map.of("custom_js","","custom_css",""));
        var cleared=mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(cleared).doesNotContain("/official/site/custom.css","/official/site/custom.js");
        mvc.perform(get("/official/site/custom.js")).andExpect(content().string(""));
    }

    @Test void metadataPreservesMetaTagsAndEscapesPlainFieldsWithoutInjectingOtherMarkup() throws Exception {
        mvc.perform(post("/admin/options").with(user(admin)).with(csrf()).param("option","meta")
                        .param("meta_keywords","皮肤,披风\"<img>").param("meta_description","中文介绍 & 分享")
                        .param("meta_extras","<meta property='og:title' content='站点 > 标题'><div id='discard-me'>not metadata</div><script>window.injected=1</script><meta name=robots content=noindex>"))
                .andExpect(redirectedUrl("/admin/options?saved"));
        var page=mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        var document=org.jsoup.Jsoup.parse(page);
        assertThat(document.selectFirst("meta[name=keywords]").attr("content")).isEqualTo("皮肤,披风\"<img>");
        assertThat(document.selectFirst("meta[name=description]").attr("content")).isEqualTo("中文介绍 & 分享");
        assertThat(document.selectFirst("meta[property=og:title]").attr("content")).isEqualTo("站点 > 标题");
        assertThat(document.selectFirst("meta[name=robots]").attr("content")).isEqualTo("noindex");
        assertThat(page).doesNotContain("discard-me","window.injected=1","not metadata");
    }

    @Test void navigationThemeAndCdnApplyToPagesAndClearingCdnRestoresLocalAssets() throws Exception {
        mvc.perform(post("/admin/customize").with(user(admin)).with(csrf()).param("option","color").param("navbar_color","warning"))
                .andExpect(redirectedUrl("/admin/customize?saved"));
        for(String path:List.of("/","/user","/admin","/skinlib")) {
            var page=mvc.perform(get(path).with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("navbar-warning navbar-light","name=\"theme-color\" content=\"#ffc107\"");
        }
        assertThatThrownBy(()->settings.save("customize","color",Map.of("navbar_color","unknown"))).isInstanceOf(top.twinklestars.koharuskin.account.BusinessException.class);
        assertThat(options.get("navbar_color")).isEqualTo("warning");
        mvc.perform(post("/admin/resource").with(user(member)).with(csrf()).param("option","resources").param("auto_detect_asset_url","true").param("cache_expire_time","31536000").param("cdn_address","https://cdn.example.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/resource").with(user(admin)).with(csrf()).param("option","resources").param("auto_detect_asset_url","true").param("cache_expire_time","31536000").param("cdn_address"," https://cdn.example.invalid/assets/// "))
                .andExpect(redirectedUrl("/admin/resource?saved"));
        assertThat(options.get("cdn_address")).isEqualTo("https://cdn.example.invalid/assets");
        var cdnPage=mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        for(String file:List.of("app.js","style.css","home.js","home.css")) assertThat(cdnPage).contains("https://cdn.example.invalid/assets/app/"+file);
        assertThat(cdnPage).contains("id=\"cdn-host\"");
        for(String invalid:List.of("//cdn.example.invalid","javascript:alert(1)","https://user:secret@cdn.example.invalid","https://cdn.example.invalid?a=1"))
            assertThatThrownBy(()->settings.save("resource","resources",Map.of("cdn_address",invalid,"cache_expire_time","31536000","auto_detect_asset_url","true"))).isInstanceOf(top.twinklestars.koharuskin.account.BusinessException.class);
        settings.save("resource","resources",Map.of("cdn_address","","cache_expire_time","31536000","auto_detect_asset_url","true"));
        var localPage=mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(localPage).contains("src=\"/app/app.js\"","href=\"/app/style.css\"").doesNotContain("id=\"cdn-host\"","cdn.example.invalid");
    }

    @Test void hitokotoIsOnlyLoadedOnEnabledUserAndAdminPages() throws Exception {
        extensions.setEnabled("hitokoto",true);
        for(String path:List.of("/user","/admin")) {
            var page=mvc.perform(get(path).with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("/official/hitokoto.js");
        }
        for(String path:List.of("/","/auth/login","/skinlib")) {
            var page=mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).doesNotContain("/official/hitokoto.js");
        }
        extensions.setEnabled("hitokoto",false);
        var disabled=mvc.perform(get("/user").with(user(member))).andReturn().getResponse().getContentAsString();
        assertThat(disabled).doesNotContain("/official/hitokoto.js");
    }
}
