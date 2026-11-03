package top.twinklestars.koharuskin.extension.henan;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.extension.OfficialExtensions;
import javax.crypto.Cipher;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
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
class HenanTests {
    static HttpServer server;
    static KeyPair rsa;
    static final String SCHOOL_PASSWORD="school-test-password",SITE_PASSWORD="local-test-password";
    static final AtomicBoolean requireMfa=new AtomicBoolean(),scanPassword=new AtomicBoolean(true),badCredentials=new AtomicBoolean(),evilMfa=new AtomicBoolean();
    static final AtomicBoolean unavailable=new AtomicBoolean(),emptyDetection=new AtomicBoolean();
    static final AtomicInteger calls=new AtomicInteger(),smsSent=new AtomicInteger(),followedRedirect=new AtomicInteger();
    static final AtomicReference<Throwable> upstreamFailure=new AtomicReference<>();
    static final AtomicReference<String> ncwuRawForm=new AtomicReference<>(""),rsaPassword=new AtomicReference<>("");
    static final JsonMapper JSON=JsonMapper.builder().build();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        rsa=KeyPairGenerator.getInstance("RSA").generateKeyPair();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try { handle(exchange); }
            catch(Exception error) { upstreamFailure.set(error); reply(exchange,500,"test upstream failed"); }
        });
        server.start();
        registry.add("koharu.henan.ncwu-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/hsjw");
        registry.add("koharu.henan.zzu-url",()->"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterAll static void stop() { server.stop(0); }
    static void handle(HttpExchange exchange) throws Exception {
        calls.incrementAndGet();
        if(unavailable.get()) { reply(exchange,503,"unavailable"); return; }
        String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
        String cookie=Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),"");
        String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        if(path.equals("/hsjw/cas/login.action")) {
            check(exchange.getRequestHeaders().getFirst("X-Requested-With")==null,"NCWU login page must use a normal request");
            exchange.getResponseHeaders().add("Set-Cookie","JSESSIONID=ncwu-session; Path=/hsjw");
            reply(exchange,200,"<input value='1' name='hid_flag'><input name='hid_dxyzm' value='sms&amp;flag'><script>var isXqeScanQrCode = "+scanPassword.get()+";</script>");
        } else if(path.equals("/hsjw/frame/homepage")) {
            check(cookie.contains("ncwu-session"),"lost NCWU cookie");
            reply(exchange,200,exchange.getRequestURI().getQuery().contains("getTempDeskey")?"12345678":"1712345678");
        } else if(path.equals("/hsjw/cas/logon.action")) {
            check("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With")),"NCWU logon must use AJAX");
            check(cookie.contains("ncwu-session"),"lost NCWU cookie"); ncwuRawForm.set(body);
            reply(exchange,200,"{\"status\":"+(badCredentials.get()?401:200)+"}");
        } else if(path.equals("/cas/a/login")&&method.equals("GET")) {
            check(exchange.getRequestHeaders().getFirst("X-Requested-With")==null,"CAS login page must use a normal request");
            exchange.getResponseHeaders().add("Set-Cookie","JSESSIONID=zzu-session; Path=/");
            reply(exchange,200,"<input value='exec&amp;token' name='execution'><form id='fm1'></form>");
        } else if(path.equals("/cas/jwt/publicKey")) {
            check(cookie.contains("zzu-session"),"lost ZZU cookie");
            reply(exchange,200,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        } else if(path.equals("/cas/mfa/detect")) {
            check("XMLHttpRequest".equals(exchange.getRequestHeaders().getFirst("X-Requested-With")),"MFA detection must use AJAX");
            if(emptyDetection.get()) { reply(exchange,200,""); return; }
            check(cookie.contains("zzu-session"),"lost ZZU cookie");
            var form=form(body); check(form.get("password").startsWith("__RSA__"),"missing RSA prefix");
            var decrypt=Cipher.getInstance("RSA/ECB/PKCS1Padding"); decrypt.init(Cipher.DECRYPT_MODE,rsa.getPrivate());
            rsaPassword.set(new String(decrypt.doFinal(Base64.getDecoder().decode(form.get("password").substring(7))),StandardCharsets.UTF_8));
            reply(exchange,200,"{\"code\":0,\"data\":{\"state\":\"mfa-state\",\"need\":"+requireMfa.get()+",\"mfaTypeSecurePhone\":true}}");
        } else if(path.equals("/cas/mfa/initByType/securephone")) {
            check(exchange.getRequestURI().getQuery().equals("state=mfa-state"),"wrong MFA state");
            exchange.getResponseHeaders().add("Set-Cookie","MFA=initialized; Path=/");
            reply(exchange,200,"{\"code\":0,\"data\":{\"attestServerUrl\":\""+(evilMfa.get()?"https://untrusted.invalid/mf":"/attest/")+"\",\"gid\":\"test-gid\",\"securePhone\":\"138****0000\"}}");
        } else if(path.equals("/attest/api/guard/securephone/send")) {
            check(cookie.contains("zzu-session")&&cookie.contains("MFA=initialized"),"lost MFA cookies");
            check(JSON.readTree(body).path("gid").asString().equals("test-gid"),"wrong MFA gid"); smsSent.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie","MFA_SENT=1; Path=/"); reply(exchange,200,"{\"code\":0}");
        } else if(path.equals("/attest/api/guard/securephone/valid")) {
            check(cookie.contains("MFA_SENT=1"),"lost SMS cookie");
            boolean valid=JSON.readTree(body).path("code").asString().equals("123456");
            if(valid) exchange.getResponseHeaders().add("Set-Cookie","MFA_OK=1; Path=/");
            reply(exchange,200,"{\"code\":0,\"data\":{\"status\":"+(valid?2:1)+"}}");
        } else if(path.equals("/cas/a/login")&&method.equals("POST")) {
            check(exchange.getRequestHeaders().getFirst("X-Requested-With")==null,"CAS login must use a normal form");
            var form=form(body);
            check(form.get("execution").equals("exec&token")&&form.get("mfaState").equals("mfa-state"),"wrong CAS state");
            check(!requireMfa.get()||cookie.contains("MFA_OK=1"),"CAS submitted before MFA");
            if(badCredentials.get()) reply(exchange,401,"<script>var errors=[\"\\u5bc6\\u7801\\u9519\\u8bef\"];</script>");
            else {
                exchange.getResponseHeaders().add("Set-Cookie","TGC=ticket; Path=/");
                exchange.getResponseHeaders().add("Location","/service?ticket=ST-test"); reply(exchange,302,"");
            }
        } else if(path.equals("/service")) { followedRedirect.incrementAndGet(); reply(exchange,200,"service"); }
        else reply(exchange,404,"missing");
    }
    static void check(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
    static void reply(HttpExchange exchange,int status,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","text/plain;charset=UTF-8");
        exchange.sendResponseHeaders(status,bytes.length); try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }
    static Map<String,String> form(String body) {
        var values=new HashMap<String,String>();
        for(String field:body.split("&")) { String[] pair=field.split("=",2); values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8)); }
        return values;
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NcwuAuth ncwu;
    @Autowired ZzuAuth zzu;
    @Autowired HenanLogin login;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired OfficialExtensions extensions;
    @MockitoBean Clock clock;
    Instant now;
    String previousHash;
    @BeforeEach void fixture() {
        now=Instant.now(); when(clock.instant()).thenReturn(now);
        requireMfa.set(false); scanPassword.set(true); badCredentials.set(false); evilMfa.set(false);
        unavailable.set(false); emptyDetection.set(false);
        calls.set(0); smsSent.set(0); followedRedirect.set(0); upstreamFailure.set(null);
        for(String table:List.of("users","players","options","notifications","uuid")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','false'),('plugins_enabled','[\"auth-henan\"]'),('regs_per_ip','10'),('user_initial_score','500')");
        previousHash=passwords.encode(SITE_PASSWORD);
        for(String school:List.of("ncwu","zzu")) jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,permission,register_at,last_sign_at,verified) VALUES(?,?, 'OldPlayer',?,500,'127.0.0.1',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,false)",
                school.equals("ncwu")?1501:1502,"student1@stu."+school+".edu.cn",previousHash);
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(1502,1502,'OldPlayer',123,456,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('OldPlayer','123456781234123412341234567890ab')");
    }
    @AfterEach void verifyUpstream() { assertThat(upstreamFailure.get()).isNull(); }

    @Test void kingoCipherMatchesOriginalPhpIncludingUnicodeAndRepeatedBlocks() throws Exception {
        assertThat(KingoDes.encrypt("abc","12345678")).isEqualTo("876D7CB6874F041C");
        assertThat(KingoDes.encrypt("user=学生😀&password=xY9!","临时Key123")).isEqualTo("4B8A0E67695B3D9FD908E855010896F1AD365AD5E852CAFC04D1B82EFC4B038743A890869F8E3E76604B6819D14F4607");
        assertThat(KingoDes.encrypt("abcdabcd","key")).isEqualTo("7CAB6E3E87D0F77A7CAB6E3E87D0F77A");
    }
    @Test void ncwuPreservesSessionAndEncodesBothPasswordModes() throws Exception {
        for(boolean scan:List.of(true,false)) {
            scanPassword.set(scan); assertThat(ncwu.login("student1",SCHOOL_PASSWORD)).isTrue();
            var sent=form(ncwuRawForm.get());
            String raw="_u="+Base64.getEncoder().encodeToString("student1;;ncwu-session".getBytes(StandardCharsets.UTF_8))+"&_p="
                    +(scan?NcwuAuth.md5(NcwuAuth.md5(SCHOOL_PASSWORD)+NcwuAuth.md5("")):SCHOOL_PASSWORD)
                    +"&randnumber=&isPasswordPolicy=1&txt_mm_expression=5&txt_mm_length=20&txt_mm_userzh=0&hid_flag=1&hidlag=1&hid_dxyzm=sms&flag";
            assertThat(sent).containsEntry("ssessionid","ncwu-session").containsEntry("timestamp","1712345678")
                    .containsEntry("token",NcwuAuth.md5(NcwuAuth.md5(raw)+NcwuAuth.md5("1712345678")));
            assertThat(new String(Base64.getDecoder().decode(sent.get("params")),StandardCharsets.US_ASCII)).isEqualTo(KingoDes.encrypt(raw,"12345678"));
        }
        badCredentials.set(true); assertThat(ncwu.login("student1",SCHOOL_PASSWORD)).isFalse();
    }
    @Test void zzuRsaLoginPreservesLocalPasswordUuidAndTextureAssociations() throws Exception {
        mvc.perform(post("/auth/login/henan").with(csrf()).param("school","zzu").param("identification","student1").param("password",SCHOOL_PASSWORD))
                .andExpect(redirectedUrl("/user"));
        assertThat(rsaPassword.get()).isEqualTo(SCHOOL_PASSWORD); assertThat(followedRedirect.get()).isZero();
        assertThat(accounts.findById(1502).password()).isEqualTo(previousHash); assertThat(accounts.verified(1502)).isTrue();
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='OldPlayer'",String.class)).isEqualTo("123456781234123412341234567890ab");
        assertThat(jdbc.queryForObject("SELECT tid_skin FROM players WHERE pid=1502",Integer.class)).isEqualTo(123);
        badCredentials.set(true); assertThat(zzu.login("student1",SCHOOL_PASSWORD).authenticated()).isFalse();
    }
    @Test void registrationCreatesVerifiedAccountAndPlayerUsingSeparateSitePassword() throws Exception {
        var session=new MockHttpSession(); session.setAttribute("captcha","ABCDE");
        mvc.perform(post("/auth/register/henan").with(csrf()).session(session).param("school","ncwu").param("identification","student2")
                .param("password",SCHOOL_PASSWORD).param("site_password",SITE_PASSWORD).param("player_name","NewStudent").param("captcha","ABCDE"))
                .andExpect(redirectedUrl("/user"));
        var created=accounts.findByEmail("student2@stu.ncwu.edu.cn");
        assertThat(passwords.matches(SITE_PASSWORD,created.password())).isTrue(); assertThat(passwords.matches(SCHOOL_PASSWORD,created.password())).isFalse();
        assertThat(accounts.verified(created.uid())).isTrue();
        assertThat(jdbc.queryForObject("SELECT name FROM players WHERE uid=?",String.class,created.uid())).isEqualTo("NewStudent");
        assertThat(jdbc.queryForObject("SELECT score FROM users WHERE uid=?",Integer.class,created.uid())).isEqualTo(500);
        mvc.perform(get("/api/user").session(session).accept("application/json")).andExpect(status().isOk()).andExpect(jsonPath("$.uid").value(created.uid()));
    }
    @Test void mfaRequiresSendBindsSessionEnforcesCooldownAndConsumesChallenge() throws Exception {
        requireMfa.set(true); var session=new MockHttpSession();
        var start=mvc.perform(post("/auth/login/henan").with(csrf()).session(session).param("school","zzu").param("identification","student1").param("password",SCHOOL_PASSWORD)).andReturn();
        String path=start.getResponse().getRedirectedUrl(); assertThat(path).matches("/auth/mfa/henan/[a-f0-9]{64}");
        String token=path.substring(path.lastIndexOf('/')+1);
        assertThat(JSON.writeValueAsString(login.pending(session,token))).doesNotContain(SCHOOL_PASSWORD,SITE_PASSWORD);
        mvc.perform(get(path).session(session)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("138****0000")));
        mvc.perform(get(path).session(new MockHttpSession())).andExpect(redirectedUrl("/auth/login/henan"));
        mvc.perform(post(path+"/verify").session(session).with(csrf()).param("code","123456")).andExpect(flash().attribute("henanError","请先发送短信验证码"));
        mvc.perform(post(path+"/send").session(session)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/send").session(session).with(csrf())).andExpect(redirectedUrl(path));
        mvc.perform(post(path+"/send").session(session).with(csrf())).andExpect(flash().attributeExists("henanError")); assertThat(smsSent.get()).isOne();
        when(clock.instant()).thenReturn(now.plusSeconds(61));
        mvc.perform(post(path+"/send").session(session).with(csrf())).andExpect(redirectedUrl(path)); assertThat(smsSent.get()).isEqualTo(2);
        mvc.perform(post(path+"/verify").session(session).with(csrf()).param("code","000000")).andExpect(flash().attribute("henanError","短信验证码错误，请重试"));
        assertThat(accounts.verified(1502)).isFalse();
        mvc.perform(post(path+"/verify").session(session).with(csrf()).param("code","123456")).andExpect(redirectedUrl("/user"));
        assertThat(login.pending(session,token)).isNull(); assertThat(accounts.verified(1502)).isTrue();
        assertThat(accounts.findById(1502).password()).isEqualTo(previousHash);
        mvc.perform(get("/api/user").session(session).accept("application/json")).andExpect(jsonPath("$.uid").value(1502));
    }
    @Test void registrationMfaExpiresCancelsAndCompletesWithoutKeepingPlaintextPasswords() {
        requireMfa.set(true); var session=new MockHttpSession();
        var start=login.start("register","zzu","student2",SCHOOL_PASSWORD,SITE_PASSWORD,"MfaStudent","127.0.0.1",session);
        assertThat(JSON.writeValueAsString(login.pending(session,start.token()))).doesNotContain(SCHOOL_PASSWORD,SITE_PASSWORD);
        assertThat(accounts.findByEmail("student2@stu.zzu.edu.cn")).isNull();
        when(clock.instant()).thenReturn(now.plusSeconds(300)); assertThat(login.pending(session,start.token())).isNull();
        assertThatThrownBy(()->login.send(session,start.token())).isInstanceOf(HenanLogin.Expired.class);
        var next=login.start("register","zzu","student2",SCHOOL_PASSWORD,SITE_PASSWORD,"MfaStudent","127.0.0.1",session);
        assertThat(login.cancel(session,next.token())).isEqualTo("register"); assertThat(login.pending(session,next.token())).isNull();
        var finalStart=login.start("register","zzu","student2",SCHOOL_PASSWORD,SITE_PASSWORD,"MfaStudent","127.0.0.1",session);
        login.send(session,finalStart.token());
        var created=login.verify(session,finalStart.token(),"123456","127.0.0.1");
        assertThat(passwords.matches(SITE_PASSWORD,created.password())).isTrue(); assertThat(accounts.verified(created.uid())).isTrue();
        assertThatThrownBy(()->login.verify(session,finalStart.token(),"123456","127.0.0.1")).isInstanceOf(HenanLogin.Expired.class);
    }
    @Test void gatesProtectDisabledBannedUnregisteredAndUntrustedMfaDestinations() throws Exception {
        mvc.perform(get("/auth/login/henan")).andExpect(status().isOk());
        mvc.perform(get("/auth/register/henan")).andExpect(status().isOk());
        mvc.perform(post("/auth/login/henan").with(csrf()).param("school","zzu").param("identification","missing").param("password",SCHOOL_PASSWORD))
                .andExpect(redirectedUrl("/auth/login/henan")).andExpect(flash().attributeExists("henanError"));
        assertThat(calls.get()).isZero(); jdbc.update("UPDATE users SET permission=-1 WHERE uid=1502");
        assertThatThrownBy(()->login.start("login","zzu","student1",SCHOOL_PASSWORD,null,null,"127.0.0.1",new MockHttpSession())).isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isZero();
        extensions.setEnabled("auth-henan",false);
        mvc.perform(get("/auth/login/henan")).andExpect(status().isNotFound());
        mvc.perform(get("/auth/login")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/auth/login/henan"))));
        extensions.setEnabled("auth-henan",true); requireMfa.set(true); evilMfa.set(true);
        assertThatThrownBy(()->zzu.login("student1",SCHOOL_PASSWORD)).isInstanceOf(BusinessException.class).hasMessageContaining("服务器地址");
        assertThat(smsSent.get()).isZero();
    }
    @Test void validationAndRegistrationConflictsLeaveNoPartialAccountsOrSecretsInFlash() throws Exception {
        var invalid=mvc.perform(post("/auth/register/henan").with(csrf()).param("school","ncwu").param("identification","student2")
                .param("password",SCHOOL_PASSWORD).param("site_password",SITE_PASSWORD).param("player_name","NewPlayer").param("captcha","wrong"))
                .andExpect(flash().attributeExists("henanError")).andReturn();
        assertThat(JSON.writeValueAsString(invalid.getFlashMap())).doesNotContain(SCHOOL_PASSWORD,SITE_PASSWORD);
        assertThat(calls.get()).isZero();
        assertThatThrownBy(()->login.start("register","ncwu","student2",SCHOOL_PASSWORD,SITE_PASSWORD,"OldPlayer","127.0.0.1",new MockHttpSession()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("角色名");
        assertThat(accounts.findByEmail("student2@stu.ncwu.edu.cn")).isNull();
        jdbc.update("UPDATE options SET option_value='2' WHERE option_name='regs_per_ip'");
        assertThatThrownBy(()->login.start("register","ncwu","student2",SCHOOL_PASSWORD,SITE_PASSWORD,"NewPlayer","127.0.0.1",new MockHttpSession()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("上限");
        assertThat(accounts.findByEmail("student2@stu.ncwu.edu.cn")).isNull();
    }
    @Test void unavailableOrMalformedSchoolResponsesDoNotVerifyOrExposePasswords() throws Exception {
        for(boolean outage:List.of(true,false)) {
            unavailable.set(outage); emptyDetection.set(!outage);
            var result=mvc.perform(post("/auth/login/henan").with(csrf()).param("school","zzu").param("identification","student1").param("password",SCHOOL_PASSWORD))
                    .andExpect(redirectedUrl("/auth/login/henan")).andExpect(flash().attributeExists("henanError")).andReturn();
            assertThat(JSON.writeValueAsString(result.getFlashMap())).doesNotContain(SCHOOL_PASSWORD,SITE_PASSWORD);
            assertThat(accounts.verified(1502)).isFalse();
        }
    }
}
