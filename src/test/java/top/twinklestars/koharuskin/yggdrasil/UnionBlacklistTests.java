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
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=","koharu.union-network-enabled=true","server.address=127.0.0.1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnionBlacklistTests {
    static HttpServer upstream;
    static final AtomicReference<String> lastMethod=new AtomicReference<>(),lastUri=new AtomicReference<>(),lastBody=new AtomicReference<>(),lastKey=new AtomicReference<>(),page=new AtomicReference<>();
    static final AtomicInteger calls=new AtomicInteger(),responseStatus=new AtomicInteger(200);
    @DynamicPropertySource static void server(DynamicPropertyRegistry registry) throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/union/blacklist",exchange->{
            calls.incrementAndGet(); lastMethod.set(exchange.getRequestMethod()); lastUri.set(exchange.getRequestURI().toString());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); lastKey.set(exchange.getRequestHeaders().getFirst("X-Union-Member-Key"));
            String result=responseStatus.get()==200?(exchange.getRequestMethod().equals("GET")?page.get():"{}"):"{\"message\":\"此邮箱已有有效记录\"}";
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(responseStatus.get(),bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);}
        }); upstream.start();
    }
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AccountMapper accounts;
    @Autowired JsonMapper json;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    AccountUser admin,ordinary;
    @BeforeEach void fixture() {
        for(String table:List.of("players","users","options"))jdbc.update("DELETE FROM "+table);
        options.insert("register_with_player_name","false"); options.insert("plugins_enabled","[\"yggdrasil-connect\"]");
        options.insert("union_api_root","http://127.0.0.1:"+upstream.getAddress().getPort()+"/union"); options.insert("union_member_key","synthetic-member-key");
        String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password");
        for(int uid=2701;uid<=2702;uid++)jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?, ?,?,321,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,"blacklist"+uid+"@example.invalid","Blacklist"+uid,password,uid==2701?2:0);
        admin=accounts.findById(2701); ordinary=accounts.findById(2702); calls.set(0); responseStatus.set(200);
        page.set(json.writeValueAsString(Map.of("data",List.of(Map.of("id",12,"email","blocked@example.invalid","source","synthetic","reason","<script>bad()</script>","created_at","2026-01-01 12:00:00","valid_until","2099-01-01 00:00:00")),"current_page",2,"last_page",3,"total",21)));
    }
    @Test void searchAndPaginationMatchOriginalApiAndHtmlEscapesExternalText() throws Exception {
        String query="\"blocked+tag@example.invalid\" & reason:spam";
        mvc.perform(get("/admin/union/view/blacklist/list").with(user(admin)).param("q",query).param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(21)).andExpect(jsonPath("$.data[0].id").value(12));
        assertThat(lastUri.get()).isEqualTo("/union/blacklist/query?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&page=2");
        assertThat(lastKey.get()).isEqualTo("synthetic-member-key");
        String html=mvc.perform(get("/admin/union/view/blacklist").with(user(admin)).param("q",query).param("page","2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("&lt;script&gt;bad()&lt;/script&gt;","上一页","下一页","共 21 条").doesNotContain("<script>bad()","synthetic-member-key");
        assertThat(org.jsoup.Jsoup.parse(html).select("form[action*=blacklist/invalidate/12]")).hasSize(1);
        mvc.perform(get("/api/union/member")).andExpect(jsonPath("$.enabledFeatures[0]").value("unionBlacklist"));
    }
    @Test void createReleaseAndDeleteUseDistinctUpstreamOperations() throws Exception {
        mvc.perform(post("/admin/union/blacklist/create").with(user(admin)).with(csrf()).contentType("application/json")
                .content("{\"email\":\"blocked@example.invalid\",\"reason\":\"已核实的违规\",\"source\":\"forged\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        assertThat(lastMethod.get()).isEqualTo("POST"); assertThat(lastUri.get()).isEqualTo("/union/blacklist/restful");
        assertThat(json.readTree(lastBody.get()).size()).isEqualTo(2); assertThat(json.readTree(lastBody.get()).get("reason").asString()).isEqualTo("已核实的违规");
        mvc.perform(post("/admin/union/blacklist/invalidate/12").with(user(admin)).with(csrf()).accept("application/json"))
                .andExpect(status().isOk());
        assertThat(lastMethod.get()).isEqualTo("PUT"); assertThat(lastUri.get()).isEqualTo("/union/blacklist/invalidate/12");
        mvc.perform(post("/admin/union/blacklist/delete/12").with(user(admin)).with(csrf())).andExpect(redirectedUrl("/admin/union/view/blacklist"));
        assertThat(lastMethod.get()).isEqualTo("DELETE"); assertThat(lastUri.get()).isEqualTo("/union/blacklist/restful/12");
    }
    @Test void adminCsrfValidationAndExtensionGatesPreventUnauthorizedRemoteWrites() throws Exception {
        for(String path:List.of("/admin/union/blacklist/create","/admin/union/blacklist/invalidate/12","/admin/union/blacklist/delete/12")) {
            mvc.perform(post(path).with(csrf()).accept("application/json").contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(user(ordinary)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
            mvc.perform(post(path).with(user(admin)).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/union/blacklist/create").with(user(admin)).with(csrf()).contentType("application/json").content("{\"email\":\"invalid\",\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/union/blacklist/create").with(user(admin)).with(csrf()).contentType("application/x-www-form-urlencoded").param("email","invalid").param("reason",""))
                .andExpect(redirectedUrl("/admin/union/view/blacklist")).andExpect(flash().attributeExists("error","blacklistForm"));
        options.update("plugins_enabled","[]");
        mvc.perform(get("/admin/union/view/blacklist").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/union/blacklist/delete/12").with(user(admin)).with(csrf()).accept("application/json")).andExpect(status().isNotFound());
        assertThat(calls.get()).isZero();
    }
    @Test void upstreamValidationAndOutagesStayVisibleAndFailedFormsKeepInput() throws Exception {
        responseStatus.set(422);
        mvc.perform(post("/admin/union/blacklist/create").with(user(admin)).with(csrf()).contentType("application/json").content("{\"email\":\"blocked@example.invalid\",\"reason\":\"reason\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("此邮箱已有有效记录"));
        mvc.perform(post("/admin/union/blacklist/create").with(user(admin)).with(csrf()).contentType("application/x-www-form-urlencoded").param("email","blocked@example.invalid").param("reason","reason"))
                .andExpect(redirectedUrl("/admin/union/view/blacklist")).andExpect(flash().attribute("error","此邮箱已有有效记录")).andExpect(flash().attributeExists("blacklistForm"));
        responseStatus.set(503);
        String html=mvc.perform(get("/admin/union/view/blacklist").with(user(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("联盟服务请求失败").doesNotContain("没有符合条件的记录");
    }
    @Test void actualHttpAdminCanSubmitTheRenderedFormWithItsCsrfToken() throws Exception {
        try(var client=java.net.http.HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build()) {
            String root="http://127.0.0.1:"+port;
            var loginPage=client.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            String csrf=org.jsoup.Jsoup.parse(loginPage.body()).selectFirst("meta[name=csrf-token]").attr("content");
            var login=java.net.http.HttpRequest.newBuilder(URI.create(root+"/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"identification\":\"blacklist2701@example.invalid\",\"password\":\"test-password\"}")).build();
            var loggedIn=client.send(login,java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(json.readTree(loggedIn.body()).get("code").asInt()).isZero();
            var html=client.send(java.net.http.HttpRequest.newBuilder(URI.create(root+"/admin/union/view/blacklist")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(html.statusCode()).isEqualTo(200);
            var form=org.jsoup.Jsoup.parse(html.body()).selectFirst("form[action=/admin/union/blacklist/create]");
            String input="email=blocked%40example.invalid&reason="+URLEncoder.encode("测试违规说明",StandardCharsets.UTF_8)+"&_csrf="+URLEncoder.encode(form.selectFirst("input[name=_csrf]").attr("value"),StandardCharsets.UTF_8);
            var submission=java.net.http.HttpRequest.newBuilder(URI.create(root+form.attr("action"))).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(input)).build();
            var created=client.send(submission,java.net.http.HttpResponse.BodyHandlers.ofString()); assertThat(created.statusCode()).isEqualTo(302);
            assertThat(lastMethod.get()).isEqualTo("POST"); assertThat(json.readTree(lastBody.get()).get("reason").asString()).isEqualTo("测试违规说明");
        }
    }
}
