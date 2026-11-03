package top.twinklestars.koharuskin.admin;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminSettings settings;
    AccountUser admin=new AccountUser(901,"translation901@example.invalid","User","unused",2);
    AccountUser ordinary=new AccountUser(902,"translation902@example.invalid","User","unused",0);
    @BeforeEach void fixture() {
        for(String table:new String[]{"language_lines","users","options"}) jdbc.update("DELETE FROM "+table);
        for(int uid=901;uid<=902;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",uid,"translation"+uid+"@example.invalid","User",uid==901?2:0);
    }
    @AfterEach void cleanup() { jdbc.update("DELETE FROM language_lines"); }

    @Test void createsOverridesAndDeletionRestoresBundledTextWithoutRestart() throws Exception {
        var original=blessing("zh_CN");
        mvc.perform(post("/admin/i18n").with(user(admin)).with(csrf()).param("group","front-end").param("key","general.noResult").param("text","<script>custom</script>"))
                .andExpect(redirectedUrl("/admin/i18n")).andExpect(flash().attribute("translationAdded",true));
        long id=jdbc.queryForObject("SELECT id FROM language_lines",Long.class);
        assertThat(general(blessing("zh_CN")).get("noResult")).isEqualTo("<script>custom</script>");
        mvc.perform(get("/admin/i18n/list").with(user(admin))).andExpect(jsonPath("$.data[0].text.zh_CN").value("<script>custom</script>"))
                .andExpect(jsonPath("$.last_page").value(1));
        mvc.perform(get("/admin/i18n").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"table\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>custom</script>"))));
        mvc.perform(delete("/admin/i18n/"+id).with(user(admin)).with(csrf())).andExpect(jsonPath("$.code").value(0));
        assertThat(general(blessing("zh_CN")).get("noResult")).isEqualTo(general(original).get("noResult"));
    }

    @Test void editsLegacyJsonWithoutLosingOtherLanguagesAndUsesLocaleCookie() throws Exception {
        jdbc.update("INSERT INTO language_lines(`group`,`key`,text) VALUES('front-end','general.noResult',?)","{\"en\":\"Before\",\"zh_CN\":\"原有中文\",\"ru_RU\":\"Старый\"}");
        long id=jdbc.queryForObject("SELECT id FROM language_lines",Long.class);
        mvc.perform(put("/admin/i18n/"+id).with(user(admin)).with(csrf()).cookie(new Cookie("locale","en"))
                .contentType("application/x-www-form-urlencoded").content("text=After"))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/i18n/list").with(user(admin)))
                .andExpect(jsonPath("$.data[0].text.en").value("After"))
                .andExpect(jsonPath("$.data[0].text.zh_CN").value("原有中文"))
                .andExpect(jsonPath("$.data[0].text.ru_RU").value("Старый"));
        assertThat(general(blessing("en_US")).get("noResult")).isEqualTo("After");
        assertThat(general(blessing("zh_HANS_CN")).get("noResult")).isEqualTo("原有中文");
        mvc.perform(get("/auth/login").param("lang","ru")).andExpect(cookie().value("locale","ru_RU"));
        var result=mvc.perform(get("/auth/login").header("Accept-Language","en-US,en;q=0.8")).andReturn();
        assertThat(((Map<?,?>)result.getModelAndView().getModel().get("blessing")).get("locale")).isEqualTo("en");
        assertThat(blessing("../../secret").get("locale")).isEqualTo("en");
    }

    @Test void serverTemplateOverridesAreEscapedAndLocalizedSiteNameFollowsSelectedLanguage() throws Exception {
        jdbc.update("INSERT INTO language_lines(`group`,`key`,text) VALUES('admin','i18n.add',?)","{\"en\":\"<b>Add a phrase</b>\"}");
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('site_name_zh_CN','中文站'),('site_name_en','English Site')");
        mvc.perform(get("/admin/i18n").with(user(admin)).param("lang","en"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;b&gt;Add a phrase&lt;/b&gt;")));
        assertThat(blessing("en").get("site_name")).isEqualTo("English Site");
        assertThat(blessing("zh_CN").get("site_name")).isEqualTo("中文站");
    }

    @Test void managementRequiresAdminCsrfAndValidFields() throws Exception {
        mvc.perform(get("/admin/i18n/list").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/i18n").with(user(ordinary))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/i18n").with(user(admin))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/i18n").with(user(ordinary)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/i18n").with(user(admin)).with(csrf()).param("group","front-end").param("key","missing"))
                .andExpect(redirectedUrl("/admin/i18n")).andExpect(flash().attributeExists("translationError"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM language_lines",Integer.class)).isZero();
        mvc.perform(put("/admin/i18n/99999").with(user(admin)).with(csrf()).contentType("application/json").content("{\"text\":\"value\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/admin/i18n/99999").with(user(admin)).with(csrf()).contentType("application/json").content("{\"text\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/admin/i18n/99999").with(user(admin)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void savingEnglishSettingsKeepsExistingChineseName() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('site_name_zh_CN','原来的中文站名')");
        var values=settings.values("options");values.put("site_name","English title");
        var request=post("/admin/options").with(user(admin)).with(csrf()).param("option","general").cookie(new Cookie("locale","en"));
        values.forEach(request::param);
        mvc.perform(request).andExpect(redirectedUrl("/admin/options?saved"));
        assertThat(blessing("en").get("site_name")).isEqualTo("English title");
        assertThat(blessing("zh_CN").get("site_name")).isEqualTo("原来的中文站名");
    }

    @Test void paginatesExistingEntriesTenAtATime() throws Exception {
        for(int i=0;i<11;i++) jdbc.update("INSERT INTO language_lines(`group`,`key`,text) VALUES('front-end',?,'{}')","entry"+i);
        mvc.perform(get("/admin/i18n/list").with(user(admin))).andExpect(jsonPath("$.data.length()").value(10)).andExpect(jsonPath("$.total").value(11));
        mvc.perform(get("/admin/i18n/list").param("page","2").with(user(admin))).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].key").value("entry10")).andExpect(jsonPath("$.last_page").value(2));
    }

    @Test void publicPagesUseSelectedLanguageAndCustomTitlesStayEscaped() throws Exception {
        for(var page:Map.of("/auth/login","Log In","/auth/register","Register","/auth/forgot","Forgot Password").entrySet()) {
            var body=mvc.perform(get(page.getKey()).param("lang","en")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var document=org.jsoup.Jsoup.parse(body);
            assertThat(document.title()).startsWith(page.getValue()+" - ");
            assertThat(document.selectFirst("html").attr("lang")).isEqualTo("en");
            assertThat(document.select("nav[aria-label=Language] a")).hasSize(5);
        }
        jdbc.update("INSERT INTO language_lines(`group`,`key`,text) VALUES('auth','login.title',?)","{\"en\":\"<script>Custom login</script>\"}");
        var body=mvc.perform(get("/auth/login").cookie(new Cookie("locale","en"))).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(body).title()).startsWith("<script>Custom login</script> - ");
        assertThat(body).doesNotContain("<script>Custom login</script>");
        mvc.perform(get("/").param("lang","en")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Give your character its own look")));
        var userPage=mvc.perform(get("/user").with(user(admin)).param("lang","en")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(userPage).selectFirst("a[href='/user/player']").text()).isEqualTo("Players");
    }

    @Test void languageLinksPreserveEncodedQueriesAndReplaceExistingLanguage() throws Exception {
        var result=mvc.perform(get(java.net.URI.create("/auth/login?return=%2Fuser%3Ftab%3Dskins&value=a%2Bb&lang=zh_CN"))).andExpect(status().isOk()).andReturn();
        var links=(Map<?,?>)result.getModelAndView().getModel().get("languageLinks");
        assertThat(links.get("en")).isEqualTo("/auth/login?return=%2Fuser%3Ftab%3Dskins&value=a%2Bb&lang=en");
        mvc.perform(get(java.net.URI.create(links.get("en").toString()))).andExpect(status().isOk()).andExpect(cookie().value("locale","en"));
    }

    @Test void authenticatedAccountLanguageOverridesBrowserPreferenceWithoutChangingLegacyValue() throws Exception {
        jdbc.update("UPDATE users SET locale='ru' WHERE uid=901");
        var result=mvc.perform(get("/user").with(user(admin)).cookie(new Cookie("locale","en")).header("Accept-Language","zh-CN"))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Map<?,?>)result.getModelAndView().getModel().get("blessing")).get("locale")).isEqualTo("ru_RU");
        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE uid=901",String.class)).isEqualTo("ru");
    }

    @Test void explicitLanguageSelectionPersistsOnlyForCurrentAccountAndSurvivesNewSession() throws Exception {
        jdbc.update("UPDATE users SET locale='zh_CN' WHERE uid=901");
        jdbc.update("UPDATE users SET locale='ru_RU' WHERE uid=902");
        mvc.perform(get("/user").with(user(admin)).param("lang","en_US")).andExpect(status().isOk()).andExpect(cookie().value("locale","en"));
        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE uid=901",String.class)).isEqualTo("en");
        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE uid=902",String.class)).isEqualTo("ru_RU");
        jdbc.update("UPDATE users SET password=? WHERE uid=901",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("locale-test-password"));
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                        .content("{\"identification\":\"translation901@example.invalid\",\"password\":\"locale-test-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        var result=mvc.perform(get("/user").session(session).cookie(new Cookie("locale","zh_CN"))).andExpect(status().isOk()).andReturn();
        assertThat(((Map<?,?>)result.getModelAndView().getModel().get("blessing")).get("locale")).isEqualTo("en");
        mvc.perform(get("/auth/login").param("lang","zh_TW")).andExpect(cookie().value("locale","zh_TW"));
        assertThat(jdbc.queryForObject("SELECT locale FROM users WHERE uid=901",String.class)).isEqualTo("en");
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> blessing(String locale) throws Exception {
        return (Map<String,Object>)mvc.perform(get("/auth/login").param("lang",locale)).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("blessing");
    }
    private Map<?,?> general(Map<String,Object> blessing) { return (Map<?,?>)((Map<?,?>)blessing.get("i18n")).get("general"); }
}
