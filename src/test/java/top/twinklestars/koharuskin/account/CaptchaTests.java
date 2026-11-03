package top.twinklestars.koharuskin.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.admin.AdminSettings;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaptchaTests {
    static HttpServer upstream;
    static final AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);
    static final AtomicReference<String> reply=new AtomicReference<>(),body=new AtomicReference<>();
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry registry) throws IOException {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/verify",exchange->{
            calls.incrementAndGet(); body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); upstream.start();
        registry.add("koharu.recaptcha-verify-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/verify");
    }
    @AfterAll static void close(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AdminSettings settings;
    @Autowired CaptchaService captcha;
    @Autowired AccountMapper accounts;
    @Autowired LoginAttempts attempts;
    @Autowired JsonMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @MockitoBean AccountMailService mail;
    @BeforeEach void fixture() {
        for(String table:List.of("notifications","user_closet","uuid","players","users","options"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(2201,'captcha@example.invalid','Captcha',?,0,1000,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",passwords.encode("test-password"));
        options.insert("register_with_player_name","false"); options.insert("recaptcha_sitekey","synthetic-site-key"); options.insert("recaptcha_secretkey","synthetic+secret&key");
        calls.set(0); status.set(200); reply.set("{\"success\":true}"); body.set(""); attempts.clear("127.0.0.1");
        when(mail.afterRegistration(anyLong())).thenReturn("注册成功");
    }
    @Test void publicPagesExposeOnlySiteKeyAndAdminCanRetainRotateOrDisableTheSecret() throws Exception {
        options.insert("recaptcha_invisible","true");
        for(String path:List.of("/auth/login","/auth/register","/auth/forgot")) {
            var page=mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("synthetic-site-key","www.recaptcha.net/recaptcha/api.js","\"invisible\":true").doesNotContain("synthetic+secret&key");
        }
        jdbc.update("UPDATE users SET permission=2 WHERE uid=2201"); var admin=accounts.findById(2201);
        var form=mvc.perform(get("/admin/options").with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).doesNotContain("synthetic+secret&amp;key","synthetic+secret&key");
        settings.save("options","recaptcha",Map.of("recaptcha_sitekey","synthetic-site-key","recaptcha_secretkey","","recaptcha_invisible","true"));
        assertThat(options.get("recaptcha_secretkey")).isEqualTo("synthetic+secret&key");
        settings.save("options","recaptcha",Map.of("recaptcha_sitekey","rotated-site","recaptcha_secretkey","rotated-secret"));
        assertThat(options.get("recaptcha_secretkey")).isEqualTo("rotated-secret");
        settings.save("options","recaptcha",Map.of("recaptcha_sitekey",""));
        assertThat(options.get("recaptcha_secretkey")).isEmpty(); assertThat(captcha.siteKey()).isEmpty();
        assertThatThrownBy(()->settings.save("options","recaptcha",Map.of("recaptcha_sitekey","without-secret"))).isInstanceOf(BusinessException.class);
        assertThat(options.get("recaptcha_sitekey")).isEmpty();
        var disabled=mvc.perform(get("/auth/register")).andReturn().getResponse().getContentAsString();
        assertThat(disabled).doesNotContain("www.recaptcha.net/recaptcha/api.js");
    }
    @Test void loginOnlyVerifiesAfterFourFailuresAndCannotUseImageCaptchaToBypassRecaptcha() throws Exception {
        login(null,new MockHttpSession()).andExpect(jsonPath("$.code").value(0)); assertThat(calls.get()).isZero();
        for(int i=0;i<4;i++)attempts.failed("127.0.0.1");
        login(null,new MockHttpSession()).andExpect(jsonPath("$.code").value(1)); assertThat(calls.get()).isZero();
        var session=new MockHttpSession(); session.setAttribute("captcha","ABC23"); reply.set("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
        login("ABC23",session).andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/user").session(session).accept("application/json")).andExpect(status().isUnauthorized());
        reply.set("{\"success\":true}");
        login("token+with&special=characters",new MockHttpSession()).andExpect(jsonPath("$.code").value(0));
        assertThat(body.get()).isEqualTo("secret=synthetic%2Bsecret%26key&response=token%2Bwith%26special%3Dcharacters");
        assertThat(attempts.count("127.0.0.1")).isZero();
    }
    @Test void registrationAndForgotPasswordRequireSuccessfulServerVerificationBeforeSideEffects() throws Exception {
        reply.set("{\"success\":false}");
        var input=Map.of("email","new-captcha@example.invalid","nickname","Captcha New","password","test-password","captcha","expired-token");
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(jsonPath("$.code").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isOne();
        mvc.perform(post("/auth/forgot").with(csrf()).contentType("application/json").content("{\"email\":\"captcha@example.invalid\",\"captcha\":\"expired-token\"}"))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(mail);
        reply.set("{\"success\":true}");
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(2);
        mvc.perform(post("/auth/forgot").with(csrf()).contentType("application/json").content("{\"email\":\"captcha@example.invalid\",\"captcha\":\"fresh-token\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(mail).forgot("captcha@example.invalid","127.0.0.1");
    }
    @Test void invalidOrUnavailableUpstreamNeverGrantsAccessAndDisablingRestoresSingleUseImages() throws Exception {
        for(int i=0;i<4;i++)attempts.failed("127.0.0.1");
        status.set(503);
        login("token",new MockHttpSession()).andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.message").value("人机验证服务暂时不可用，请稍后重试"));
        status.set(200); reply.set("not-json");
        login("token",new MockHttpSession()).andExpect(jsonPath("$.code").value(1));
        reply.set("{\"success\":\"true\"}"); assertThat(captcha.verify(new MockHttpSession(),"token")).isFalse();
        settings.save("options","recaptcha",Map.of("recaptcha_sitekey",""));
        int previousCalls=calls.get(); var session=new MockHttpSession();
        mvc.perform(get("/auth/captcha").session(session)).andExpect(content().contentType("image/png"));
        String answer=(String)session.getAttribute("captcha");
        assertThat(captcha.verify(session,answer.toLowerCase(Locale.ROOT))).isTrue();
        assertThat(captcha.verify(session,answer)).isFalse();
        assertThat(calls.get()).isEqualTo(previousCalls);
    }
    private org.springframework.test.web.servlet.ResultActions login(String token,MockHttpSession session) throws Exception {
        var input=new LinkedHashMap<String,Object>(); input.put("identification","captcha@example.invalid"); input.put("password","test-password"); input.put("captcha",token);
        return mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)));
    }
}
