package top.twinklestars.koharuskin.yggdrasil;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.texture.TextureMapper;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class YggTests {
    static final String ROOT="/api/yggdrasil";
    static final String ONE="11111111111141118111111111111111";
    static final String TWO="22222222222242228222222222222222";
    static final String OTHER="33333333333343338333333333333333";
    static KeyPair keyPair;
    static final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("koharu.textures",() -> storage.toString());
        registry.add("koharu.public-url",() -> "http://localhost:18080");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PlayerService playerService;
    @Autowired PlayerMapper players;
    @Autowired TextureMapper textures;
    @Autowired SiteOptions options;
    @Autowired YggKeys keys;
    @MockitoBean Clock clock;
    Instant time;

    @BeforeAll static void key() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(4096); keyPair=generator.generateKeyPair();
    }
    @BeforeEach void fixture() {
        time=Instant.parse("2026-11-03T06:00:00Z").plusSeconds(sequence.incrementAndGet()*3600); when(clock.instant()).thenReturn(time);
        for (String table:List.of("koharu_ygg_sessions","koharu_ygg_tokens","user_closet","textures","uuid","players","users","options")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('plugins_enabled','[{\"name\":\"yggdrasil-api\",\"version\":\"5.2.2\"}]'),('ygg_rate_limit','0'),('ygg_token_expire_1','600'),('ygg_token_expire_2','1200'),('require_verification','true'),('score_per_closet_item','2')");
        options.insert("ygg_private_key",YggKeys.pem("PRIVATE KEY",keyPair.getPrivate().getEncoded()));
        var encoder=new BCryptPasswordEncoder(4);
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,permission,verified,register_at,last_sign_at) VALUES(1,'one@example.invalid','One',?,10000,'127.0.0.1',0,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'other@example.invalid','Other',?,10000,'127.0.0.1',0,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",encoder.encode("test-password"),encoder.encode("test-password"));
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(101,1,'PlayerOne',0,0,CURRENT_TIMESTAMP),(102,1,'PlayerTwo',0,0,CURRENT_TIMESTAMP),(201,2,'OtherPlayer',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('PlayerOne',?),('PlayerTwo',?),('OtherPlayer',?)",ONE,TWO,OTHER);
    }

    @Test void authenticatesOldPasswordAndKeepsProfilesAndClientToken() throws Exception {
        var result=login("one@example.invalid");
        assertThat(result.get("clientToken").asString()).isEqualTo("arbitrary client token");
        assertThat(result.get("availableProfiles").size()).isEqualTo(2);
        assertThat(result.has("selectedProfile")).isFalse();
        assertThat(result.at("/availableProfiles/0/id").asString()).isEqualTo(ONE);
        assertThat(result.at("/user/id").asString()).matches("[a-f0-9]{32}");
        var named=login("playerone");
        assertThat(named.at("/selectedProfile/id").asString()).isEqualTo(ONE);
        var single=login("other@example.invalid");
        assertThat(single.at("/selectedProfile/id").asString()).isEqualTo(OTHER);
        assertThat(YggAuth.userId("www.widgets.com")).isEqualTo("21f7f8de80515b8986800195ef798b6a");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM koharu_ygg_tokens WHERE player_pid=101",String.class)).isNotEqualTo(token(named));
        mvc.perform(post(ROOT+"/authserver/authenticate").contentType("application/json").content("{\"username\":\"PlayerOne\",\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("ForbiddenOperationException"));
    }

    @Test void refreshSelectsOnlyOwnedProfilesAndAtomicallyRevokesOldToken() throws Exception {
        String access=token(login("one@example.invalid"));
        postJson("/authserver/refresh",Map.of("accessToken",access,"clientToken","wrong")).andExpect(status().isForbidden());
        postJson("/authserver/refresh",Map.of("accessToken",access,"selectedProfile",Map.of("id",OTHER,"name","OtherPlayer"))).andExpect(status().isForbidden());
        postJson("/authserver/validate",Map.of("accessToken",access)).andExpect(status().isNoContent());
        var renewed=body(postJson("/authserver/refresh",Map.of("accessToken",access,"selectedProfile",Map.of("id",ONE,"name","PlayerOne"),"requestUser",true)).andExpect(status().isOk()));
        assertThat(renewed.at("/selectedProfile/id").asString()).isEqualTo(ONE);
        assertThat(token(renewed)).isNotEqualTo(access);
        postJson("/authserver/validate",Map.of("accessToken",access)).andExpect(status().isForbidden());
        postJson("/authserver/refresh",Map.of("accessToken",token(renewed),"selectedProfile",Map.of("id",TWO,"name","PlayerTwo"))).andExpect(status().isBadRequest());
        postJson("/authserver/validate",Map.of("accessToken",token(renewed))).andExpect(status().isNoContent());
    }

    @Test void onlyOneConcurrentRefreshCanSucceed() throws Exception {
        String access=token(login("PlayerOne"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Integer> refresh=() -> { start.await(); return postJson("/authserver/refresh",Map.of("accessToken",access)).andReturn().getResponse().getStatus(); };
            var one=executor.submit(refresh); var two=executor.submit(refresh); start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,403);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM koharu_ygg_tokens",Integer.class)).isOne();
    }

    @Test void expirationAndRenameRequireRefreshWithoutChangingPlayerUuid() throws Exception {
        options.update("ygg_token_expire_1","2"); options.update("ygg_token_expire_2","10");
        String access=token(login("PlayerOne"));
        advance(3);
        postJson("/authserver/validate",Map.of("accessToken",access)).andExpect(status().isForbidden());
        var refreshed=body(postJson("/authserver/refresh",Map.of("accessToken",access)).andExpect(status().isOk()));
        access=token(refreshed);
        playerService.rename(1,101,"RenamedPlayer");
        postJson("/authserver/validate",Map.of("accessToken",access)).andExpect(status().isForbidden());
        refreshed=body(postJson("/authserver/refresh",Map.of("accessToken",access)).andExpect(status().isOk()));
        assertThat(refreshed.at("/selectedProfile/id").asString()).isEqualTo(ONE);
        assertThat(refreshed.at("/selectedProfile/name").asString()).isEqualTo("RenamedPlayer");
        advance(11);
        postJson("/authserver/refresh",Map.of("accessToken",token(refreshed))).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings={"password","email","ban","unverified","transfer","delete"})
    void accountAndOwnershipChangesRevokeTokens(String change) throws Exception {
        String access=token(login("PlayerOne"));
        switch(change) {
            case "password" -> jdbc.update("UPDATE users SET password='changed' WHERE uid=1");
            case "email" -> jdbc.update("UPDATE users SET email='changed@example.invalid' WHERE uid=1");
            case "ban" -> jdbc.update("UPDATE users SET permission=-1 WHERE uid=1");
            case "unverified" -> jdbc.update("UPDATE users SET verified=false WHERE uid=1");
            case "transfer" -> jdbc.update("UPDATE players SET uid=2 WHERE pid=101");
            case "delete" -> jdbc.update("DELETE FROM players WHERE pid=101");
        }
        postJson("/authserver/validate",Map.of("accessToken",access)).andExpect(status().isForbidden());
        postJson("/authserver/refresh",Map.of("accessToken",access)).andExpect(status().isForbidden());
    }

    @Test void invalidateIsIdempotentAndSignoutRevokesAllAccountTokens() throws Exception {
        String first=token(login("PlayerOne")); String second=token(login("PlayerTwo")); String other=token(login("OtherPlayer"));
        postJson("/authserver/invalidate",Map.of("accessToken",first,"clientToken","ignored")).andExpect(status().isNoContent());
        postJson("/authserver/invalidate",Map.of("accessToken",first)).andExpect(status().isNoContent());
        postJson("/authserver/validate",Map.of("accessToken",first)).andExpect(status().isForbidden());
        postJson("/authserver/signout",Map.of("username","PlayerOne","password","test-password")).andExpect(status().isNoContent());
        postJson("/authserver/validate",Map.of("accessToken",second)).andExpect(status().isForbidden());
        postJson("/authserver/validate",Map.of("accessToken",other)).andExpect(status().isNoContent());
    }

    @Test void joinRequiresBoundProfileAndHasJoinedChecksIpSignatureAndExpiration() throws Exception {
        String access=token(login("PlayerOne"));
        postJson("/sessionserver/session/minecraft/join",Map.of("accessToken",access,"selectedProfile",OTHER,"serverId","server-id")).andExpect(status().isForbidden());
        postJson("/sessionserver/session/minecraft/join",Map.of("accessToken",access,"selectedProfile",ONE,"serverId","server-id")).andExpect(status().isNoContent());
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/hasJoined").param("username","PlayerOne").param("serverId","server-id").param("ip","192.0.2.1")).andExpect(status().isNoContent());
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/hasJoined").param("username","OtherPlayer").param("serverId","server-id")).andExpect(status().isNoContent());
        var profile=body(mvc.perform(get(ROOT+"/sessionserver/session/minecraft/hasJoined").param("username","PlayerOne").param("serverId","server-id").param("ip","127.0.0.1")).andExpect(status().isOk()));
        assertThat(profile.get("id").asString()).isEqualTo(ONE);
        for(var property:profile.get("properties")) {
            var signature=Signature.getInstance("SHA1withRSA"); signature.initVerify(keyPair.getPublic()); signature.update(property.get("value").asString().getBytes(StandardCharsets.UTF_8));
            assertThat(signature.verify(Base64.getDecoder().decode(property.get("signature").asString()))).isTrue();
        }
        advance(121);
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/hasJoined").param("username","PlayerOne").param("serverId","server-id")).andExpect(status().isNoContent());
    }

    @Test void profileLookupAndMetadataUseConfiguredHostAndPreserveSigningKey() throws Exception {
        var metadata=body(mvc.perform(get(ROOT).header("Host","attacker.invalid")).andExpect(status().isOk()));
        assertThat(metadata.at("/meta/links/homepage").asString()).isEqualTo("http://localhost:18080");
        assertThat(metadata.at("/skinDomains/0").asString()).isEqualTo("localhost");
        String publicPem=metadata.get("signaturePublickey").asString();
        var publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicPem.replaceAll("-----[^-]+-----","").replaceAll("\\s",""))));
        assertThat(publicKey.getEncoded()).isEqualTo(keyPair.getPublic().getEncoded());
        assertThat(new YggKeys(options).pair().getPublic().getEncoded()).isEqualTo(publicKey.getEncoded());
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/profile/"+ONE)).andExpect(jsonPath("$.properties[0].signature").doesNotExist());
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/profile/"+ONE).param("unsigned","false")).andExpect(jsonPath("$.properties[0].signature").isString());
        mvc.perform(get(ROOT+"/sessionserver/session/minecraft/profile/"+"0".repeat(32))).andExpect(status().isNoContent());
        postJson("/api/profiles/minecraft",List.of("playerone","PlayerTwo","Missing")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/")).andExpect(header().string("X-Authlib-Injector-API-Location","http://localhost:18080/api/yggdrasil/"));
    }

    @Test void bearerUploadStoresPrivateSkinAndModelWithoutOverwritingAnotherPlayersTexture() throws Exception {
        String access=token(login("PlayerOne")); String other=token(login("OtherPlayer"));
        String path=ROOT+"/api/user/profile/"+ONE+"/skin";
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path).file(png()).param("model","slim"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path).file(png()).header("Authorization","Bearer "+other))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path).file(png()).param("model","slim").header("Authorization","Bearer "+access))
                .andExpect(status().isNoContent());
        int alex=players.find(101).tidSkin(); var texture=textures.find(alex);
        assertThat(texture.visible()).isFalse(); assertThat(texture.type()).isEqualTo("alex");
        assertThat(players.inCloset(1,alex)).isOne(); assertThat(Files.isRegularFile(storage.resolve(texture.hash()))).isTrue();
        int score=players.score(1);
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path).file(png()).param("model","slim").header("Authorization","Bearer "+access)).andExpect(status().isNoContent());
        assertThat(players.score(1)).isEqualTo(score);
        var profile=body(mvc.perform(get(ROOT+"/sessionserver/session/minecraft/profile/"+ONE).param("unsigned","false")).andExpect(status().isOk()));
        var payload=json.readTree(Base64.getDecoder().decode(profile.at("/properties/0/value").asString()));
        assertThat(payload.at("/textures/SKIN/metadata/model").asString()).isEqualTo("slim");
        mvc.perform(get("/textures/"+texture.hash())).andExpect(content().bytes(Files.readAllBytes(storage.resolve(texture.hash()))));
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path).file(png()).header("Authorization","Bearer "+access)).andExpect(status().isNoContent());
        assertThat(players.find(101).tidSkin()).isNotEqualTo(alex);
        assertThat(textures.find(alex).type()).isEqualTo("alex");
        mvc.perform(delete(path).header("Authorization","Bearer "+access)).andExpect(status().isNoContent());
        assertThat(players.find(101).tidSkin()).isZero();
    }

    @Test void rateLimitCoversAllAliasesAndTokenLimitRevokesOldest() throws Exception {
        options.insert("ygg_tokens_limit","2");
        String one=token(login("PlayerOne")); String two=token(login("PlayerTwo")); String three=token(login("one@example.invalid"));
        postJson("/authserver/validate",Map.of("accessToken",one)).andExpect(status().isForbidden());
        postJson("/authserver/validate",Map.of("accessToken",two)).andExpect(status().isNoContent());
        postJson("/authserver/validate",Map.of("accessToken",three)).andExpect(status().isNoContent());
        options.update("ygg_rate_limit","1000"); advance(2);
        login("PlayerOne");
        postJson("/authserver/authenticate",Map.of("username","PlayerTwo","password","test-password")).andExpect(status().isForbidden());
        postJson("/authserver/signout",Map.of("username","one@example.invalid","password","test-password")).andExpect(status().isForbidden());
    }

    @Test void restoreSignsEveryOriginalValueWithoutChangingProfileOrRotatingKeys() throws Exception {
        options.insert("ygg_restore_api","true");
        String first=Base64.getEncoder().encodeToString("{\"textures\":{},\"profileName\":\"测试\"}".getBytes(StandardCharsets.UTF_8));
        var input=Map.of("id",OTHER,"name","RemotePlayer","extra",Map.of("keep",true),"properties",List.of(
                Map.of("name","textures","value",first,"signature","old-signature"),Map.of("name","custom","value","另一个值")));
        var restored=body(postJson("/restore",input).andExpect(status().isOk()));
        assertThat(restored.get("id").asString()).isEqualTo(OTHER); assertThat(restored.get("name").asString()).isEqualTo("RemotePlayer");
        assertThat(restored.at("/extra/keep").asBoolean()).isTrue();
        assertThat(restored.at("/properties/0/value").asString()).isEqualTo(first);
        for(var property:restored.get("properties")) {
            var verifier=Signature.getInstance("SHA1withRSA"); verifier.initVerify(keyPair.getPublic());
            verifier.update(property.get("value").asString().getBytes(StandardCharsets.UTF_8));
            assertThat(verifier.verify(Base64.getDecoder().decode(property.get("signature").asString()))).isTrue();
        }
        assertThat(options.get("ygg_private_key")).isEqualTo(YggKeys.pem("PRIVATE KEY",keyPair.getPrivate().getEncoded()));
        assertThat(players.find(201).name()).isEqualTo("OtherPlayer");
    }

    @Test void restoreRequiresExplicitOptInAndRejectsMalformedProperties() throws Exception {
        mvc.perform(get(ROOT+"/restore")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"));
        postJson("/restore",Map.of("properties",List.of())).andExpect(status().isForbidden());
        options.insert("ygg_restore_api","true");
        for(Object input:List.of(Map.of(),Map.of("properties","invalid"),Map.of("properties",List.of(Map.of("value",7))),Map.of("properties",List.of("invalid"))))
            postJson("/restore",input).andExpect(status().isBadRequest());
        postJson("/restore",Map.of("properties",List.of())).andExpect(status().isOk()).andExpect(jsonPath("$.properties").isEmpty());
        options.update("plugins_enabled","[]");
        mvc.perform(get(ROOT+"/restore")).andExpect(status().isNotFound());
        postJson("/restore",Map.of("properties",List.of())).andExpect(status().isNotFound());
    }

    @Test void minecraftServicesLookupAliasesKeepLegacyProfilesAndQueryLimits() throws Exception {
        String single="/minecraftservices/minecraft/profile/lookup/name/",batch="/minecraftservices/minecraft/profile/lookup/bulk/byname";
        var old=body(mvc.perform(get(ROOT+"/api/users/profiles/minecraft/PlayerOne")).andExpect(status().isOk()));
        var modern=body(mvc.perform(get(ROOT+single+"PlayerOne")).andExpect(status().isOk()));
        assertThat(modern).isEqualTo(old);
        mvc.perform(get(ROOT+single+"MissingPlayer")).andExpect(status().isNoContent());
        var names=List.of("PlayerOne","PlayerOne","PlayerTwo","MissingPlayer");
        assertThat(body(postJson(batch,names).andExpect(status().isOk())))
                .isEqualTo(body(postJson("/api/profiles/minecraft",names).andExpect(status().isOk())));
        options.insert("ygg_search_profile_max","1");
        postJson(batch,List.of("PlayerOne","PlayerTwo")).andExpect(status().isBadRequest());
        options.update("plugins_enabled","[]");
        mvc.perform(get(ROOT+single+"PlayerOne")).andExpect(status().isNotFound());
        postJson(batch,List.of("PlayerOne")).andExpect(status().isNotFound());
    }

    @Test void disabledExtensionAndInvalidInputCannotBypassProtocolAuthentication() throws Exception {
        mvc.perform(post(ROOT+"/authserver/authenticate").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("IllegalArgumentException"));
        mvc.perform(options(ROOT).header("Origin","http://launcher.invalid").header("Access-Control-Request-Method","GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","*"));
        options.update("plugins_enabled","[]");
        mvc.perform(get(ROOT)).andExpect(status().isNotFound());
        postJson("/authserver/authenticate",Map.of("username","PlayerOne","password","test-password")).andExpect(status().isNotFound());
    }

    private JsonNode login(String username) throws Exception {
        time=time.plusMillis(1); when(clock.instant()).thenReturn(time);
        return body(postJson("/authserver/authenticate",Map.of("username",username,"password","test-password","clientToken","arbitrary client token","requestUser",true,"agent",Map.of("name","Minecraft","version",1))).andExpect(status().isOk()));
    }
    private String token(JsonNode response) { return response.get("accessToken").asString(); }
    private void advance(long seconds) { time=time.plusSeconds(seconds); when(clock.instant()).thenReturn(time); }
    private org.springframework.test.web.servlet.ResultActions postJson(String path,Object body) throws Exception {
        return mvc.perform(post(ROOT+path).contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    private MockMultipartFile png() throws Exception {
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB); image.setRGB(8,8,0xff336699);
        var output=new ByteArrayOutputStream(); ImageIO.write(image,"png",output);
        return new MockMultipartFile("file","GameSkin.png","image/png",output.toByteArray());
    }
}
