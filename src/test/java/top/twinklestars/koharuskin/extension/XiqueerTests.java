package top.twinklestars.koharuskin.extension;

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
import top.twinklestars.koharuskin.account.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class XiqueerTests {
    static final AtomicInteger sequence=new AtomicInteger(1400),calls=new AtomicInteger(),responseStatus=new AtomicInteger(200);
    static final AtomicReference<String> responseBody=new AtomicReference<>(""),requestBody=new AtomicReference<>("");
    static final AtomicReference<Runnable> onQuery=new AtomicReference<>(()->{});
    static HttpServer upstream;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws IOException {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/query",exchange->{
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            onQuery.get().run();
            byte[] body=responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(),body.length);
            try(var output=exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        registry.add("koharu.xiqueer-url",()->"http://127.0.0.1:"+upstream.getAddress().getPort()+"/query");
    }
    @AfterAll static void stop() { upstream.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMapper accounts;
    @Autowired XiqueerVerification verification;
    @Autowired OfficialExtensions extensions;
    @MockitoBean Clock clock;
    AccountUser student;
    Instant now;

    @BeforeEach void fixture() {
        int uid=sequence.incrementAndGet();
        student=new AccountUser(uid,"Student123@stu.ncwu.edu.cn","Student","unused",0);
        now=Instant.now(); when(clock.instant()).thenReturn(now);
        calls.set(0); responseStatus.set(200); responseBody.set(""); onQuery.set(()->{});
        for(String table:List.of("notifications","users","players","options")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('plugins_enabled','[\"xiqueer-verification\"]'),('require_verification','true')");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,'Student','unused',0,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,false)",uid,student.email());
    }
    @Test void qrPageAndPollingUseOriginalProtocolAndVerifyMatchingStudentOnly() throws Exception {
        var session=new MockHttpSession();
        var page=mvc.perform(get("/user/xiqueer-verification").with(user(student)).session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/official/xiqueer/qrcode.min.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/official/xiqueer/verify.js"))).andReturn();
        var view=(XiqueerVerification.Page)page.getModelAndView().getModel().get("verification");
        assertThat(view.challenge()).matches("smdljwxt[0-9A-V]{16}");
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf()).session(session))
                .andExpect(jsonPath("$.code").value(1));
        assertThat(requestBody.get()).isEqualTo("operate=query&qrCode="+view.challenge());
        assertThat(accounts.verified(student.uid())).isFalse();
        responseBody.set(" student123 \n");
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf()).session(session))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(accounts.verified(student.uid())).isTrue();
        assertThat(session.getAttribute("xiqueer-verification.challenge")).isNull();
        mvc.perform(get("/user/xiqueer-verification/challenge").with(user(student)).session(session))
                .andExpect(jsonPath("$.data.verified").value(true));
        assertThat(calls.get()).isEqualTo(2);
        mvc.perform(get("/official/xiqueer/qrcode.min.js")).andExpect(status().isOk());
    }
    @Test void challengeExpiresAfterFiveMinutesAndCannotBeUsedByAnotherAccount() throws Exception {
        var session=new MockHttpSession();
        String first=(String)verification.challenge(student,session).data().get("challenge");
        String second=(String)verification.challenge(student,session).data().get("challenge");
        assertThat(second).isNotEqualTo(first);
        when(clock.instant()).thenReturn(now.plusSeconds(300));
        assertThat(verification.status(student,session).code()).isEqualTo(3);
        assertThat(calls.get()).isZero();
        assertThat(session.getAttribute("xiqueer-verification.challenge")).isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(301));
        verification.challenge(student,session);
        var other=new AccountUser(student.uid()+100,"other@stu.ncwu.edu.cn","Other","unused",0);
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,'Other','unused',0,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,false)",other.uid(),other.email());
        assertThat(verification.status(other,session).code()).isEqualTo(3);
        assertThat(calls.get()).isZero();
        assertThat(accounts.verified(student.uid())).isFalse();
    }
    @Test void upstreamFailuresCanRetryButWrongStudentInvalidatesChallenge() {
        var session=new MockHttpSession(); verification.challenge(student,session);
        responseBody.set("<html>error</html>");
        assertThat(verification.status(student,session).code()).isEqualTo(2);
        responseStatus.set(503); responseBody.set("student123");
        assertThat(verification.status(student,session).code()).isEqualTo(2);
        assertThat(accounts.verified(student.uid())).isFalse();
        responseStatus.set(200); responseBody.set("student999");
        assertThat(verification.status(student,session).data()).containsEntry("reload",true);
        assertThat(accounts.verified(student.uid())).isFalse();
        assertThat(session.getAttribute("xiqueer-verification.challenge")).isNull();
        assertThat(verification.status(student,session).code()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);
    }
    @Test void changingEmailWhileUpstreamIsQueriedDoesNotVerifyNewIdentity() {
        var session=new MockHttpSession(); verification.challenge(student,session);
        responseBody.set("student123");
        onQuery.set(()->jdbc.update("UPDATE users SET email='different@stu.ncwu.edu.cn' WHERE uid=?",student.uid()));
        assertThat(verification.status(student,session).code()).isEqualTo(3);
        assertThat(accounts.verified(student.uid())).isFalse();
    }
    @Test void disabledExtensionUnsupportedEmailAndCsrfAreEnforced() throws Exception {
        var session=new MockHttpSession(); verification.challenge(student,session);
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/user/xiqueer-verification/challenge").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/hegaolian").with(user(student))).andExpect(redirectedUrl("/user/xiqueer-verification"));
        extensions.setEnabled("xiqueer-verification",false);
        mvc.perform(get("/user/xiqueer-verification").with(user(student))).andExpect(status().isNotFound());
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/user").with(user(student))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/official/xiqueer/"))));
        extensions.setEnabled("xiqueer-verification",true);
        jdbc.update("UPDATE users SET email='outside@example.invalid' WHERE uid=?",student.uid());
        var outside=accounts.findById(student.uid());
        assertThat(verification.challenge(outside,session).code()).isEqualTo(2);
        assertThat(verification.page(outside,session).eligible()).isFalse();
        assertThat(session.getAttribute("xiqueer-verification.challenge")).isNull();
        assertThat(calls.get()).isZero();
    }
    @Test void originalChallengeAndPollingLimitsApplyAcrossSessionsAndRecoverAfterMinute() throws Exception {
        for(int i=0;i<12;i++) mvc.perform(get("/user/xiqueer-verification/challenge").with(user(student)))
                .andExpect(status().isOk());
        mvc.perform(get("/user/xiqueer-verification/challenge").with(user(student))).andExpect(status().isTooManyRequests());
        for(int i=0;i<40;i++) mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf()))
                .andExpect(jsonPath("$.code").value(3));
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf())).andExpect(status().isTooManyRequests());
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        mvc.perform(get("/user/xiqueer-verification/challenge").with(user(student))).andExpect(status().isOk());
        mvc.perform(post("/user/xiqueer-verification/status").with(user(student)).with(csrf())).andExpect(status().isOk());
        assertThat(calls.get()).isZero();
    }
}
