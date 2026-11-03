package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExtensionTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OfficialExtensions extensions;
    @Autowired AdminSettings settings;
    @Autowired SiteOptions options;
    @Autowired PlayerService players;
    @Autowired PlayerMapper playerMapper;
    final AccountUser admin=new AccountUser(951,"extension951@example.invalid","Admin","unused",2);
    final AccountUser ordinary=new AccountUser(952,"extension952@example.invalid","User","unused",0);

    @BeforeEach void fixture() {
        for(String table:List.of("players","users","options","uuid","textures","user_closet")) jdbc.update("DELETE FROM "+table);
        options.insert("register_with_player_name","true");
        for(int uid=951;uid<=952;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,1000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                uid,"extension"+uid+"@example.invalid","Extension"+uid,uid==951?2:0);
    }

    @Test void extensionRoutesRequireAdminCsrfAndRejectArbitraryPlugins() throws Exception {
        mvc.perform(get("/admin/extensions/data").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/extensions/data").with(user(ordinary))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/extensions").with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/admin/extensions/config").with(user(admin))).andExpect(status().isOk())
                .andExpect(model().attribute("blessing",org.hamcrest.Matchers.hasEntry("route","admin/extensions/config")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"yggdrasil-api\"")));
        mvc.perform(get("/admin/plugins/manage").with(user(admin))).andExpect(redirectedUrl("/admin/extensions"));
        mvc.perform(get("/admin/extensions/data").with(user(admin))).andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[1].name").value("yggdrasil-api"));
        mvc.perform(put("/admin/extensions/yggdrasil-api").with(user(admin)).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/admin/extensions/yggdrasil-api").with(user(admin)).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/admin/extensions/third-party").with(user(admin)).with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(1));
        for(String path:List.of("/admin/plugins/upload","/admin/plugins/wget","/admin/plugins/market"))
            mvc.perform(post(path).with(user(admin)).with(csrf())).andExpect(status().isNotFound());
        assertThat(options.get("plugins_enabled")).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"[\"single-player-limit\",\"legacy-extension\"]", "[{\"name\":\"single-player-limit\",\"version\":\"3.0.4\"},{\"name\":\"legacy-extension\"}]", "{\"single-player-limit\":true,\"legacy-extension\":true}"})
    void togglingSupportsOldFormatsAndRetainsOtherExtensionStates(String stored) throws Exception {
        options.insert("plugins_enabled",stored);
        mvc.perform(put("/admin/extensions/yggdrasil-api").with(user(admin)).with(csrf()).contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(extensions.enabled("yggdrasil-api")).isTrue();
        assertThat(extensions.enabled("single-player-limit")).isTrue();
        assertThat(extensions.enabled("legacy-extension")).isTrue();
        extensions.setEnabled("single-player-limit",false);
        assertThat(extensions.enabled("single-player-limit")).isFalse();
        assertThat(extensions.enabled("legacy-extension")).isTrue();
        assertThat(extensions.enabled("yggdrasil-api")).isTrue();
    }

    @Test void singlePlayerSwitchImmediatelyAppliesWithoutDeletingExistingPlayers() throws Exception {
        players.add(952,"FirstPlayer",false); players.add(952,"SecondPlayer",false);
        extensions.setEnabled("single-player-limit",true);
        assertThat(playerMapper.list(952)).hasSize(2);
        mvc.perform(get("/user").with(user(ordinary))).andExpect(redirectedUrl("/user/player/bind"));
        assertThatThrownBy(()->players.add(952,"ThirdPlayer",false)).isInstanceOf(BusinessException.class);
        extensions.setEnabled("single-player-limit",false);
        mvc.perform(get("/user").with(user(ordinary))).andExpect(status().isOk());
        players.add(952,"ThirdPlayer",false);
        assertThat(playerMapper.list(952)).hasSize(3);
    }

    @Test void domainConfigurationPreservesLegacyJsonAndChangesEnforcement() throws Exception {
        var form=post("/admin/extensions/config").with(user(admin)).with(csrf()).param("option","restricted-email-domains")
                .param("restricted-email-domains.allow"," Example.COM \nexample.com\nallowed.invalid")
                .param("restricted-email-domains.deny","blocked.example.com")
                .param("ygg_private_key","untrusted-overwrite");
        mvc.perform(form).andExpect(redirectedUrl("/admin/extensions/config?saved"));
        assertThat(options.get("restricted-email-domains.allow")).isEqualTo("[\"example.com\",\"allowed.invalid\"]");
        assertThat(options.get("ygg_private_key")).isNull();
        extensions.setEnabled("restricted-email-domains",true);
        extensions.checkEmail("user@EXAMPLE.COM");
        assertThatThrownBy(()->extensions.checkEmail("user@elsewhere.invalid")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->extensions.checkEmail("user@blocked.example.com")).isInstanceOf(BusinessException.class);
        assertThat(settings.values("extensions").get("restricted-email-domains.allow")).isEqualTo("example.com\nallowed.invalid");
        extensions.setEnabled("restricted-email-domains",false);
        extensions.checkEmail("user@elsewhere.invalid");
    }

    @Test void yggSwitchAndSettingsAffectApiWhilePreservingExistingUuidAndKey() throws Exception {
        var old=players.add(952,"ExistingPlayer",false);
        String uuid=playerMapper.uuid(old.name());
        extensions.setEnabled("yggdrasil-api",true);
        mvc.perform(get("/api/yggdrasil")).andExpect(status().isOk());
        String key=options.get("ygg_private_key");
        assertThat(key).contains("PRIVATE KEY");
        var values=settings.values("extensions"); values.put("ygg_uuid_algorithm","v3"); values.put("ygg_private_key","overwrite");
        var request=post("/admin/extensions/config").with(user(admin)).with(csrf()).param("option","yggdrasil-api");
        values.forEach(request::param);
        mvc.perform(request).andExpect(redirectedUrl("/admin/extensions/config?saved"));
        assertThat(options.get("ygg_private_key")).isEqualTo(key);
        assertThat(playerMapper.uuid(old.name())).isEqualTo(uuid);
        var added=players.add(952,"OfflinePlayer",false);
        assertThat(playerMapper.uuid(added.name())).isEqualTo(UUID.nameUUIDFromBytes("OfflinePlayer:OfflinePlayer".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-",""));
        values.put("ygg_token_expire_2","1");
        assertThatThrownBy(()->settings.save("extensions","yggdrasil-api",values)).isInstanceOf(BusinessException.class);
        assertThat(options.get("ygg_token_expire_2")).isEqualTo("604800");
        extensions.setEnabled("yggdrasil-api",false);
        mvc.perform(get("/api/yggdrasil")).andExpect(status().isNotFound());
        assertThat(options.get("ygg_private_key")).isEqualTo(key);
        assertThat(playerMapper.uuid(old.name())).isEqualTo(uuid);
    }
}
