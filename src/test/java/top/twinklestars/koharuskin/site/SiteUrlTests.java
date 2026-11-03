package top.twinklestars.koharuskin.site;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.admin.AdminSettings;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","koharu.public-url="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SiteUrlTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteUrls urls;
    @Autowired SiteOptions options;
    @Autowired AdminSettings settings;
    AccountUser admin=new AccountUser(901,"urls@example.invalid","Urls","unused",2);
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM options");jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(901,'urls@example.invalid','Urls','unused',2,1000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)");
        options.insert("site_url","http://skin.example.invalid/base");
        options.insert("plugins_enabled","[\"yggdrasil-api\"]");
    }
    @AfterEach void cleanup() { jdbc.update("DELETE FROM options"); }

    @Test void fixedAddressControlsPageLinksAssetsFormsAndAuthlibMetadata() throws Exception {
        options.insert("auto_detect_asset_url","false");
        var response=mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(header().string("X-Authlib-Injector-API-Location","http://skin.example.invalid/base/api/yggdrasil/"))
                .andReturn();
        var document=org.jsoup.Jsoup.parse(response.getResponse().getContentAsString());
        assertThat(document.selectFirst("script[src$='/app/app.js']").attr("src")).isEqualTo("http://skin.example.invalid/base/app/app.js");
        assertThat(document.selectFirst("a[href$='/auth/register']").attr("href")).isEqualTo("http://skin.example.invalid/base/auth/register");
        assertThat(document.selectFirst("link[rel=icon]").attr("href")).isEqualTo("http://skin.example.invalid/base/official/site/favicon.ico");
        var home=mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(home).selectFirst("img.home-background").attr("src"))
                .isEqualTo("http://skin.example.invalid/base/official/site/bg.webp");
        assertThat(((Map<?,?>)response.getModelAndView().getModel().get("blessing")).get("base_url")).isEqualTo("http://skin.example.invalid/base");
        var settingsPage=mvc.perform(get("/admin/resource").with(user(admin))).andExpect(status().isOk()).andReturn();
        assertThat(org.jsoup.Jsoup.parse(settingsPage.getResponse().getContentAsString()).selectFirst("form#resources").attr("action"))
                .isEqualTo("http://skin.example.invalid/base/admin/resource");
        mvc.perform(get("/api/yggdrasil")).andExpect(jsonPath("$.meta.links.homepage").value("http://skin.example.invalid/base"))
                .andExpect(jsonPath("$.skinDomains[0]").value("skin.example.invalid"));
    }

    @Test void automaticAddressAndHttpsDoNotRedirectTheIncomingRequest() throws Exception {
        options.insert("auto_detect_asset_url","true");options.insert("force_ssl","true");
        var result=mvc.perform(get("/auth/login").with(request->{ request.setServerName("frontend.example.invalid");request.setServerPort(80);return request; }))
                .andExpect(status().isOk()).andReturn();
        var document=org.jsoup.Jsoup.parse(result.getResponse().getContentAsString());
        assertThat(document.selectFirst("script[src$='/app/app.js']").attr("src")).isEqualTo("https://frontend.example.invalid/app/app.js");
        assertThat(document.selectFirst("a[href$='/auth/register']").attr("href")).isEqualTo("https://frontend.example.invalid/auth/register");
        assertThat(urls.publicUrl()).isEqualTo("https://skin.example.invalid/base");
        options.update("force_ssl","false");
        var relative=mvc.perform(get("/auth/login")).andReturn().getResponse().getContentAsString();
        assertThat(org.jsoup.Jsoup.parse(relative).selectFirst("script[src$='/app/app.js']").attr("src")).isEqualTo("/app/app.js");
    }

    @Test void cdnOnlyChangesStaticFilesAndPublicOverrideKeepsImportedDomainOut() throws Exception {
        options.insert("auto_detect_asset_url","false");options.insert("cdn_address","https://cdn.example.invalid/files");
        var body=mvc.perform(get("/auth/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document=org.jsoup.Jsoup.parse(body);
        assertThat(document.selectFirst("script[src$='/app/app.js']").attr("src")).isEqualTo("https://cdn.example.invalid/files/app/app.js");
        assertThat(document.selectFirst("a[href$='/auth/register']").attr("href")).isEqualTo("http://skin.example.invalid/base/auth/register");
        var local=new SiteUrls(options,"http://127.0.0.1:18080/");
        options.insert("force_ssl","true");
        assertThat(local.publicUrl()).isEqualTo("http://127.0.0.1:18080");
        assertThat(local.overridden()).isTrue();
        assertThat(SiteUrls.webLink(local.publicUrl(),"","https://client.example.invalid/callback?code=example"))
                .isEqualTo("https://client.example.invalid/callback?code=example");
    }

    @Test void invalidSiteAddressDoesNotPartiallySaveAndCopyrightUsesPublicAddress() throws Exception {
        var values=settings.values("resource");
        for(String bad:new String[]{"javascript:alert(1)","//example.invalid","https://user:secret@example.invalid","https://example.invalid?x=1","https://example.invalid/#part"}) {
            values.put("site_url",bad);values.put("cache_expire_time","15");
            assertThatThrownBy(()->settings.save("resource","resources",values)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->new SiteUrls(options,bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(options.get("cache_expire_time")).isNull();
        assertThat(options.get("site_url")).isEqualTo("http://skin.example.invalid/base");
        options.insert("copyright_text","Visit {site_url}");
        mvc.perform(get("/")).andExpect(content().string(org.hamcrest.Matchers.containsString("Visit http://skin.example.invalid/base")));
        assertThat(SiteUrls.webLink("https://skin.example.invalid/base","/base","/base/auth/login?lang=en"))
                .isEqualTo("https://skin.example.invalid/base/auth/login?lang=en");
    }
}
