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
        assertThat(guest).contains("&lt;b&gt;测试皮肤站&lt;/b&gt;","已有中文介绍","/official/site/bg.webp","/app/home.css","/app/home.js","立即注册","id=\"intro\"")
                .doesNotContain("<b>测试皮肤站</b>","/official/hitokoto.js");
        var logged=mvc.perform(get("/").with(user(member))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(logged).contains("用户中心").doesNotContain("立即注册");
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
