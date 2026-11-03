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
        for(String table:List.of("koharu_oauth_authorizations","koharu_oauth_consents","oauth_refresh_tokens","oauth_auth_codes","oauth_access_tokens","oauth_clients","scopes","notifications","players","users","options"))
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
