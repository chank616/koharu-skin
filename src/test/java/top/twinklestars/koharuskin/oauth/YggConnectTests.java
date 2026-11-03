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
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","server.address=127.0.0.1","koharu.public-url=http://localhost:18080","koharu.union-network-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class YggConnectTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired OAuthClients clients;
    @Autowired OAuthAuthorizations authorizations;
    @Autowired JsonMapper json;
    @Autowired top.twinklestars.koharuskin.yggdrasil.YggKeys keys;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    final AccountUser owner=new AccountUser(2901,"connect2901@example.invalid","游戏用户","unused",0);
    final AccountUser other=new AccountUser(2902,"connect2902@example.invalid","Other","unused",0);
    static final String CALLBACK="http://localhost:18080/yggc/callback",ISSUER="https://oidc.example.invalid";
    static final String ONE="11111111111141118111111111111111",OTHER="22222222222242228222222222222222";
    OAuthClient client;
    record Grant(String code,String id,MockHttpSession session,String state) {}
    @BeforeEach void fixture() {
        for(String table:List.of("koharu_ygg_oauth_tokens","koharu_ygg_sessions","koharu_ygg_tokens","koharu_player_renames","code_id_to_uuid","koharu_oauth_authorizations","koharu_oauth_consents","oauth_refresh_tokens","oauth_auth_codes","oauth_access_tokens","oauth_clients","scopes","uuid","players","users","options"))jdbc.update("DELETE FROM "+table);
        for(var user:List.of(owner,other))jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',0,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",user.uid(),user.email(),user.nickname());
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(2901,2901,'OwnPlayer',0,0,CURRENT_TIMESTAMP),(2902,2902,'OtherPlayer',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('OwnPlayer',?),('OtherPlayer',?)",ONE,OTHER);
        options.insert("plugins_enabled","[\"yggdrasil-connect\",\"yggdrasil-api\"]"); options.insert("ygg_connect_server_url",ISSUER);
        client=clients.create(owner.uid(),"游戏授权应用","https://application.example.invalid/callback",true);
    }
    @Test void selectsOwnProfileBeforeCodeExchangeAndUserInfoRespectsGrantedScopes() throws Exception {
        var grant=grant("openid profile email "+OAuthScopes.SELECT+" "+OAuthScopes.JOIN);
        exchange(grant.code()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        var page=mvc.perform(get("/yggc/callback").with(user(owner)).session(grant.session()).param("code",grant.code()).param("state",grant.state()))
                .andExpect(status().isOk()).andExpect(view().name("auth/ygg-select")).andExpect(header().string("Cache-Control","no-store")).andReturn();
        String html=page.getResponse().getContentAsString();
        assertThat(html).contains("OwnPlayer",ONE,"name=\"_csrf\"").doesNotContain("OtherPlayer",OTHER);
        var selection=(YggConnect.Selection)page.getModelAndView().getModel().get("selection"); assertThat(selection.codeId()).isEqualTo(grant.id());
        var selected=select(grant,ONE,owner).andExpect(status().is3xxRedirection()).andReturn();
        var target=URI.create(selected.getResponse().getRedirectedUrl()); assertThat(target.getHost()).isEqualTo("oidc.example.invalid");
        assertThat(target.getPath()).isEqualTo("/interaction/interaction-state/callback");
        assertThat(query(target.toString(),"code")).isEqualTo(grant.id());
        assertThat(jdbc.queryForObject("SELECT uuid FROM code_id_to_uuid WHERE code_id=?",String.class,grant.id())).isEqualTo(ONE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_auth_codes WHERE id=? AND user_id=2901 AND client_id=? AND revoked=false AND expires_at>CURRENT_TIMESTAMP",Integer.class,grant.id(),client.id())).isOne();
        var tokens=body(exchange(grant.code()).andExpect(status().isOk()));
        String access=tokens.get("access_token").asString();
        mvc.perform(get("/yggc/userinfo").header("Authorization","Bearer "+access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("2901")).andExpect(jsonPath("$.nickname").value(owner.nickname()))
                .andExpect(jsonPath("$.email").value(owner.email())).andExpect(jsonPath("$.email_verified").value(true))
                .andExpect(jsonPath("$.selectedProfile.id").value(ONE)).andExpect(jsonPath("$.availableProfiles").doesNotExist());
        var jwt=com.nimbusds.jwt.SignedJWT.parse(tokens.get("id_token").asString());
        assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier((java.security.interfaces.RSAPublicKey)keys.pair().getPublic()))).isTrue();
        var refreshed=body(mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id()).param("client_secret",client.secret())
                .param("refresh_token",tokens.get("refresh_token").asString())).andExpect(status().isOk()));
        assertThat(refreshed.path("id_token").asString()).isNotBlank();
        mvc.perform(get("/yggc/userinfo").header("Authorization","Bearer "+refreshed.get("access_token").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedProfile.id").value(ONE));
    }
    @Test void readScopeHasNoSelectionAndDoesNotLeakEmailOrOtherUsersProfiles() throws Exception {
        var grant=grant("openid "+OAuthScopes.READ);
        mvc.perform(get("/yggc/callback").with(user(owner)).param("code",grant.code()).param("state",grant.state())).andExpect(status().is3xxRedirection());
        var tokens=body(exchange(grant.code()).andExpect(status().isOk()));
        mvc.perform(get("/yggc/userinfo").header("Authorization","Bearer "+tokens.get("access_token").asString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableProfiles.length()").value(1)).andExpect(jsonPath("$.availableProfiles[0].id").value(ONE))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.nickname").doesNotExist()).andExpect(jsonPath("$.selectedProfile").doesNotExist());
    }
    @Test void foreignProfilesStateTamperingReplayAndMissingCsrfAreRejected() throws Exception {
        var grant=grant("openid "+OAuthScopes.SELECT);
        mvc.perform(get("/yggc/callback").with(user(other)).param("code",grant.code()).param("state",grant.state())).andExpect(status().isForbidden());
        mvc.perform(get("/yggc/callback").with(user(owner)).param("code",grant.code()).param("state","tampered")).andExpect(status().isForbidden());
        select(grant,OTHER,owner).andExpect(status().isForbidden()); select(grant,ONE,other).andExpect(status().isForbidden());
        mvc.perform(post("/yggc/callback").with(user(owner)).param("code_id",grant.id()).param("state",grant.state()).param("selectedProfile",ONE)).andExpect(status().isForbidden());
        select(grant,ONE,owner).andExpect(status().is3xxRedirection()); select(grant,ONE,owner).andExpect(status().isForbidden());
        exchange(grant.code()).andExpect(status().isOk()); exchange(grant.code()).andExpect(status().isBadRequest());
    }
    @Test void cancelRequiresOwnershipAndRevokesCodeWhileGetOnlyShowsConfirmation() throws Exception {
        var grant=grant("openid "+OAuthScopes.SELECT);
        mvc.perform(get("/yggc/cancel").with(user(owner)).param("code_id",grant.id()).param("state",grant.state())).andExpect(status().isOk());
        assertThat(authorizations.findById(grant.id())).isNotNull();
        mvc.perform(post("/yggc/cancel").with(user(other)).with(csrf()).param("code_id",grant.id()).param("state",grant.state())).andExpect(status().isForbidden());
        var cancelled=mvc.perform(post("/yggc/cancel").with(user(owner)).with(csrf()).param("code_id",grant.id()).param("state",grant.state()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(query(cancelled.getResponse().getRedirectedUrl(),"error")).isEqualTo("access_denied");
        assertThat(authorizations.findById(grant.id())).isNull(); exchange(grant.code()).andExpect(status().isBadRequest());
    }
    @Test void scopeRulesDisabledExtensionAndBrowserCookiesCannotBypassAuthorization() throws Exception {
        for(String scope:List.of("profile","openid "+OAuthScopes.JOIN,"openid "+OAuthScopes.SELECT+" "+OAuthScopes.READ))
            mvc.perform(get("/oauth/authorize").with(user(owner)).param("response_type","code").param("client_id",""+client.id()).param("redirect_uri",CALLBACK).param("scope",scope))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        var browser=new MockHttpSession();
        browser.setAttribute("SPRING_SECURITY_CONTEXT",new org.springframework.security.core.context.SecurityContextImpl(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(owner,null,owner.getAuthorities())));
        mvc.perform(get("/yggc/userinfo").session(browser)).andExpect(status().isUnauthorized());
        mvc.perform(get("/yggc/userinfo").with(user(owner)).header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        var grant=grant("openid "+OAuthScopes.READ); var tokens=body(exchange(grant.code()).andExpect(status().isOk()));
        options.update("plugins_enabled","[]");
        mvc.perform(get("/yggc/userinfo").header("Authorization","Bearer "+tokens.get("access_token").asString())).andExpect(status().isNotFound());
        mvc.perform(get("/yggc/callback").with(user(owner)).param("code",grant.code()).param("state",grant.state())).andExpect(status().isNotFound());
    }
    @Test void concurrentSelectionHasExactlyOneWinner() throws Exception {
        var grant=grant("openid "+OAuthScopes.SELECT);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> action=()->{start.await();return select(grant,ONE,owner).andReturn().getResponse().getStatus();};
            var a=executor.submit(action);var b=executor.submit(action);start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(302,403);
        }
    }
    @Test void actualHttpLoginConsentSelectionAndGameJoin() throws Exception {
        jdbc.update("UPDATE users SET password=? WHERE uid=?",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password"),owner.uid());
        try(var http=java.net.http.HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build()) {
            String root="http://127.0.0.1:"+port;
            var loginPage=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            String csrf=org.jsoup.Jsoup.parse(loginPage.body()).selectFirst("meta[name=csrf-token]").attr("content");
            var login=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"identification\":\"connect2901@example.invalid\",\"password\":\"test-password\"}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(login.body()).get("code").asInt()).isZero();
            String authorize=root+"/oauth/authorize?response_type=code&client_id="+client.id()+"&redirect_uri="+URLEncoder.encode(CALLBACK,StandardCharsets.UTF_8)
                    +"&scope="+URLEncoder.encode("openid "+OAuthScopes.SELECT+" "+OAuthScopes.JOIN,StandardCharsets.UTF_8)+"&state=http-test";
            var started=http.send(java.net.http.HttpRequest.newBuilder(URI.create(authorize)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            var consentUri=URI.create(started.headers().firstValue("location").orElseThrow());
            var consentPage=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+consentUri.getRawPath()+"?"+consentUri.getRawQuery())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(consentPage.statusCode()).isEqualTo(200);
            var consent=org.jsoup.Jsoup.parse(consentPage.body()).selectFirst("form[action=/oauth/authorize]");
            var approved=submit(http,root,consent,null);
            var callback=URI.create(approved.headers().firstValue("location").orElseThrow());
            var selectionPage=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+callback.getRawPath()+"?"+callback.getRawQuery())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(selectionPage.statusCode()).isEqualTo(200);
            var selection=org.jsoup.Jsoup.parse(selectionPage.body()).selectFirst("form[action=/yggc/callback]");
            var selected=submit(http,root,selection,ONE); assertThat(selected.statusCode()).isEqualTo(302);
            String id=query(selected.headers().firstValue("location").orElseThrow(),"code");
            assertThat(jdbc.queryForObject("SELECT uuid FROM code_id_to_uuid WHERE code_id=?",String.class,id)).isEqualTo(ONE);
            String form="grant_type=authorization_code&client_id="+client.id()+"&client_secret="+client.secret()+"&redirect_uri="+URLEncoder.encode(CALLBACK,StandardCharsets.UTF_8)
                    +"&code="+URLEncoder.encode(query(callback.toString(),"code"),StandardCharsets.UTF_8);
            var exchanged=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/oauth/token")).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(exchanged.statusCode()).isEqualTo(200);
            String access=json.readTree(exchanged.body()).get("access_token").asString();
            var joined=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/api/yggdrasil/sessionserver/session/minecraft/join")).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("accessToken",access,"selectedProfile",ONE,"serverId","http-game-server"))))
                    .build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(joined.statusCode()).isEqualTo(204);
            var confirmed=http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/api/yggdrasil/sessionserver/session/minecraft/hasJoined?username=OwnPlayer&serverId=http-game-server"))
                    .GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(confirmed.statusCode()).isEqualTo(200);var profile=json.readTree(confirmed.body());
            assertThat(profile.get("id").asString()).isEqualTo(ONE);
            assertThat(profile.get("properties").get(0).get("signature").asString()).isNotBlank();
        }
    }
    private java.net.http.HttpResponse<String> submit(java.net.http.HttpClient http,String root,org.jsoup.nodes.Element form,String uuid) throws Exception {
        var fields=new ArrayList<String>();
        for(var input:form.select("input[name]")) {
            if(input.hasAttr("disabled")||input.attr("type").equals("radio")) continue;
            fields.add(URLEncoder.encode(input.attr("name"),StandardCharsets.UTF_8)+"="+URLEncoder.encode(input.attr("value"),StandardCharsets.UTF_8));
        }
        if(uuid!=null) fields.add("selectedProfile="+uuid);
        return http.send(java.net.http.HttpRequest.newBuilder(URI.create(root+form.attr("action"))).header("Content-Type","application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(String.join("&",fields))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test void oauthGameTokenJoinsAndRefreshInvalidatesPreviouslyJoinedSession() throws Exception {
        var grant=grant("openid "+OAuthScopes.SELECT+" "+OAuthScopes.JOIN);
        select(grant,ONE,owner).andExpect(status().is3xxRedirection());
        var token=body(exchange(grant.code()).andExpect(status().isOk()));String access=token.get("access_token").asString();
        join(access,OTHER,"foreign").andExpect(status().isForbidden());join(access,ONE,"oauth-game").andExpect(status().isNoContent());
        joined("oauth-game").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ONE));
        mvc.perform(post("/api/yggdrasil/authserver/refresh").contentType("application/json").content(json.writeValueAsString(Map.of("accessToken",access))))
                .andExpect(status().isForbidden());
        var renewed=body(mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id()).param("client_secret",client.secret())
                .param("refresh_token",token.get("refresh_token").asString())).andExpect(status().isOk()));
        joined("oauth-game").andExpect(status().isNoContent());join(access,ONE,"old-access").andExpect(status().isForbidden());
        String current=renewed.get("access_token").asString();join(current,ONE,"renewed-game").andExpect(status().isNoContent());
        joined("renewed-game").andExpect(status().isOk());
        mvc.perform(post("/api/yggdrasil/authserver/invalidate").contentType("application/json").content(json.writeValueAsString(Map.of("accessToken",current))))
                .andExpect(status().isNoContent());
        joined("renewed-game").andExpect(status().isNoContent());join(current,ONE,"revoked").andExpect(status().isForbidden());
        mvc.perform(post("/oauth/token").param("grant_type","refresh_token").param("client_id",""+client.id()).param("client_secret",client.secret())
                .param("refresh_token",renewed.get("refresh_token").asString())).andExpect(status().isBadRequest());
    }
    @Test void readOnlyAuthorizationCannotJoinAndRevokingGrantCancelsPendingJoin() throws Exception {
        var read=grant("openid "+OAuthScopes.READ);var readToken=body(exchange(read.code()).andExpect(status().isOk()));
        join(readToken.get("access_token").asString(),ONE,"read-is-not-login").andExpect(status().isForbidden());
        var game=grant("openid "+OAuthScopes.SELECT+" "+OAuthScopes.JOIN);select(game,ONE,owner).andExpect(status().is3xxRedirection());
        String token=body(exchange(game.code()).andExpect(status().isOk())).get("access_token").asString();
        join(token,ONE,"grant-revoked").andExpect(status().isNoContent());
        mvc.perform(delete("/oauth/tokens/"+game.id()).with(user(owner)).with(csrf())).andExpect(status().isNoContent());
        joined("grant-revoked").andExpect(status().isNoContent());
    }
    private ResultActions join(String token,String uuid,String server) throws Exception {
        return mvc.perform(post("/api/yggdrasil/sessionserver/session/minecraft/join").contentType("application/json")
                .content(json.writeValueAsString(Map.of("accessToken",token,"selectedProfile",uuid,"serverId",server))));
    }
    private ResultActions joined(String server) throws Exception {
        return mvc.perform(get("/api/yggdrasil/sessionserver/session/minecraft/hasJoined").param("username","OwnPlayer").param("serverId",server));
    }

    private Grant grant(String scopes) throws Exception {
        var result=mvc.perform(get("/oauth/authorize").with(user(owner)).queryParam("response_type","code").queryParam("client_id",""+client.id())
                .queryParam("redirect_uri",CALLBACK).queryParam("scope",scopes).queryParam("state","interaction-state")).andExpect(status().is3xxRedirection()).andReturn();
        String pending=query(result.getResponse().getRedirectedUrl(),"state");
        var session=(MockHttpSession)result.getRequest().getSession();
        var page=mvc.perform(get("/oauth/consent").session(session).param("client_id",""+client.id()).param("state",pending)).andExpect(status().isOk()).andReturn();
        String csrf=((CsrfToken)page.getRequest().getAttribute(CsrfToken.class.getName())).getToken();
        var approved=mvc.perform(post("/oauth/authorize").session(session).header("X-CSRF-TOKEN",csrf).param("client_id",""+client.id()).param("state",pending).param("scope",scopes.split(" ")))
                .andExpect(status().is3xxRedirection()).andReturn();
        String code=query(approved.getResponse().getRedirectedUrl(),"code"); assertThat(code).isNotBlank();
        String id=jdbc.queryForObject("SELECT id FROM koharu_oauth_authorizations WHERE code_hash=?",String.class,OAuthAuthorizations.hash(code));
        return new Grant(code,id,session,"interaction-state");
    }
    private ResultActions select(Grant grant,String uuid,AccountUser user) throws Exception {
        return mvc.perform(post("/yggc/callback").with(user(user)).with(csrf()).param("code_id",grant.id()).param("state",grant.state()).param("selectedProfile",uuid));
    }
    private ResultActions exchange(String code) throws Exception {
        return mvc.perform(post("/oauth/token").param("grant_type","authorization_code").param("client_id",""+client.id()).param("client_secret",client.secret()).param("code",code).param("redirect_uri",CALLBACK));
    }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    private static String query(String url,String name) { String value=UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);return value==null?null:URLDecoder.decode(value,StandardCharsets.UTF_8); }
}
