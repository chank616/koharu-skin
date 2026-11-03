package top.twinklestars.koharuskin.yggdrasil;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
class UnionProfilesTests {
    static HttpServer upstream;
    static final Map<String,String> replies=new ConcurrentHashMap<>();
    static final List<String> requests=new CopyOnWriteArrayList<>();
    static final AtomicReference<String> sentBody=new AtomicReference<>(),sentKey=new AtomicReference<>();
    static final AtomicInteger responseStatus=new AtomicInteger(200);
    static final String OWN="a".repeat(32),FOREIGN="b".repeat(32),MAPPED="c".repeat(32),BOUND="d".repeat(32),BOUND_MAPPED="e".repeat(32);
    @DynamicPropertySource static void server(DynamicPropertyRegistry registry) throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/union",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getRawPath());
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            sentKey.set(exchange.getRequestHeaders().getFirst("X-Union-Member-Key"));
            byte[] bytes=replies.getOrDefault(exchange.getRequestURI().getPath(),"{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(responseStatus.get(),bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); upstream.start();
    }
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AccountMapper accounts;
    @Autowired JsonMapper json;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    AccountUser owner,other;
    Map<String,Object> own,bound,duplicate;
    @BeforeEach void fixture() {
        for(String table:List.of("players","uuid","users","options"))jdbc.update("DELETE FROM "+table);
        options.insert("register_with_player_name","false"); options.insert("plugins_enabled","[\"yggdrasil-connect\"]");
        options.insert("union_api_root","http://127.0.0.1:"+upstream.getAddress().getPort()+"/union");
        options.insert("union_member_key","synthetic-member-secret");
        options.insert("union_server_list","[{\"code\":\"MUA\",\"bs_root\":\"https://example.invalid\"}]");
        for(int uid=2501;uid<=2502;uid++) {
            jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?,'unused',0,321,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,"union"+uid+"@example.invalid","Union"+uid);
            jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(?,?,?,0,0,CURRENT_TIMESTAMP)",uid,uid,"Union"+uid);
            jdbc.update("INSERT INTO uuid(name,uuid) VALUES(?,?)","Union"+uid,uid==2501?OWN:FOREIGN);
        }
        owner=accounts.findById(2501); other=accounts.findById(2502);
        own=remote("self",OWN,MAPPED,"MappedSelf",0); bound=remote("bound",BOUND,BOUND_MAPPED,"BoundName",1); duplicate=remote("duplicate",FOREIGN,FOREIGN,"DuplicateName",0);
        own.put("bind",List.of(bound));
        replies.clear(); requests.clear(); responseStatus.set(200); sentBody.set(""); sentKey.set("");
        replies.put("/union/profile/detail/"+OWN,json.writeValueAsString(own));
        replies.put("/union/profile/unmapped/byname/Union2501",json.writeValueAsString(List.of(own,bound,duplicate)));
        replies.put("/union/profile/bind","{\"token\":\"synthetic-binding-token\"}");
        replies.put("/union/profile/bindto","{}"); replies.put("/union/profile/unbind","{}"); replies.put("/union/profile/remapuuid","{}");
        replies.put("/union/code","{\"code\":\"MUA local\"}"); replies.put("/union/backend/MUA local/security/level","3");
        replies.put("/union","{\"meta\":{\"description\":\"**介绍** <script>alert(1)</script><a href='javascript:alert(1)'>链接</a>\"}}");
    }
    @Test void userPageShowsOwnProfileBindingsAndDistinctNameConflictsWithoutSecrets() throws Exception {
        var page=mvc.perform(get("/union").with(user(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("MappedSelf","BoundName","DuplicateName","/union/bindto","/union/remapuuid","/official/union.js","name=\"_csrf\"")
                .doesNotContain("synthetic-member-secret","union2502@example.invalid");
        assertThat(requests).containsExactly("GET /union/profile/detail/"+OWN,"GET /union/profile/unmapped/byname/Union2501");
        var document=org.jsoup.Jsoup.parse(page);
        assertThat(document.select("section.card ul li")).hasSize(1);
        own.put("bind_mapped_name","<script>bad()</script>"); replies.put("/union/profile/detail/"+OWN,json.writeValueAsString(own));
        var escaped=mvc.perform(get("/union").with(user(owner))).andReturn().getResponse().getContentAsString();
        assertThat(escaped).contains("&lt;script&gt;").doesNotContain("<script>bad()");
    }
    @Test void bindingSupportsJsonAndFormAndDoesNotChangeLocalUuids() throws Exception {
        mvc.perform(post("/union/bind").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("uuid",OWN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value("synthetic-binding-token"));
        assertThat(sentKey.get()).isEqualTo("synthetic-member-secret");
        mvc.perform(post("/union/bindto").with(user(owner)).with(csrf().asHeader()).contentType("application/x-www-form-urlencoded")
                .content("uuid="+OWN+"&token=opaque%2Btoken%26value&_csrf=ignored-form-value"))
                .andExpect(status().isOk());
        assertThat(json.readTree(sentBody.get()).get("token").asString()).isEqualTo("opaque+token&value");
        mvc.perform(post("/union/unbind").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("uuid",OWN))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Union2501'",String.class)).isEqualTo(OWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players",Integer.class)).isEqualTo(2);
    }
    @Test void guestsCsrfFailuresForeignRolesAndBannedUsersCannotSendBindingRequests() throws Exception {
        String input=json.writeValueAsString(Map.of("uuid",OWN,"token","token","me",OWN,"target",MAPPED));
        for(String action:List.of("bind","bindto","unbind","remapuuid")) {
            mvc.perform(post("/union/"+action).with(csrf()).accept("application/json").contentType("application/json").content(input)).andExpect(status().isUnauthorized());
            mvc.perform(post("/union/"+action).with(user(owner)).contentType("application/json").content(input)).andExpect(status().isForbidden());
            mvc.perform(post("/union/"+action).with(user(other)).with(csrf()).contentType("application/json").content(input)).andExpect(status().isForbidden());
        }
        jdbc.update("UPDATE users SET permission=-1 WHERE uid=2501");
        mvc.perform(post("/union/bind").with(user(owner)).with(csrf()).accept("application/json").contentType("application/json").content(input)).andExpect(status().isUnauthorized());
        assertThat(requests).isEmpty();
    }
    @Test void remapOnlyAcceptsRelatedDestinationsAndLeavesChangesToSignedCallback() throws Exception {
        mvc.perform(post("/union/remapuuid").with(user(owner)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("me",OWN,"target",FOREIGN)))).andExpect(status().isBadRequest());
        assertThat(requests).doesNotContain("POST /union/profile/remapuuid");
        mvc.perform(post("/union/remapuuid").with(user(owner)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("me",OWN,"target",BOUND_MAPPED)))).andExpect(status().isOk());
        assertThat(json.readTree(sentBody.get()).get("target").asString()).isEqualTo(BOUND_MAPPED);
        assertThat(jdbc.queryForObject("SELECT uuid FROM uuid WHERE name='Union2501'",String.class)).isEqualTo(OWN);
    }
    @Test void descriptionIsSanitizedSecurityLevelUsesMemberCredentialsAndFailuresAreVisible() throws Exception {
        var response=mvc.perform(get("/union/description").with(user(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String html=json.readTree(response).get("html").asString();
        assertThat(html).contains("<strong>介绍</strong>").doesNotContain("<script","javascript:");
        mvc.perform(get("/union/security/level").with(user(owner))).andExpect(status().isOk()).andExpect(content().string("3"));
        assertThat(requests).contains("POST /union/code","GET /union/backend/MUA%20local/security/level");
        responseStatus.set(503);
        mvc.perform(post("/union/bind").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("uuid",OWN))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("联盟服务请求失败"));
        var page=mvc.perform(get("/union").with(user(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("联盟服务请求失败");
    }
    @Test void disabledExtensionAndUnboundEmailPreventAccess() throws Exception {
        options.update("plugins_enabled","[]");
        mvc.perform(get("/union").with(user(owner))).andExpect(status().isNotFound());
        mvc.perform(post("/union/bind").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("uuid",OWN))))
                .andExpect(status().isNotFound());
        options.update("plugins_enabled","[\"yggdrasil-connect\"]"); jdbc.update("UPDATE users SET email='' WHERE uid=2501");
        mvc.perform(get("/union").with(user(accounts.findById(2501)))).andExpect(redirectedUrl("/auth/bind"));
        assertThat(requests).isEmpty();
    }
    @Test void actualHttpLoginPageAndUrlencodedBindingWorkTogether() throws Exception {
        jdbc.update("UPDATE users SET password=? WHERE uid=2501",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password"));
        var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
        try(var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
            String root="http://127.0.0.1:"+port;
            var loginPage=client.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(loginPage.statusCode()).isEqualTo(200);
            String csrf=org.jsoup.Jsoup.parse(loginPage.body()).selectFirst("meta[name=csrf-token]").attr("content");
            var login=java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"identification\":\"union2501@example.invalid\",\"password\":\"test-password\"}")).build();
            var loggedIn=client.send(login,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(loggedIn.body()).path("code").asInt()).isZero();
            var page=client.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/union")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            var document=org.jsoup.Jsoup.parse(page.body()); csrf=document.selectFirst("meta[name=csrf-token]").attr("content");
            var form=document.selectFirst("form[action=/union/bindto]");
            String formBody="uuid="+form.selectFirst("input[name=uuid]").attr("value")+"&token=actual%2Bbinding%26token&_csrf="+URLEncoder.encode(form.selectFirst("input[name=_csrf]").attr("value"),StandardCharsets.UTF_8);
            var submission=java.net.http.HttpRequest.newBuilder(URI.create(root+form.attr("action"))).header("Accept","application/json")
                    .header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody)).build();
            var response=client.send(submission,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(requests).contains("POST /union/profile/bindto");
            assertThat(json.readTree(sentBody.get()).get("token").asString()).isEqualTo("actual+binding&token");
            var script=client.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/official/union.js")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(script.statusCode()).isEqualTo(200); assertThat(script.body()).contains("X-CSRF-TOKEN");
        }
    }
    private Map<String,Object> remote(String id,String uuid,String mapped,String name,int state) {
        return new LinkedHashMap<>(Map.of("internal_id",id,"uuid",uuid,"mapped_uuid",mapped,"bind_mapped_name",name,"backend_scopes",Map.of("self","MUA"),"backend","https://example.invalid","bind_status",state,"bind",List.of()));
    }
}
