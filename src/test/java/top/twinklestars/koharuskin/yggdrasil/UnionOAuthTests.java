package top.twinklestars.koharuskin.yggdrasil;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","koharu.union-network-enabled=true","server.address=127.0.0.1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnionOAuthTests {
    static HttpServer upstream;
    static KeyPair backend,signing;
    static final AtomicReference<String> reply=new AtomicReference<>();
    static final AtomicInteger calls=new AtomicInteger(),upstreamStatus=new AtomicInteger(200);
    @DynamicPropertySource static void server(DynamicPropertyRegistry registry) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(4096); backend=generator.generateKeyPair(); signing=generator.generateKeyPair();
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/union/oauth2/backend",exchange->{
            calls.incrementAndGet(); byte[] body=reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(upstreamStatus.get(),body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        }); upstream.start();
    }
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AccountMapper accounts;
    @Autowired JsonMapper json;
    @Autowired UnionOAuth oauth;
    @Autowired LoginAttempts attempts;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    String root,origin;
    AccountUser owner;
    @BeforeEach void fixture() {
        for(String table:List.of("players","uuid","users","options"))jdbc.update("DELETE FROM "+table);
        root="http://127.0.0.1:"+upstream.getAddress().getPort()+"/union"; origin="http://127.0.0.1:"+upstream.getAddress().getPort();
        options.insert("register_with_player_name","false"); options.insert("plugins_enabled","[\"yggdrasil-connect\"]");
        options.insert("union_api_root",root); options.insert("union_member_key","synthetic-member-secret"); options.insert("union_enable_oauth2","true");
        options.insert("union_oauth2_sig_private_key",YggKeys.pem("PRIVATE KEY",signing.getPrivate().getEncoded()));
        options.insert("union_oauth2_sig_public_key",YggKeys.pem("PUBLIC KEY",signing.getPublic().getEncoded()));
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(2601,'union-oauth@example.invalid','测试角色😀',?,0,321,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password"));
        owner=accounts.findById(2601); calls.set(0); upstreamStatus.set(200); attempts.clear("127.0.0.1");
        reply.set(json.writeValueAsString(Map.of("publicKey",YggKeys.pem("PUBLIC KEY",backend.getPublic().getEncoded()))));
    }
    @Test void encryptedGrantMatchesLegacyHmacSignatureAndChunkedRsaAndIgnoresForgedToken() throws Exception {
        long before=Instant.now().getEpochSecond();
        var response=mvc.perform(get(UnionOAuth.ROOT+"/grant").with(user(owner)).param("state","state+value&中文").param("scope","openid profile")
                        .param("userInfoToken","forged").param("uid","9999"))
                .andExpect(status().isFound()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andReturn().getResponse();
        String location=response.getHeader("Location"); assertThat(location).startsWith(root+"/oauth2/continue?").doesNotContain(owner.email(),"synthetic-member-secret","forged");
        var params=query(location); assertThat(params.get("state")).isEqualTo("state+value&中文"); assertThat(params.get("scope")).isEqualTo("openid profile");
        var token=decrypt(params.get("userInfoToken")); String info=token.get("userInfo").asString(),mac=token.get("mac").asString();
        var hmac=Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec("synthetic-member-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        assertThat(mac).isEqualTo(HexFormat.of().formatHex(hmac.doFinal(info.getBytes(StandardCharsets.UTF_8))));
        var verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(signing.getPublic()); verifier.update((info+"."+mac).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(token.get("signature").asString()))).isTrue();
        var user=json.readTree(Base64.getDecoder().decode(info));
        assertThat(user.get("uid").asLong()).isEqualTo(2601); assertThat(user.get("nickname").asString()).isEqualTo("测试角色😀");
        assertThat(user.get("email").asString()).isEqualTo(owner.email()); assertThat(user.get("expires_at").asLong()).isBetween(before+600,Instant.now().getEpochSecond()+600);
        assertThat(user.size()).isEqualTo(4);
        assertThat(options.get("union_oauth2_sig_private_key")).isEqualTo(YggKeys.pem("PRIVATE KEY",signing.getPrivate().getEncoded()));
    }
    @Test void anonymousGrantResumesAfterLoginAndEmptyEmailMustBeBoundFirst() throws Exception {
        String target=UnionOAuth.ROOT+"/grant?state=original%2Bstate&client_id=synthetic";
        var session=new MockHttpSession();
        mvc.perform(get(URI.create(target)).session(session)).andExpect(redirectedUrl("/auth/login"));
        assertThat(session.getAttribute("oauth.returnTo")).isEqualTo(target); assertThat(calls.get()).isZero();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json").content("{\"identification\":\"union-oauth@example.invalid\",\"password\":\"test-password\"}"))
                .andExpect(jsonPath("$.data.redirectTo").value(target));
        mvc.perform(get(URI.create(target)).session(session)).andExpect(status().isFound());
        jdbc.update("UPDATE users SET email='' WHERE uid=2601"); calls.set(0);
        var unbound=new MockHttpSession();
        mvc.perform(get(URI.create(target)).with(user(accounts.findById(2601))).session(unbound)).andExpect(redirectedUrl("/auth/bind"));
        assertThat(unbound.getAttribute("oauth.returnTo")).isEqualTo(target); assertThat(calls.get()).isZero();
    }
    @Test void corsOnlyAllowsConfiguredUnionOriginIncludingPortAndPublicEndpointOnlyExposesPublicKey() throws Exception {
        var response=mvc.perform(get(UnionOAuth.ROOT).header("Origin",origin)).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin",origin)).andExpect(header().string("Access-Control-Allow-Credentials","true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).get("signaturePublicKey").asString()).isEqualTo(YggKeys.pem("PUBLIC KEY",signing.getPublic().getEncoded()));
        assertThat(response).doesNotContain("PRIVATE KEY","synthetic-member-secret",owner.email());
        mvc.perform(options(UnionOAuth.ROOT+"/grant").header("Origin",origin).header("Access-Control-Request-Method","GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",origin));
        mvc.perform(get(UnionOAuth.ROOT+"/grant").with(user(owner)).header("Origin","https://foreign.example.invalid")).andExpect(status().isForbidden());
        assertThat(calls.get()).isZero();
    }
    @Test void disabledBannedAndInvalidKeyRequestsNeverIssueGrant() throws Exception {
        options.update("union_enable_oauth2","false");
        mvc.perform(get(UnionOAuth.ROOT)).andExpect(status().isForbidden());
        mvc.perform(get(UnionOAuth.ROOT+"/grant").with(user(owner))).andExpect(status().isForbidden());
        options.update("union_enable_oauth2","true"); jdbc.update("UPDATE users SET permission=-1 WHERE uid=2601");
        mvc.perform(get(UnionOAuth.ROOT+"/grant").with(user(owner)).accept("application/json")).andExpect(status().isUnauthorized());
        assertThat(calls.get()).isZero(); jdbc.update("UPDATE users SET permission=0 WHERE uid=2601");
        reply.set("{\"publicKey\":\"invalid\"}");
        mvc.perform(get(UnionOAuth.ROOT+"/grant").with(user(owner))).andExpect(status().isBadGateway()).andExpect(header().doesNotExist("Location"));
        options.update("union_oauth2_sig_public_key",YggKeys.pem("PUBLIC KEY",backend.getPublic().getEncoded()));
        mvc.perform(get(UnionOAuth.ROOT)).andExpect(status().isServiceUnavailable());
        assertThat(options.get("union_oauth2_sig_private_key")).isEqualTo(YggKeys.pem("PRIVATE KEY",signing.getPrivate().getEncoded()));
        options.update("plugins_enabled","[]"); mvc.perform(get(UnionOAuth.ROOT)).andExpect(status().isNotFound());
    }
    @Test void firstUseCreatesOnePersistentKeyAndMissingPublicKeyDoesNotRotatePrivateKey() throws Exception {
        options.update("union_oauth2_sig_public_key","");
        assertThat(oauth.publicKey()).isEqualTo(YggKeys.pem("PUBLIC KEY",signing.getPublic().getEncoded()));
        assertThat(options.get("union_oauth2_sig_private_key")).isEqualTo(YggKeys.pem("PRIVATE KEY",signing.getPrivate().getEncoded()));
        options.update("union_oauth2_sig_private_key",""); options.update("union_oauth2_sig_public_key","");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->oauth.publicKey()); var second=executor.submit(()->oauth.publicKey());
            String publicKey=first.get(15,TimeUnit.SECONDS); assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(publicKey);
            var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""))));
            assertThat(key.getModulus().bitLength()).isEqualTo(4096);
            assertThat(options.get("union_oauth2_sig_public_key")).isEqualTo(publicKey);
        }
    }
    @Test void actualHttpSessionCanCompleteGrantWithoutExposingPlainUserInfo() throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        try(var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build()) {
            String site="http://127.0.0.1:"+port;
            var request=java.net.http.HttpRequest.newBuilder(URI.create(site+UnionOAuth.ROOT+"/grant?state=real-http")).GET().build();
            var before=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(before.statusCode()).isEqualTo(302);
            var loginPage=client.send(java.net.http.HttpRequest.newBuilder(URI.create(site+"/auth/login")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            String csrf=org.jsoup.Jsoup.parse(loginPage.body()).selectFirst("meta[name=csrf-token]").attr("content");
            var login=java.net.http.HttpRequest.newBuilder(URI.create(site+"/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"identification\":\"union-oauth@example.invalid\",\"password\":\"test-password\"}")).build();
            var loggedIn=client.send(login,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(loggedIn.body()).get("data").get("redirectTo").asString()).isEqualTo(UnionOAuth.ROOT+"/grant?state=real-http");
            var granted=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(granted.statusCode()).isEqualTo(302);
            String location=granted.headers().firstValue("Location").orElseThrow(); assertThat(location).doesNotContain(owner.email());
            var token=decrypt(query(location).get("userInfoToken"));
            assertThat(json.readTree(Base64.getDecoder().decode(token.get("userInfo").asString())).get("uid").asLong()).isEqualTo(2601);
        }
    }
    private JsonNode decrypt(String token) throws Exception {
        byte[] encrypted=Base64.getDecoder().decode(token); int block=((RSAPublicKey)backend.getPublic()).getModulus().bitLength()/8;
        assertThat(encrypted.length).isGreaterThan(block);
        var cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.DECRYPT_MODE,backend.getPrivate()); var plain=new ByteArrayOutputStream();
        for(int i=0;i<encrypted.length;i+=block) plain.writeBytes(cipher.doFinal(encrypted,i,block));
        return json.readTree(plain.toByteArray());
    }
    private Map<String,String> query(String uri) {
        var values=new LinkedHashMap<String,String>();
        for(String entry:URI.create(uri).getRawQuery().split("&")) { String[] pair=entry.split("=",2); values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8)); }
        return values;
    }
}
