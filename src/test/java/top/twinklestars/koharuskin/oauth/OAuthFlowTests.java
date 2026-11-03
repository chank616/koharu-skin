package top.twinklestars.koharuskin.oauth;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.web.util.UriComponentsBuilder;
import top.twinklestars.koharuskin.account.AccountUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthFlowTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired OAuthClients clients;
    @Autowired OAuthAuthorizations authorizations;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    final AccountUser owner=new AccountUser(1901,"oauth1901@example.invalid","Owner","unused",0);
    final AccountUser other=new AccountUser(1902,"oauth1902@example.invalid","Other","unused",0);
    OAuthClient client;
    static final String CALLBACK="https://client.example.invalid/callback";
    record Consent(String state,MockHttpSession session,String csrf) {}

    @BeforeEach void fixture() {
        for(String table:List.of("koharu_oauth_authorizations","koharu_oauth_consents","oauth_refresh_tokens","oauth_auth_codes","oauth_access_tokens","oauth_clients","scopes","notifications","user_closet","textures","uuid","reports","players","users","options"))
            jdbc.update("DELETE FROM "+table);
        for(var user:List.of(owner,other)) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',0,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                user.uid(),user.email(),user.nickname());
        client=clients.create(owner.uid(),"OAuth 测试应用",CALLBACK,true);
    }

    @Test void authorizationCodeConsentAndRefreshUseStoredGrants() throws Exception {
        var consent=start("User.Read");
        String code=approve(consent,"User.Read");
        JsonNode tokens=exchange(code);
        assertThat(tokens.path("access_token").asString()).isNotBlank();
        assertThat(tokens.path("refresh_token").asString()).isNotBlank();
        assertThat(tokens.path("scope").asString()).isEqualTo("User.Read");
        mvc.perform(get("/api/user").header("Authorization","Bearer "+tokens.path("access_token").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uid").value(owner.uid())).andExpect(jsonPath("$.password").doesNotExist());
        var saved=authorizations.findByToken(tokens.path("access_token").asString(),org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
        assertThat(saved.getPrincipalName()).isEqualTo("1901");
        assertThat(saved.getAuthorizedScopes()).containsExactly("User.Read");
        String payload=jdbc.queryForObject("SELECT payload FROM koharu_oauth_authorizations WHERE id=?",String.class,saved.getId());
        assertThat(payload).doesNotContain("unused",owner.email());
        var refreshed=mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").exists()).andReturn();
        assertThat(json.readTree(refreshed.getResponse().getContentAsString()).path("refresh_token").asString()).isNotEqualTo(tokens.path("refresh_token").asString());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+tokens.path("access_token").asString())).andExpect(status().isUnauthorized());
    }

    @Test void scopeChecksDoNotFallBackToBrowserCookiesAndClientRevocationIsImmediate() throws Exception {
        var tokens=exchange(approve(start("User.Read"),"User.Read"));
        String token=tokens.path("access_token").asString();
        mvc.perform(get("/api/user/notifications").with(user(owner)).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/user").with(user(owner)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/user").with(user(owner))).andExpect(status().isOk());
        clients.revoke(owner.uid(),client.id());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test void clientsCanInspectAndRevokeTheirGrantsAndCrossOriginApiPreflightWorks() throws Exception {
        var tokens=exchange(approve(start("User.Read Notification.Read"),"User.Read"));
        String access=tokens.path("access_token").asString(),refresh=tokens.path("refresh_token").asString();
        String basic="Basic "+Base64.getEncoder().encodeToString((client.id()+":"+client.secret()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(post("/oauth/introspect").header("Authorization",basic).param("token",access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.scope").value("User.Read"));
        mvc.perform(post("/oauth/revoke").param("client_id",""+client.id()).param("client_secret","wrong").param("token",refresh))
                .andExpect(status().isUnauthorized());
        var stranger=clients.create(other.uid(),"其他应用",CALLBACK,true);
        mvc.perform(post("/oauth/revoke").param("client_id",""+stranger.id()).param("client_secret",stranger.secret()).param("token",refresh))
                .andExpect(status().is4xxClientError());
        mvc.perform(options("/api/user").header("Origin","https://client.example.invalid").header("Access-Control-Request-Method","GET")
                .header("Access-Control-Request-Headers","authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","*"));
        mvc.perform(post("/oauth/revoke").header("Authorization",basic).param("token",refresh)).andExpect(status().isOk());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth/introspect").header("Authorization",basic).param("token",access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/oauth/revoke").header("Authorization",basic).param("token","already-missing")).andExpect(status().isOk());
    }

    @Test void changedCredentialsBansVerificationAndExpiredTokensRejectAccess() throws Exception {
        var tokens=exchange(approve(start("User.Read"),"User.Read"));
        String token=tokens.path("access_token").asString();
        jdbc.update("UPDATE users SET password='changed' WHERE uid=?",owner.uid());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        jdbc.update("UPDATE users SET password='unused',permission=-1 WHERE uid=?",owner.uid());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET permission=0,verified=false WHERE uid=?",owner.uid());
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('require_verification','true')");
        mvc.perform(get("/api/user").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET verified=true WHERE uid=?",owner.uid());
        var saved=authorizations.findByToken(token,org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN);
        var expired=new org.springframework.security.oauth2.core.OAuth2AccessToken(org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
                token,java.time.Instant.now().minusSeconds(120),java.time.Instant.now().minusSeconds(60),Set.of("User.Read"));
        authorizations.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(saved).token(expired).build());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }

    @Test void adminScopesStillRequireCurrentAdministratorPermission() throws Exception {
        var tokens=exchange(approve(start("Notification.ReadWrite"),"Notification.ReadWrite"));
        String token=tokens.path("access_token").asString();
        String body=json.writeValueAsString(Map.of("receiver","uid","uid",other.uid(),"title","授权测试"));
        mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+token).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET permission=1 WHERE uid=?",owner.uid());
        mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+token).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.count").value(1));
        jdbc.update("UPDATE users SET permission=0 WHERE uid=?",owner.uid());
        mvc.perform(post("/api/admin/notifications").header("Authorization","Bearer "+token).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class)).isOne();
    }

    @Test void codeAndRefreshCanOnlyBeUsedOnceEvenWithConcurrentRequests() throws Exception {
        String code=approve(start("User.Read"),"User.Read");
        var codeResults=race(()->mvc.perform(post("/oauth/token").param("grant_type","authorization_code").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("code",code).param("redirect_uri",CALLBACK)).andReturn());
        assertThat(codeResults.stream().map(r->r.getResponse().getStatus())).containsExactlyInAnyOrder(200,400);
        String replayed=json.readTree(codeResults.stream().filter(r->r.getResponse().getStatus()==200).findFirst().orElseThrow().getResponse().getContentAsString()).path("access_token").asString();
        mvc.perform(get("/api/user").header("Authorization","Bearer "+replayed)).andExpect(status().isUnauthorized());
        // 重放授权码会撤销已兑换的令牌；新授权用于验证刷新竞争。
        jdbc.update("DELETE FROM koharu_oauth_authorizations"); jdbc.update("DELETE FROM koharu_oauth_consents");
        var tokens=exchange(approve(start("User.Read"),"User.Read"));
        var refreshResults=race(()->mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString())).andReturn());
        assertThat(refreshResults.stream().map(r->r.getResponse().getStatus())).containsExactlyInAnyOrder(200,400);
        var winner=refreshResults.stream().filter(r->r.getResponse().getStatus()==200).findFirst().orElseThrow();
        String active=json.readTree(winner.getResponse().getContentAsString()).path("access_token").asString();
        mvc.perform(get("/api/user").header("Authorization","Bearer "+active)).andExpect(status().isOk());
    }

    @Test void pkceProtectsPublicClientsAndRequestedScopesCannotBeExpandedAtConsent() throws Exception {
        client=clients.create(owner.uid(),"公开客户端",CALLBACK,false);
        String verifier="a".repeat(43);
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var consent=start("User.Read",Map.of("code_challenge",challenge,"code_challenge_method","S256"));
        String code=approve(consent,"User.Read");
        mvc.perform(post("/oauth/token").param("grant_type","authorization_code").param("client_id",""+client.id())
                .param("code",code).param("redirect_uri",CALLBACK).param("code_verifier","b".repeat(43)))
                .andExpect(status().is4xxClientError());
        var token=mvc.perform(post("/oauth/token").param("grant_type","authorization_code").param("client_id",""+client.id())
                .param("code",code).param("redirect_uri",CALLBACK).param("code_verifier",verifier))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/user").header("Authorization","Bearer "+json.readTree(token.getResponse().getContentAsString()).path("access_token").asString()))
                .andExpect(status().isOk());
        client=clients.create(owner.uid(),"另一个应用",CALLBACK,true);
        var fresh=start("User.Read");
        mvc.perform(post("/oauth/authorize").session(fresh.session()).header("X-CSRF-TOKEN",fresh.csrf()).param("client_id",""+client.id())
                .param("state",fresh.state()).param("scope","UsersManagement.ReadWrite"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("error=invalid_scope")));
    }

    @Test void loginReturnsToAuthorizationAndMissingScopeUsesLegacyDefault() throws Exception {
        jdbc.update("UPDATE users SET password=? WHERE uid=?",passwords.encode("test-password"),owner.uid());
        var initial=mvc.perform(get("/oauth/authorize").queryParam("response_type","code").queryParam("client_id",""+client.id()).queryParam("redirect_uri",CALLBACK))
                .andExpect(redirectedUrl("/auth/login")).andReturn();
        var session=(MockHttpSession)initial.getRequest().getSession();
        var page=mvc.perform(get("/auth/login").session(session)).andReturn();
        String csrf=((CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName())).getToken();
        var login=mvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN",csrf).contentType("application/json")
                .content(json.writeValueAsString(Map.of("identification",owner.email(),"password","test-password"))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String redirect=json.readTree(login.getResponse().getContentAsString()).path("data").path("redirectTo").asString();
        assertThat(redirect).startsWith("/oauth/authorize?");
        mvc.perform(get(redirect).session(session)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location",org.hamcrest.Matchers.containsString("scope=User.Read")));
        assertThat(session.getAttribute("oauth.returnTo")).isNull();
    }

    @Test void badRedirectsMissingPkceAndExpiredConsentAreRejected() throws Exception {
        mvc.perform(get("/oauth/authorize").with(user(owner)).queryParam("response_type","code").queryParam("client_id",""+client.id())
                .queryParam("redirect_uri","https://evil.example.invalid/callback").queryParam("scope","User.Read"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        var consent=start("User.Read");
        var saved=authorizations.findByToken(consent.state(),new org.springframework.security.oauth2.server.authorization.OAuth2TokenType("state"));
        authorizations.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization.from(saved)
                .attribute("koharu.createdAt",java.time.Instant.now().minusSeconds(601).toString()).build());
        mvc.perform(get("/oauth/consent").session(consent.session()).param("client_id",""+client.id()).param("state",consent.state()))
                .andExpect(status().isBadRequest());
        client=clients.create(owner.uid(),"公开客户端",CALLBACK,false);
        mvc.perform(get("/oauth/authorize").with(user(owner)).queryParam("response_type","code").queryParam("client_id",""+client.id())
                .queryParam("redirect_uri",CALLBACK).queryParam("scope","User.Read"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location",org.hamcrest.Matchers.containsString("error=invalid_request")));
    }

    @Test void consentRequiresOwnerAndCsrfAndDenialReturnsOriginalState() throws Exception {
        var consent=start("User.Read");
        mvc.perform(get("/oauth/consent").with(user(other)).param("client_id",""+client.id()).param("state",consent.state()))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET email=? WHERE uid=?",owner.email(),other.uid());
        var sameEmail=new AccountUser(other.uid(),owner.email(),other.nickname(),other.password(),other.permission());
        mvc.perform(get("/oauth/consent").with(user(sameEmail)).param("client_id",""+client.id()).param("state",consent.state()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/oauth/authorize").session(consent.session()).param("client_id",""+client.id()).param("state",consent.state()).param("scope","User.Read"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/oauth/deny").session(consent.session()).header("X-CSRF-TOKEN",consent.csrf()).param("state",consent.state()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(CALLBACK+"?error=access_denied&state=caller-state"));
        mvc.perform(get("/oauth/consent").session(consent.session()).param("client_id",""+client.id()).param("state",consent.state()))
                .andExpect(status().isBadRequest());
    }

    @Test void denyingARequestWithoutRedirectUriUsesItsSingleRegisteredCallback() throws Exception {
        var response=mvc.perform(get("/oauth/authorize").with(user(owner)).queryParam("response_type","code").queryParam("client_id",""+client.id()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String state=java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(response.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state"),java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post("/oauth/deny").session((MockHttpSession)response.getRequest().getSession()).with(csrf()).param("state",state))
                .andExpect(redirectedUrl(CALLBACK+"?error=access_denied"));
    }

    @Test void usersCanInspectAndRevokeOnlyTheirOwnGrantsWithoutExposingSecrets() throws Exception {
        var tokens=exchange(approve(start("User.Read"),"User.Read"));
        String access=tokens.path("access_token").asString();
        String id=authorizations.findByToken(access,org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN).getId();
        var result=mvc.perform(get("/oauth/tokens").with(user(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].client.name").value(client.name()))
                .andExpect(jsonPath("$[0].client.secret").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(client.secret(),access,tokens.path("refresh_token").asString(),"unused");
        mvc.perform(get("/oauth/tokens").with(user(other))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/oauth/tokens/"+id).with(user(other)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/oauth/tokens/"+id).with(user(owner))).andExpect(status().isForbidden());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(delete("/oauth/tokens/"+id).with(user(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/user").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth/tokens").with(user(owner))).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM koharu_oauth_consents",Integer.class)).isZero();
        start("User.Read"); // 撤销后必须重新确认授权。
    }

    @Test void concurrentRefreshCannotRestoreAUserRevokedGrant() throws Exception {
        var tokens=exchange(approve(start("User.Read"),"User.Read"));
        String id=authorizations.findByToken(tokens.path("access_token").asString(),org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN).getId();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new java.util.concurrent.CountDownLatch(1);
            var revoke=executor.submit(()->{ready.await();return mvc.perform(delete("/oauth/tokens/"+id).with(user(owner)).with(csrf())).andReturn();});
            var refresh=executor.submit(()->{ready.await();return mvc.perform(post("/oauth/token").param("grant_type","refresh_token")
                    .param("client_id",""+client.id()).param("client_secret",client.secret()).param("refresh_token",tokens.path("refresh_token").asString())).andReturn();});
            ready.countDown();
            assertThat(revoke.get().getResponse().getStatus()).isEqualTo(204);
            var response=refresh.get().getResponse();
            assertThat(response.getStatus()).isIn(200,400);
            if(response.getStatus()==200) mvc.perform(get("/api/user").header("Authorization","Bearer "+json.readTree(response.getContentAsString()).path("access_token").asString()))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM koharu_oauth_authorizations WHERE id=?",Integer.class,id)).isZero();
    }

    @Test void legacyPlayerAndClosetApisAcceptJsonAndUrlEncodedBodiesWithScopeIsolation() throws Exception {
        jdbc.update("UPDATE users SET score=1000");
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,upload_at) VALUES(1901,'API skin','alex',?,1,1902,true,CURRENT_TIMESTAMP),(1902,'API cape','cape',?,1,1902,true,CURRENT_TIMESTAMP)","a".repeat(64),"b".repeat(64));
        String token=exchange(approve(start("Player.ReadWrite Closet.ReadWrtie"),"Player.ReadWrite","Closet.ReadWrtie")).path("access_token").asString();
        String bearer="Bearer "+token;
        var added=mvc.perform(post("/api/players").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("name=ApiPlayer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn();
        long pid=json.readTree(added.getResponse().getContentAsString()).path("data").path("pid").asLong();
        mvc.perform(put("/api/players/"+pid+"/name").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("name=RenamedPlayer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("RenamedPlayer"));
        mvc.perform(get("/api/players").header("Authorization",bearer)).andExpect(jsonPath("$[0].pid").value(pid));
        mvc.perform(post("/api/closet").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("tid=1901&name=My+skin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/closet").header("Authorization",bearer).contentType("application/json").content("{\"tid\":1902,\"name\":\"My cape\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/closet/1901").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("name=New+skin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/closet").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].pivot.item_name").value("New skin"));
        mvc.perform(put("/api/players/"+pid+"/textures").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("skin=1901&cape=1902"))
                .andExpect(jsonPath("$.data.tid_skin").value(1901)).andExpect(jsonPath("$.data.tid_cape").value(1902));
        mvc.perform(delete("/api/players/"+pid+"/textures").header("Authorization",bearer).contentType("application/json").content("{\"skin\":false}"))
                .andExpect(jsonPath("$.data.tid_skin").value(0)).andExpect(jsonPath("$.data.tid_cape").value(1902));
        mvc.perform(delete("/api/players/"+pid+"/textures").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("type%5B%5D=cape"))
                .andExpect(jsonPath("$.data.tid_cape").value(0));
        mvc.perform(delete("/api/closet/1901").header("Authorization",bearer)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(delete("/api/players/"+pid).header("Authorization",bearer)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/user").header("Authorization",bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api").header("Authorization","Bearer invalid")).andExpect(status().isOk()).andExpect(jsonPath("$.spec").value(0));
        mvc.perform(post("/api/")).andExpect(status().isOk());
    }

    @Test void adminClosetApiPreservesOriginalNoScoreNoLikesRulesAndRevokesAppliedTextures() throws Exception {
        jdbc.update("UPDATE users SET permission=1 WHERE uid=?",owner.uid());
        jdbc.update("UPDATE users SET permission=2 WHERE uid=?",other.uid());
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(1901,'Private skin','alex',?,1,1901,false,7,CURRENT_TIMESTAMP)","c".repeat(64));
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(1901,1902,'AdminTarget',1901,0,CURRENT_TIMESTAMP)");
        String bearer="Bearer "+exchange(approve(start("ClosetManagement.ReadWrite UsersManagement.Read PlayersManagement.Read ReportsManagement.Read"),
                "ClosetManagement.ReadWrite","UsersManagement.Read","PlayersManagement.Read","ReportsManagement.Read")).path("access_token").asString();
        mvc.perform(post("/api/admin/closet/1902").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("tid=1901"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.user.password").doesNotExist());
        mvc.perform(get("/api/admin/closet/1902").header("Authorization",bearer)).andExpect(jsonPath("$[0].tid").value(1901));
        mvc.perform(get("/api/admin/users").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/admin/players").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/admin/reports").header("Authorization",bearer)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/users/1902/score").header("Authorization",bearer).contentType("application/json").content("{\"score\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/closet/1902").header("Authorization",bearer).contentType("application/x-www-form-urlencoded").content("tid=1901"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        assertThat(jdbc.queryForObject("SELECT tid_skin FROM players WHERE pid=1901",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_closet",Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT score FROM users",Integer.class)).containsOnly(100);
        assertThat(jdbc.queryForObject("SELECT likes FROM textures WHERE tid=1901",Integer.class)).isEqualTo(7);
        jdbc.update("UPDATE users SET permission=0 WHERE uid=?",owner.uid());
        mvc.perform(get("/api/admin/closet/1902").header("Authorization",bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user(owner))).andExpect(status().isForbidden());
    }

    private Consent start(String scope) throws Exception {
        return start(scope,Map.of());
    }
    private Consent start(String scope,Map<String,String> extra) throws Exception {
        var request=get("/oauth/authorize").with(user(owner)).queryParam("response_type","code").queryParam("client_id",""+client.id())
                .queryParam("redirect_uri",CALLBACK).queryParam("scope",scope).queryParam("state","caller-state");
        extra.forEach((key,value)->request.queryParam(key,value));
        var start=mvc.perform(request)
                .andExpect(status().is3xxRedirection()).andReturn();
        String location=start.getResponse().getRedirectedUrl();
        assertThat(java.net.URI.create(location).getPath()).isEqualTo("/oauth/consent");
        String state=java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"),java.nio.charset.StandardCharsets.UTF_8);
        var session=(MockHttpSession)start.getRequest().getSession();
        var page=mvc.perform(get("/oauth/consent").session(session).param("client_id",""+client.id()).param("state",state))
                .andExpect(status().isOk()).andExpect(view().name("auth/oauth-consent")).andReturn();
        return new Consent(state,session,((CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName())).getToken());
    }
    private String approve(Consent consent,String... scopes) throws Exception {
        var approved=mvc.perform(post("/oauth/authorize").session(consent.session()).header("X-CSRF-TOKEN",consent.csrf())
                .param("client_id",""+client.id()).param("state",consent.state()).param("scope",scopes))
                .andExpect(status().is3xxRedirection()).andReturn();
        var params=UriComponentsBuilder.fromUriString(approved.getResponse().getRedirectedUrl()).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("caller-state");
        assertThat(params.getFirst("error")).isNull();
        return java.net.URLDecoder.decode(params.getFirst("code"),java.nio.charset.StandardCharsets.UTF_8);
    }
    private JsonNode exchange(String code) throws Exception {
        var response=mvc.perform(post("/oauth/token").param("grant_type","authorization_code").param("client_id",""+client.id())
                .param("client_secret",client.secret()).param("code",code).param("redirect_uri",CALLBACK))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }
    private List<MvcResult> race(java.util.concurrent.Callable<MvcResult> action) throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<MvcResult> call=()->{start.await();return action.call();};
            var a=executor.submit(call); var b=executor.submit(call); start.countDown();
            return List.of(a.get(),b.get());
        }
    }
}
