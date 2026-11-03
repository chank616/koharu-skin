package top.twinklestars.koharuskin.account;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailBindingTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMapper accounts;
    @Autowired ProfileService profiles;
    @Autowired LoginAttempts attempts;

    @BeforeEach void fixture() {
        for(String table:List.of("notifications","user_closet","uuid","players","textures","users","options")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','false')");
        String password=new BCryptPasswordEncoder(4).encode("legacy-password").replace("$2a$","$2y$");
        for(int uid=2301;uid<=2303;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?, ?,0,321,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,uid==2303?"taken@example.invalid":"","Legacy"+uid,password);
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(2301,'Legacy skin','steve',?,1,2301,true,2,CURRENT_TIMESTAMP)","b".repeat(64));
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(2301,2301,'Legacy2301',2301,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('Legacy2301','123456781234123412341234567890ab')");
        attempts.clear("127.0.0.1");
    }

    @Test void legacyPlayerLoginLeadsToBindingAndRetainsAccountAndSkinRelations() throws Exception {
        var session=login(); String originalPassword=accounts.findById(2301).password();
        mvc.perform(get("/user").session(session)).andExpect(redirectedUrl("/auth/bind"));
        mvc.perform(get("/auth/login").session(session)).andExpect(redirectedUrl("/auth/bind"));
        mvc.perform(get("/auth/bind").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("绑定邮箱"))).andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/skinlib").session(session)).andExpect(status().isOk());
        mvc.perform(post("/user/sign").session(session).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.data.redirectTo").value("/auth/bind"));
        var bound=bind(session,"restored@example.invalid").andExpect(redirectedUrl("/user"))
                .andExpect(cookie().exists("remember-me")).andReturn();
        mvc.perform(get("/api/user").cookie(bound.getResponse().getCookie("remember-me"))).andExpect(jsonPath("$.uid").value(2301));
        mvc.perform(get("/user").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/user").session(session)).andExpect(jsonPath("$.uid").value(2301)).andExpect(jsonPath("$.score").value(321));
        assertThat(accounts.findById(2301).password()).isEqualTo(originalPassword);
        assertThat(accounts.verified(2301)).isTrue();
        assertThat(jdbc.queryForObject("SELECT tid_skin FROM players WHERE pid=2301",Integer.class)).isEqualTo(2301);
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Legacy2301'",String.class)).isEqualTo("123456781234123412341234567890ab");
        mvc.perform(post("/auth/login").with(csrf()).contentType("application/json").content("{\"identification\":\"restored@example.invalid\",\"password\":\"legacy-password\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.redirectTo").value("/user"));
    }

    @Test void bindingRequiresLoginCsrfAndUniqueValidEmailAndCannotReplaceExistingAddress() throws Exception {
        mvc.perform(get("/auth/bind")).andExpect(redirectedUrl("/auth/login"));
        mvc.perform(post("/auth/bind").with(csrf()).param("email","guest@example.invalid")).andExpect(redirectedUrl("/auth/login"));
        var session=login();
        mvc.perform(post("/auth/bind").session(session).param("email","no-csrf@example.invalid")).andExpect(status().isForbidden());
        for(String invalid:List.of("","invalid","taken@example.invalid")) {
            bind(session,invalid).andExpect(status().isOk()).andExpect(content().string(containsString("alert alert-warning")));
            assertThat(accounts.findById(2301).email()).isEmpty();
        }
        mvc.perform(post("/auth/bind").session(session).with(csrf()).param("email","first@example.invalid").param("uid","2302"))
                .andExpect(redirectedUrl("/user"));
        assertThat(accounts.findById(2302).email()).isEmpty();
        bind(session,"replacement@example.invalid").andExpect(redirectedUrl("/user"));
        assertThat(accounts.findById(2301).email()).isEqualTo("first@example.invalid");
        assertThatThrownBy(()->profiles.bindEmail(2301,"replacement@example.invalid")).isInstanceOf(BusinessException.class);
    }

    @Test void bindingKeepsCurrentSessionAndRevokesOtherSessionsAndResumesPendingAuthorization() throws Exception {
        var session=login(); var other=login();
        String authorize="/oauth/authorize?client_id=synthetic&response_type=code&state=retained";
        mvc.perform(get(authorize).session(session)).andExpect(redirectedUrl("/auth/bind"));
        assertThat(session.getAttribute("oauth.returnTo")).isEqualTo(authorize);
        bind(session,"oauth@example.invalid").andExpect(redirectedUrl(authorize));
        assertThat(session.getAttribute("oauth.returnTo")).isNull();
        mvc.perform(get("/user").session(session)).andExpect(status().isOk());
        mvc.perform(get("/user").session(other).accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test void concurrentBindingsCannotClaimTheSameEmailOrOverwriteTheFirstBinding() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var jobs=List.of(2301L,2302L).stream().map(uid->executor.submit(()->{
                start.await();
                try { profiles.bindEmail(uid,"contended@example.invalid"); return true; }
                catch(BusinessException error) { return false; }
            })).toList();
            start.countDown();
            assertThat(List.of(jobs.get(0).get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='contended@example.invalid'",Integer.class)).isOne();
    }

    @Test void emailBindingPrecedesSinglePlayerBinding() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[{\"name\":\"single-player-limit\"}]')");
        jdbc.update("DELETE FROM players");
        var user=accounts.findById(2301);
        mvc.perform(get("/user").with(user(user))).andExpect(redirectedUrl("/auth/bind"));
        mvc.perform(get("/user/player/bind").with(user(user))).andExpect(redirectedUrl("/auth/bind"));
    }

    private MockHttpSession login() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"identification\":\"Legacy2301\",\"password\":\"legacy-password\",\"keep\":true}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.redirectTo").value("/auth/bind"))
                .andExpect(cookie().doesNotExist("remember-me"));
        return session;
    }
    private org.springframework.test.web.servlet.ResultActions bind(MockHttpSession session,String email) throws Exception {
        return mvc.perform(post("/auth/bind").session(session).with(csrf()).param("email",email));
    }
}
