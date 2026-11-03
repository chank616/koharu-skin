package top.twinklestars.koharuskin.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMapper accounts;
    @Autowired ProfileMapper profiles;

    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM players"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM options");
        jdbc.update("DELETE FROM textures"); jdbc.update("DELETE FROM uuid");
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true')");
        for (int uid=301;uid<=302;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?, ?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",uid,"profile"+uid+"@example.invalid","Profile"+uid,new BCryptPasswordEncoder(4).encode("old-password"));
    }

    @Test void nicknameHonorsExtensionAndProfilePageAndDarkModeWork() throws Exception {
        var session=login();
        mvc.perform(get("/user/profile").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"change-password\"")));
        change(session,"nickname","{\"new_nickname\":\"新昵称\"}",0);
        mvc.perform(get("/api/user").session(session)).andExpect(jsonPath("$.nickname").value("新昵称"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.ip").doesNotExist());
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[{\"name\":\"single-player-limit\",\"version\":\"3.0.4\"}]')");
        jdbc.update("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(301,'Profile301',0,0,CURRENT_TIMESTAMP)");
        change(session,"nickname","{\"new_nickname\":\"不可修改\"}",1);
        assertThat(accounts.findById(301).nickname()).isEqualTo("新昵称");
        mvc.perform(put("/user/dark-mode").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(profiles.find(301).isDarkMode()).isTrue();
        mvc.perform(get("/user/profile").session(session)).andExpect(content().string(org.hamcrest.Matchers.containsString("layout-top-nav dark-mode")));
    }

    @Test void passwordRequiresCurrentPasswordAndRevokesOtherSessions() throws Exception {
        var session=login(); var otherSession=login();
        change(session,"password","{\"current_password\":\"incorrect-password\",\"new_password\":\"new-password\"}",1);
        change(session,"password","{\"current_password\":\"old-password\",\"new_password\":\"new-password\"}",0);
        assertThat(session.isInvalid()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("new-password",accounts.findById(301).password())).isTrue();
        mvc.perform(get("/user").session(otherSession).accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test void emailRejectsDuplicatesAndRestrictedDomainsThenClearsVerification() throws Exception {
        var session=login(); var otherSession=login();
        change(session,"email","{\"email\":\"profile302@example.invalid\",\"password\":\"old-password\"}",1);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[{\"name\":\"restricted-email-domains\",\"version\":\"0.3.0\"}]'),('restricted-email-domains.allow','[\"example.invalid\"]')");
        change(session,"email","{\"email\":\"changed@denied.invalid\",\"password\":\"old-password\"}",1);
        change(session,"email","{\"email\":\"changed@example.invalid\",\"password\":\"old-password\"}",0);
        assertThat(accounts.findById(301).email()).isEqualTo("changed@example.invalid");
        assertThat(accounts.verified(301)).isFalse();
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/user").session(otherSession).accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test void deletionRequiresPasswordAndPreservesUploadedTexturesAndUuid() throws Exception {
        jdbc.update("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(301,'FormerPlayer',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('FormerPlayer','123456781234123412341234567890ab')");
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(301,'Retained','steve',?,1,301,true,0,CURRENT_TIMESTAMP)","a".repeat(64));
        var session=login();
        change(session,"delete","{\"password\":\"incorrect-password\"}",1);
        change(session,"delete","{\"password\":\"old-password\"}",0);
        assertThat(accounts.findById(301)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE uid=301",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM textures WHERE uploader=301",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='FormerPlayer'",String.class)).isEqualTo("123456781234123412341234567890ab");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test void administratorCannotDeleteAndBanRevokesSession() throws Exception {
        var session=login();
        jdbc.update("UPDATE users SET permission=1 WHERE uid=301");
        change(session,"delete","{\"password\":\"old-password\"}",1);
        assertThat(accounts.findById(301)).isNotNull();
        jdbc.update("UPDATE users SET permission=-1 WHERE uid=301");
        mvc.perform(get("/user/profile").session(session).accept("application/json")).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"identification\":\"profile301@example.invalid\",\"password\":\"old-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return session;
    }
    private void change(MockHttpSession session,String action,String json,int code) throws Exception {
        mvc.perform(post("/user/profile").param("action",action).session(session).with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
    }
}
