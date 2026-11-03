package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountLoginTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginAttempts attempts;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM players");
        jdbc.update("DELETE FROM users");
        attempts.clear("127.0.0.1");
        var encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2Y, 4);
        jdbc.update("INSERT INTO users (uid,email,nickname,password,permission,score,ip,register_at,last_sign_at) VALUES (1, 'tester@example.invalid', '测试用户', ?, 0,1000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", encoder.encode("test-password"));
        jdbc.update("INSERT INTO players (pid,uid,name,last_modified) VALUES (1, 1, 'TestPlayer',CURRENT_TIMESTAMP)");
    }

    @Test
    void acceptsLegacyBcryptAndMaintainsSessionUntilLogout() throws Exception {
        var session = new MockHttpSession();
        String originalId = session.getId();
        login("tester@example.invalid", "test-password", false, session)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.redirectTo").value("/user"));
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get("/user").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("测试用户")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("$2y$"))));
        mvc.perform(post("/auth/logout").session(session).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/user").accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test
    void alsoSupportsPlayerNameAndRememberMeCookie() throws Exception {
        MvcResult result = login("TestPlayer", "test-password", true, new MockHttpSession())
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        Cookie cookie = result.getResponse().getCookie("remember-me");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        mvc.perform(get("/user").cookie(cookie)).andExpect(status().isOk());
        jdbc.update("UPDATE users SET permission = -1 WHERE uid = 1");
        mvc.perform(get("/user").cookie(cookie).accept("application/json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongPasswordMissingAccountAndBannedAccount() throws Exception {
        login("tester@example.invalid", "wrong-password", false, new MockHttpSession())
                .andExpect(jsonPath("$.code").value(1));
        login("missing@example.invalid", "test-password", false, new MockHttpSession())
                .andExpect(jsonPath("$.code").value(1));
        jdbc.update("UPDATE users SET permission = -1 WHERE uid = 1");
        login("tester@example.invalid", "test-password", false, new MockHttpSession())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void enforcesCaptchaAfterFourFailedAttemptsAndConsumesItOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("tester@example.invalid", "wrong-password", false, new MockHttpSession());
        }
        var session = new MockHttpSession();
        login("tester@example.invalid", "test-password", false, session)
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请输入正确的验证码"));
        mvc.perform(get("/auth/captcha").session(session)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        String captcha = (String) session.getAttribute("captcha");
        assertThat(captcha).hasSize(5);
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                        .content("{\"identification\":\"tester@example.invalid\",\"password\":\"test-password\",\"captcha\":\"" + captcha + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(session.getAttribute("captcha")).isNull();
        assertThat(attempts.count("127.0.0.1")).isZero();
    }

    @Test
    void enforcesCsrfAndReturnsFrontendCompatibleValidation() throws Exception {
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"identification\":\"tester@example.invalid\",\"password\":\"test-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.identification").isArray());
        mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("csrf-token")));
    }

    @Test
    void resumesTheRequestedPageWithItsQueryOnlyAfterSuccessfulLogin() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(get(java.net.URI.create("/user/closet?q=Blue%20Skin&page=2")).session(session).accept("text/html"))
                .andExpect(redirectedUrl("/auth/login"));
        login("tester@example.invalid","wrong-password",false,session).andExpect(jsonPath("$.code").value(1));
        // 随后的接口请求和浏览器图标请求不能覆盖待返回的页面。
        mvc.perform(get("/user/score-info").session(session).accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/favicon.ico").session(session)).andExpect(redirectedUrl("/auth/login"));
        login("tester@example.invalid","test-password",false,session)
                .andExpect(jsonPath("$.data.redirectTo").value("/user/closet?q=Blue%20Skin&page=2"));
        assertThat(session.getAttribute("login.returnTo")).isNull();
        assertThat(top.twinklestars.koharuskin.oauth.OAuthBrowserFilter.afterLogin(session)).isEqualTo("/user");
    }

    @Test
    void oauthDestinationTakesPriorityAndClearsTheOrdinaryPageDestination() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(get("/skinlib/upload").session(session)).andExpect(redirectedUrl("/auth/login"));
        session.setAttribute("oauth.returnTo","/oauth/authorize?client_id=example&state=unchanged");
        login("tester@example.invalid","test-password",false,session)
                .andExpect(jsonPath("$.data.redirectTo").value("/oauth/authorize?client_id=example&state=unchanged"));
        assertThat(session.getAttribute("login.returnTo")).isNull();
        assertThat(session.getAttribute("oauth.returnTo")).isNull();
    }

    @Test
    void returnPathUsesTheApplicationContextAndDoesNotTrustTheHostHeader() throws Exception {
        var session=new MockHttpSession();
        mvc.perform(get("/skin/user/closet").contextPath("/skin").session(session).header("Host","untrusted.example.invalid"))
                .andExpect(redirectedUrl("/skin/auth/login"));
        login("tester@example.invalid","test-password",false,session)
                .andExpect(jsonPath("$.data.redirectTo").value("/skin/user/closet"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String identity, String password, boolean keep,
                                                                    MockHttpSession session) throws Exception {
        return mvc.perform(post("/auth/login").session(session).with(request -> {
                    request.setRequestedSessionId(session.getId());
                    return request;
                }).with(csrf()).contentType("application/json")
                .content("{\"identification\":\"" + identity + "\",\"password\":\"" + password + "\",\"keep\":" + keep + "}"));
    }
}
