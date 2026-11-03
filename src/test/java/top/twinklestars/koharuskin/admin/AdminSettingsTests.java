package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import top.twinklestars.koharuskin.account.*;
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
class AdminSettingsTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminSettings settings;
    @Autowired SiteOptions options;
    @Autowired PlayerService players;
    @Autowired PlayerMapper playerMapper;
    @Autowired ScoreService scores;
    @Autowired RegistrationService registration;
    AccountUser admin=new AccountUser(901,"settings901@example.invalid","Admin","unused",2);
    AccountUser ordinary=new AccountUser(902,"settings902@example.invalid","Ordinary","unused",0);

    @BeforeEach void fixture() {
        for(String table:List.of("players","users","options","uuid","textures","user_closet")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('ygg_private_key','test-private-canary')");
        for(int uid=901;uid<=902;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,1000,'127.0.0.1',CURRENT_TIMESTAMP,?,true)",
                uid,"settings"+uid+"@example.invalid","Settings"+uid,uid==901?2:0,java.time.LocalDateTime.now().minusDays(2));
    }

    @Test void formsRequireAdminAndDoNotExposeUnrelatedSecrets() throws Exception {
        mvc.perform(get("/admin/options").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/options").with(user(ordinary))).andExpect(status().isForbidden());
        for(String page:List.of("options","score")) mvc.perform(get("/admin/"+page).with(user(admin)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-private-canary"))));
        mvc.perform(post("/admin/score").with(user(admin)).param("option","rate")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/score").with(user(ordinary)).with(csrf()).param("option","rate")).andExpect(status().isForbidden());
    }

    @Test void ratesAndRegistrationUseSavedValuesAndUncheckedBoxesBecomeFalse() throws Exception {
        var values=settings.values("score");
        values.put("score_per_player","25"); values.put("user_initial_score","456"); values.remove("return_score");
        values.put("ygg_private_key","overwrite-attempt"); values.put("plugins_enabled","[\"untrusted\"]");
        save("score","rate",values).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/score?saved"));
        assertThat(options.enabled("return_score",true)).isFalse();
        assertThat(options.get("ygg_private_key")).isEqualTo("test-private-canary");
        assertThat(options.get("plugins_enabled")).isNull();
        var player=players.add(902,"CostPlayer",false);
        assertThat(playerMapper.score(902)).isEqualTo(975);
        players.delete(902,player.pid());
        assertThat(playerMapper.score(902)).isEqualTo(975);
        var newUser=registration.register("new-settings@example.invalid","test-password","NewUser","NewPlayer","127.0.0.5");
        assertThat(playerMapper.score(newUser.uid())).isEqualTo(456);
        save("score","rate",values).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM options WHERE option_name='score_per_player'",Integer.class)).isOne();
    }

    @Test void signSettingsAreStoredInLegacyFormatAndAffectRealReward() throws Exception {
        var values=settings.values("score"); values.put("sign_score_from","17"); values.put("sign_score_to","17");
        values.put("sign_after_zero","true");
        save("score","sign",values).andExpect(status().is3xxRedirection());
        assertThat(options.get("sign_score")).isEqualTo("17,17");
        assertThat(options.get("sign_score_from")).isNull();
        assertThat(scores.sign(902).get("code")).isEqualTo(0);
        assertThat(playerMapper.score(902)).isEqualTo(1017);
        assertThat(scores.sign(902).get("code")).isEqualTo(1);
        values.put("sign_score_from","30");
        save("score","sign",values).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("签到奖励范围无效")));
        assertThat(options.get("sign_score")).isEqualTo("17,17");
    }

    @Test void basicSettingsUpdatePageAndNamingAndKeepInvalidSubmissionOutOfDatabase() throws Exception {
        var values=settings.values("options"); values.put("site_name","测试 <b>皮肤站</b>");
        values.put("site_description","新站点介绍"); values.put("player_name_length_min","5");
        values.put("require_verification","true"); values.put("status_code_for_private","404");
        save("options","general",values).andExpect(status().is3xxRedirection());
        mvc.perform(get("/auth/login")).andExpect(content().string(org.hamcrest.Matchers.containsString("测试 &lt;b&gt;皮肤站&lt;/b&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("新站点介绍")));
        assertThat(options.enabled("require_verification",false)).isTrue();
        assertThatThrownBy(()->players.add(902,"Tiny",false)).isInstanceOf(BusinessException.class);
        values.put("site_name","不得保存"); values.put("player_name_length_max","2");
        save("options","general",values).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("下限不大于上限")));
        assertThat(options.get("site_name")).isEqualTo("测试 <b>皮肤站</b>");
    }

    @Test void invalidNumbersPatternsAndGroupsNeverPartiallySave() throws Exception {
        var values=settings.values("options"); values.put("site_name","不得保存"); values.put("max_upload_file_size","9999999999");
        save("options","general",values).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("超出允许范围")));
        assertThat(options.get("site_name")).isNull();
        values.put("max_upload_file_size","2048"); values.put("texture_name_regexp","/[/");
        save("options","general",values).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("正则表达式格式无效")));
        assertThat(options.get("site_name")).isNull();
        save("options","unknown",Map.of()).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("未知配置分组")));
        var score=settings.values("score"); score.put("score_per_storage","-1");
        save("score","rate",score).andExpect(status().isOk());
        assertThat(options.get("score_per_storage")).isNull();
        options.insert("score_per_storage","true");
        assertThat(settings.values("score").get("score_per_storage")).isEqualTo("1");
    }

    @Test void restoreApiCanBeExplicitlyEnabledAndDisabledFromAdminForm() throws Exception {
        var values=settings.values("extensions");
        assertThat(values.get("ygg_restore_api")).isEqualTo("false");
        var request=post("/admin/extensions/config").with(user(admin)).with(csrf()).param("option","yggdrasil-api");
        values.put("ygg_restore_api","true"); values.forEach(request::param);
        mvc.perform(request).andExpect(status().is3xxRedirection());
        assertThat(options.enabled("ygg_restore_api",false)).isTrue();
        values.remove("ygg_restore_api");
        var disable=post("/admin/extensions/config").with(user(admin)).with(csrf()).param("option","yggdrasil-api");
        values.forEach(disable::param); mvc.perform(disable).andExpect(status().is3xxRedirection());
        assertThat(options.enabled("ygg_restore_api",true)).isFalse();
    }

    private ResultActions save(String page,String group,Map<String,String> values) throws Exception {
        var request=post("/admin/"+page).with(user(admin)).with(csrf()).param("option",group);
        values.forEach(request::param);
        return mvc.perform(request);
    }
}
