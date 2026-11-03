package top.twinklestars.koharuskin.oauth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.admin.AdminUsersService;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","koharu.union-network-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JanusTokenTests {
    @TempDir static Path directory;
    static KeyPair key,foreignKey;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("koharu.oauth-public-key",()->directory.resolve("oauth-public.pem").toString());
    }
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();foreignKey=generator.generateKeyPair();
        Files.writeString(directory.resolve("oauth-public.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OAuthClients clients;
    @Autowired SiteOptions options;
    @Autowired JsonMapper json;
    @Autowired PlayerService players;
    @Autowired PlayerMapper playerMapper;
    @Autowired ProfileService profiles;
    @Autowired AdminUsersService admin;
    OAuthClient client;
    static final String ISSUER="https://janus.example.invalid",ONE="11111111111141118111111111111111",OTHER="22222222222242228222222222222222";
    record Issued(String id,String jwt,JWTClaimsSet claims) {}
    @BeforeEach void fixture() {
        for(String table:List.of("koharu_player_renames","code_id_to_uuid","oauth_auth_codes","oauth_refresh_tokens","oauth_access_tokens","oauth_clients","uuid","players","users","options"))jdbc.update("DELETE FROM "+table);
        String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password");
        for(int uid=3101;uid<=3103;uid++)jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?, ?,?,1000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",uid,"janus"+uid+"@example.invalid","Janus"+uid,password,uid==3103?2:0);
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(3101,3101,'OwnPlayer',0,0,CURRENT_TIMESTAMP),(3102,3102,'OtherPlayer',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('OwnPlayer',?),('OtherPlayer',?)",ONE,OTHER);
        options.insert("plugins_enabled","[\"yggdrasil-connect\",\"yggdrasil-api\"]");options.insert("ygg_connect_server_url",ISSUER);
        client=clients.create(3101,"Janus 客户端","https://client.example.invalid/callback",true);
    }
    @Test void signedJanusAccessTokenReturnsOnlyGrantedUserInfo() throws Exception {
        var read=issue(Set.of("openid","profile","email",OAuthScopes.READ),null);
        info(read.jwt()).andExpect(status().isOk()).andExpect(jsonPath("$.sub").value("3101"))
                .andExpect(jsonPath("$.email").value("janus3101@example.invalid")).andExpect(jsonPath("$.email_verified").value(true))
                .andExpect(jsonPath("$.availableProfiles.length()").value(1)).andExpect(jsonPath("$.availableProfiles[0].id").value(ONE))
                .andExpect(jsonPath("$.selectedProfile").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
        var selected=issue(Set.of("openid",OAuthScopes.SELECT,OAuthScopes.JOIN),ONE);
        info(selected.jwt()).andExpect(status().isOk()).andExpect(jsonPath("$.selectedProfile.id").value(ONE))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.availableProfiles").doesNotExist());
    }
    @Test void signatureIssuerAudienceSubjectAndTimeAreCheckedBeforeAcceptingDatabaseRow() throws Exception {
        var issued=issue(Set.of("openid"),null);
        info(sign(issued.claims(),foreignKey,JWSAlgorithm.RS256)).andExpect(status().isUnauthorized());
        info(sign(issued.claims(),key,JWSAlgorithm.RS512)).andExpect(status().isUnauthorized());
        List<Consumer<JWTClaimsSet.Builder>> invalid=List.of(builder->builder.issuer("https://forged.example.invalid"),builder->builder.audience("99999"),
                builder->builder.subject("3102"),builder->builder.expirationTime(Date.from(Instant.now().minusSeconds(1))),
                builder->builder.notBeforeTime(Date.from(Instant.now().plusSeconds(60))),builder->builder.issueTime(Date.from(Instant.now().plusSeconds(60))));
        for(var change:invalid) { var builder=new JWTClaimsSet.Builder(issued.claims());change.accept(builder);info(sign(builder.build(),key,JWSAlgorithm.RS256)).andExpect(status().isUnauthorized()); }
        info("invalid.jwt.value").andExpect(status().isUnauthorized());
    }
    @Test void revokedExpiredTokensAndRevokedClientsStopWorkingImmediately() throws Exception {
        var issued=issue(Set.of("openid"),null);info(issued.jwt()).andExpect(status().isOk());
        jdbc.update("UPDATE oauth_access_tokens SET revoked=true WHERE id=?",issued.id());info(issued.jwt()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE oauth_access_tokens SET revoked=false,expires_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),issued.id());info(issued.jwt()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE oauth_access_tokens SET expires_at=? WHERE id=?",LocalDateTime.now().plusHours(1),issued.id());
        clients.revoke(3101,client.id());info(issued.jwt()).andExpect(status().isUnauthorized());
    }
    @Test void signedScopesCannotExceedCurrentDatabasePermissions() throws Exception {
        var issued=issue(Set.of("openid"),null);
        var forgedScope=new JWTClaimsSet.Builder(issued.claims()).claim("scopes",List.of("openid","email")).build();
        info(sign(forgedScope,key,JWSAlgorithm.RS256)).andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist());
        jdbc.update("UPDATE oauth_access_tokens SET scopes='[]' WHERE id=?",issued.id());info(issued.jwt()).andExpect(status().isForbidden());
    }
    @Test void selectedProfilesRequireCurrentOwnershipAndRenamesInvalidateOldTokens() throws Exception {
        info(issue(Set.of("openid",OAuthScopes.SELECT),OTHER).jwt()).andExpect(status().isUnauthorized());
        info(issue(Set.of("openid",OAuthScopes.SELECT),null).jwt()).andExpect(status().isUnauthorized());
        var issued=issue(Set.of("openid",OAuthScopes.SELECT),ONE);info(issued.jwt()).andExpect(status().isOk());
        options.update("plugins_enabled","[]");players.rename(3101,3101,"RenamedPlayer");
        options.update("plugins_enabled","[\"yggdrasil-connect\",\"yggdrasil-api\"]");info(issued.jwt()).andExpect(status().isUnauthorized());
        assertThat(playerMapper.uuid("RenamedPlayer")).isEqualTo(ONE);
        var renewed=new JWTClaimsSet.Builder(issued.claims()).issueTime(Date.from(Instant.now().plusSeconds(2))).build();
        info(sign(renewed,key,JWSAlgorithm.RS256)).andExpect(status().isOk()).andExpect(jsonPath("$.selectedProfile.name").value("RenamedPlayer"));
        jdbc.update("UPDATE players SET uid=3102 WHERE pid=3101");info(sign(renewed,key,JWSAlgorithm.RS256)).andExpect(status().isUnauthorized());
    }
    @Test void credentialChangesRevokeExistingAccessAndRefreshTokensBeforeTheirFirstUse() throws Exception {
        var issued=issue(Set.of("openid"),null);
        jdbc.update("INSERT INTO oauth_refresh_tokens(id,access_token_id,revoked,expires_at) VALUES('synthetic-refresh',?,false,?)",issued.id(),LocalDateTime.now().plusDays(1));
        jdbc.update("INSERT INTO oauth_auth_codes(id,user_id,client_id,scopes,revoked,expires_at) VALUES('janus-grant',3101,?,'[]',true,?)",client.id(),LocalDateTime.now().plusMinutes(5));
        jdbc.update("INSERT INTO code_id_to_uuid(code_id,uuid) VALUES('janus-grant',?)",ONE);
        profiles.password(3101,"test-password","changed-password");info(issued.jwt()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked FROM oauth_refresh_tokens WHERE id='synthetic-refresh'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_auth_codes WHERE id='janus-grant'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM code_id_to_uuid WHERE code_id='janus-grant'",Integer.class)).isZero();
        var beforeEmail=issue(Set.of("openid"),null);profiles.email(3101,"new@example.invalid","changed-password");info(beforeEmail.jwt()).andExpect(status().isUnauthorized());
        var beforeAdmin=issue(Set.of("openid"),null);admin.password(3103,3101,"admin-reset");info(beforeAdmin.jwt()).andExpect(status().isUnauthorized());
    }
    @Test void bansVerificationAndExtensionSwitchTakeEffectWithoutRestart() throws Exception {
        var issued=issue(Set.of("openid"),null);
        jdbc.update("UPDATE users SET permission=-1 WHERE uid=3101");info(issued.jwt()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET permission=0,verified=false WHERE uid=3101");options.insert("require_verification","true");info(issued.jwt()).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET verified=true WHERE uid=3101");info(issued.jwt()).andExpect(status().isOk());
        options.update("plugins_enabled","[]");info(issued.jwt()).andExpect(status().isUnauthorized());
    }
    private Issued issue(Set<String> scopes,String selected) throws Exception {
        String id=UUID.randomUUID().toString(); var now=Instant.now();
        jdbc.update("INSERT INTO oauth_access_tokens(id,user_id,client_id,name,scopes,revoked,created_at,updated_at,expires_at) VALUES(?,3101,?,'Yggdrasil Connect',?,false,?,?,?)",id,client.id(),json.writeValueAsString(scopes),LocalDateTime.now().minusSeconds(5),LocalDateTime.now(),LocalDateTime.now().plusHours(1));
        var claims=new JWTClaimsSet.Builder().jwtID(id).issuer(ISSUER).subject("3101").audience(Long.toString(client.id())).issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(3600))).claim("scopes",new ArrayList<>(scopes)).claim("selectedProfile",selected).build();
        return new Issued(id,sign(claims,key,JWSAlgorithm.RS256),claims);
    }
    private String sign(JWTClaimsSet claims,KeyPair signingKey,JWSAlgorithm algorithm) throws JOSEException {
        var jwt=new SignedJWT(new JWSHeader(algorithm),claims);jwt.sign(new RSASSASigner(signingKey.getPrivate()));return jwt.serialize();
    }
    private ResultActions info(String token) throws Exception { return mvc.perform(get("/yggc/userinfo").header("Authorization","Bearer "+token)); }
}
