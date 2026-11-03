package top.twinklestars.koharuskin.yggdrasil;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountMapper;
import top.twinklestars.koharuskin.admin.AdminSettings;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","koharu.union-network-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnionMemberTests {
    static HttpServer upstream;
    static KeyPair signing,textureKey;
    static final Map<String,String> replies=new ConcurrentHashMap<>();
    static final AtomicReference<String> sentBody=new AtomicReference<>(),sentKey=new AtomicReference<>();
    static final AtomicInteger calls=new AtomicInteger();
    @DynamicPropertySource static void server(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(4096);
        signing=generator.generateKeyPair(); textureKey=generator.generateKeyPair();
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/union",exchange->{
            calls.incrementAndGet();
            sentKey.set(exchange.getRequestHeaders().getFirst("X-Union-Member-Key"));
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String reply=replies.get(exchange.getRequestURI().getPath());
            byte[] bytes=(reply==null?"{}":reply).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(reply==null?404:200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); upstream.start();
    }
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired SiteOptions options;
    @Autowired AccountMapper accounts;
    @Autowired AdminSettings settings;
    @Autowired YggKeys keys;
    @Autowired UnionMapper mapper;
    @Autowired YggMapper tokens;
    @Autowired top.twinklestars.koharuskin.extension.OfficialExtensions extensions;
    @BeforeEach void fixture() {
        for(String table:List.of("koharu_union_nonces","koharu_ygg_sessions","koharu_ygg_tokens","players","uuid","users","options")) jdbc.update("DELETE FROM "+table);
        options.insert("register_with_player_name","false"); options.insert("plugins_enabled","[\"yggdrasil-api\",\"yggdrasil-connect\"]");
        options.insert("union_api_root","http://127.0.0.1:"+upstream.getAddress().getPort()+"/union"); options.insert("union_member_key","synthetic-member-key");
        options.insert("ygg_private_key",YggKeys.pem("PRIVATE KEY",signing.getPrivate().getEncoded()));
        for(int uid=2401;uid<=2402;uid++) {
            jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?,'unused',2,321,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,"union"+uid+"@example.invalid","Union"+uid);
            jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(?,?,?,0,0,CURRENT_TIMESTAMP)",uid,uid,"Union"+uid);
            jdbc.update("INSERT INTO uuid(name,uuid) VALUES(?,?)","Union"+uid,(uid==2401?"a":"b").repeat(32));
            jdbc.update("INSERT INTO koharu_ygg_tokens(token_hash,user_uid,client_token,player_pid,profile_name,credentials_hash,issued_at,valid_until,expires_at) VALUES(?,?,'client',?,?,?,1,9999999999,9999999999)",(""+uid).repeat(16),uid,uid,"Union"+uid,"f".repeat(64));
        }
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('RemovedPlayer',?)","f".repeat(32));
        replies.clear(); calls.set(0); sentBody.set(""); sentKey.set("");
        replies.put("/union",json.writeValueAsString(Map.of("union_host_signature_public_key",YggKeys.pem("PUBLIC KEY",signing.getPublic().getEncoded()))));
        replies.put("/union/serverlist","{\"servers\":{\"synthetic\":\"https://example.invalid\"},\"version\":7}");
        replies.put("/union/privatekey",json.writeValueAsString(Map.of("privateKey",YggKeys.pem("PRIVATE KEY",textureKey.getPrivate().getEncoded()),"privateKeyVersion",8)));
        replies.put("/union/sync","{}"); replies.put("/union/diagnose","{}");
    }
    @Test void signaturesBindRawBodyTimestampAndNonceAndReplayIsRejected() throws Exception {
        mvc.perform(post("/api/union/member/diagnose").contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        assertThat(calls.get()).isZero();
        var expired=signed("{}",Instant.now().getEpochSecond()-11,"expired");
        send("diagnose","{}",expired).andExpect(status().isForbidden());
        send("diagnose","{}",signed("{}",Instant.now().getEpochSecond()+60,"future")).andExpect(status().isForbidden());
        String raw="{\"nonce\":\"原协议测试\"}"; var valid=signed(raw);
        send("diagnose","{\"nonce\":\"tampered\"}",valid).andExpect(status().isForbidden());
        send("diagnose",raw,valid).andExpect(status().isOk()).andExpect(jsonPath("$.nonce").value("原协议测试"));
        send("diagnose",raw,valid).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM koharu_union_nonces",Integer.class)).isOne();
    }
    @Test void concurrentReplayOnlyExecutesOnce() throws Exception {
        String body="{}"; var headers=signed(body);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var jobs=List.of(1,2).stream().map(i->executor.submit(()->{start.await();return send("diagnose",body,headers).andReturn().getResponse().getStatus();})).toList();
            start.countDown();
            assertThat(List.of(jobs.get(0).get(10,TimeUnit.SECONDS),jobs.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,403);
        }
    }
    @Test void listSyncAndMemberKeyRotationUseExistingProtocolAndDoNotLeakSecrets() throws Exception {
        command("updatelist","{}").andExpect(status().isOk());
        assertThat(options.get("union_server_list_version")).isEqualTo("7");
        assertThat(sentKey.get()).isEqualTo("synthetic-member-key");
        command("sync","{}").andExpect(status().isOk());
        var profiles=json.readTree(sentBody.get()).get("profileList");
        assertThat(profiles.size()).isEqualTo(2); assertThat(profiles.get("a".repeat(32)).asString()).isEqualTo("Union2401");
        command("updatebackendkey","{\"key\":\"rotated-synthetic-key\"}").andExpect(status().isOk());
        command("updatelist","{}").andExpect(status().isOk()); assertThat(sentKey.get()).isEqualTo("rotated-synthetic-key");
        mvc.perform(get("/api/union/member")).andExpect(status().isOk()).andExpect(jsonPath("$.serverListVersion").value("7"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("synthetic-key"))));
        replies.put("/union/serverlist","{\"servers\":[]}");
        command("updatelist","{}").andExpect(status().isBadGateway()); assertThat(options.get("union_server_list_version")).isEqualTo("7");
    }
    @Test void textureKeyUpdatesAreAtomicAndImmediatelyUsedForSigning() throws Exception {
        command("updateprivatekey","{}").andExpect(status().isOk());
        assertThat(options.get("union_private_key_version")).isEqualTo("8");
        var verifier=Signature.getInstance("SHA1withRSA"); verifier.initVerify(textureKey.getPublic()); verifier.update("texture".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(keys.sign("texture",keys.pair().getPrivate())))).isTrue();
        String kept=options.get("ygg_private_key");
        replies.put("/union/privatekey","{\"privateKey\":\"invalid\",\"privateKeyVersion\":9}");
        command("updateprivatekey","{}").andExpect(status().isBadGateway());
        assertThat(options.get("ygg_private_key")).isEqualTo(kept); assertThat(options.get("union_private_key_version")).isEqualTo("8");
    }
    @Test void remapUsesSnapshotPreservesRelationsRevokesTokensAndRejectsCollisionsAtomically() throws Exception {
        String collision=json.writeValueAsString(Map.of("remapped_uuid",Map.of("a".repeat(32),"b".repeat(32))));
        command("remapuuid",collision).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Union2401'",String.class)).isEqualTo("a".repeat(32));
        String mapped=json.writeValueAsString(Map.of("remapped_uuid",Map.of("a".repeat(32),"b".repeat(32),"b".repeat(32),"c".repeat(32))));
        command("remapuuid",mapped).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Union2401'",String.class)).isEqualTo("b".repeat(32));
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Union2402'",String.class)).isEqualTo("c".repeat(32));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM koharu_ygg_tokens",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(score) FROM users",Integer.class)).isEqualTo(642);
    }
    @Test void emailQueriesRequireSignaturesAndAdminActionsRequireRoleAndCsrf() throws Exception {
        var isolated=new UnionMember(options,extensions,mapper,tokens,keys,json,java.time.Clock.systemUTC(),false);
        assertThatThrownBy(()->isolated.request("POST","/sync",Map.of()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("503");
        assertThat(calls.get()).isZero();
        mvc.perform(get("/api/union/member/queryemail").param("username","Union2401")).andExpect(status().isForbidden());
        var signed=signed("");
        mvc.perform(get("/api/union/member/queryemail").param("username","Union2401")
                .header("X-Message-Timestamp",signed.timestamp()).header("X-Message-Nonce",signed.nonce()).header("X-Message-Signature",signed.signature()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("union2401@example.invalid"));
        var admin=accounts.findById(2401);
        mvc.perform(post("/admin/union/member/sync").with(user(admin))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/union/member/sync").with(user(admin)).with(csrf())).andExpect(redirectedUrl("/admin/extensions/config#union"));
        var page=mvc.perform(get("/admin/extensions/config").with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("联盟成员操作").doesNotContain("synthetic-member-key");
        settings.save("extensions","union",Map.of("union_api_root",options.get("union_api_root"),"union_member_key",""));
        assertThat(options.get("union_member_key")).isEqualTo("synthetic-member-key");
        assertThatThrownBy(()->settings.save("extensions","union",Map.of("union_api_root","relative","union_member_key","")))
                .isInstanceOf(top.twinklestars.koharuskin.account.BusinessException.class);
        jdbc.update("UPDATE users SET permission=0 WHERE uid=2402");
        mvc.perform(post("/admin/union/member/sync").with(user(accounts.findById(2402))).with(csrf())).andExpect(status().isForbidden());
        options.update("plugins_enabled","[]");
        mvc.perform(get("/api/union/member")).andExpect(status().isNotFound());
        command("sync","{}").andExpect(status().isNotFound());
    }
    private Headers signed(String body) throws Exception { return signed(body,Instant.now().getEpochSecond(),UUID.randomUUID().toString()); }
    private Headers signed(String body,long timestamp,String nonce) throws Exception {
        var signature=Signature.getInstance("SHA256withRSA"); signature.initSign(signing.getPrivate());
        signature.update((body+timestamp+nonce).getBytes(StandardCharsets.UTF_8));
        return new Headers(""+timestamp,nonce,Base64.getEncoder().encodeToString(signature.sign()));
    }
    private org.springframework.test.web.servlet.ResultActions command(String operation,String body) throws Exception { return send(operation,body,signed(body)); }
    private org.springframework.test.web.servlet.ResultActions send(String operation,String body,Headers headers) throws Exception {
        return mvc.perform(post("/api/union/member/"+operation).contentType("application/json").content(body)
                .header("X-Message-Timestamp",headers.timestamp()).header("X-Message-Nonce",headers.nonce()).header("X-Message-Signature",headers.signature()));
    }
    record Headers(String timestamp,String nonce,String signature) {}
}
