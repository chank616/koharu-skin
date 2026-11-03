package top.twinklestars.koharuskin.oauth;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthClientsTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired OAuthClients clients;
    final AccountUser owner=new AccountUser(1801,"oauth1801@example.invalid","Owner","unused",0);
    final AccountUser other=new AccountUser(1802,"oauth1802@example.invalid","Other","unused",0);
    final AccountUser admin=new AccountUser(1803,"oauth1803@example.invalid","Admin","unused",2);

    @BeforeEach void fixture() {
        for(String table:List.of("oauth_refresh_tokens","oauth_auth_codes","oauth_access_tokens","oauth_clients","scopes","notifications","players","users","options"))
            jdbc.update("DELETE FROM "+table);
        for(var user:List.of(owner,other,admin)) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                user.uid(),user.email(),user.nickname(),user.permission());
    }

    @Test void reusedReactPageAndClientCrudPreserveIdsSecretsAndOwner() throws Exception {
        mvc.perform(get("/user/oauth/manage").with(user(owner))).andExpect(status().isOk())
                .andExpect(view().name("user/oauth")).andExpect(model().attribute("blessing",hasEntry("route","user/oauth/manage")))
                .andExpect(content().string(containsString("container-fluid")));
        mvc.perform(get("/user").with(user(owner))).andExpect(content().string(containsString("/user/oauth/manage")));
        JsonNode app=create("自己的应用","https://client.example.invalid/callback",null);
        long id=app.path("id").asLong(); String secret=app.path("secret").asString();
        assertThat(id).isPositive(); assertThat(secret).matches("[a-f0-9]{48}");
        assertThat(app.path("user_id").asLong()).isEqualTo(owner.uid());
        assertThat(app.path("created_at").isTextual()).isTrue();
        mvc.perform(get("/oauth/clients").with(user(owner))).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].secret").value(secret));
        mvc.perform(put("/oauth/clients/"+id).with(user(owner)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("name","改名","redirect","https://client.example.invalid/new,app.test://callback/oauth", "secret","replace", "user_id",other.uid(),
                        "password_client",true,"personal_access_client",true,"revoked",true,"confidential",false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.name").value("改名"))
                .andExpect(jsonPath("$.secret").value(secret)).andExpect(jsonPath("$.user_id").value(owner.uid()))
                .andExpect(jsonPath("$.password_client").value(false)).andExpect(jsonPath("$.personal_access_client").value(false))
                .andExpect(jsonPath("$.revoked").value(false));
        mvc.perform(get("/oauth/clients").with(user(other))).andExpect(content().json("[]"));
        var second=create("第二个应用","http://127.0.0.1:18444/callback",null);
        assertThat(second.path("secret").asString()).isNotEqualTo(secret);
    }

    @Test void confidentialAndPublicClientTypesCannotBeChangedThroughUpdate() throws Exception {
        var app=create("公共客户端","app.test://callback/path",false);
        assertThat(app.path("secret").isNull()).isTrue();
        mvc.perform(put("/oauth/clients/"+app.path("id").asLong()).with(user(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"更新\",\"redirect\":\"app.test://callback/path\",\"confidential\":true}"))
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test void ownerChecksCsrfAndFreshAccountStateProtectSecrets() throws Exception {
        long id=create("应用","https://client.example.invalid/callback",null).path("id").asLong();
        mvc.perform(get("/oauth/clients").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/oauth/manage")).andExpect(redirectedUrl("/auth/login"));
        for(var stranger:List.of(other,admin)) {
            mvc.perform(put("/oauth/clients/"+id).with(user(stranger)).with(csrf()).contentType("application/json")
                    .content("{\"name\":\"越权\",\"redirect\":\"https://evil.example.invalid\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/oauth/clients/"+id).with(user(stranger)).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(get("/oauth/clients").with(user(stranger))).andExpect(content().json("[]"));
        }
        mvc.perform(post("/oauth/clients").with(user(owner)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/oauth/clients/"+id).with(user(owner)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/oauth/clients/"+id).with(user(owner))).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET password='changed' WHERE uid=?",owner.uid());
        mvc.perform(get("/oauth/clients").with(user(owner)).accept("application/json")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET password='unused',permission=-1 WHERE uid=?",owner.uid());
        mvc.perform(get("/oauth/clients").with(user(owner)).accept("application/json")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked FROM oauth_clients WHERE id=?",Boolean.class,id)).isFalse();
    }

    @Test void verificationAppliesToClientManagementButScopeDescriptionsRemainReadable() throws Exception {
        long id=create("应用","https://client.example.invalid/callback",null).path("id").asLong();
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('require_verification','true')");
        jdbc.update("UPDATE users SET verified=false WHERE uid=?",owner.uid());
        for(String path:List.of("/user/oauth/manage","/oauth/clients"))
            mvc.perform(get(path).with(user(owner))).andExpect(status().isForbidden());
        mvc.perform(get("/oauth/scopes").with(user(owner))).andExpect(status().isOk());
        mvc.perform(post("/oauth/clients").with(user(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"应用\",\"redirect\":\"https://client.example.invalid\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/oauth/clients/"+id).with(user(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"改名\",\"redirect\":\"https://client.example.invalid\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/oauth/clients/"+id).with(user(owner)).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("UPDATE options SET option_value='false' WHERE option_name='require_verification'");
        mvc.perform(get("/oauth/clients").with(user(owner))).andExpect(status().isOk());
    }

    @Test void invalidFieldsAndRedirectsCannotCreateOrModifyAnApplication() throws Exception {
        long id=create("应用","https://client.example.invalid/callback",null).path("id").asLong();
        for(String redirect:List.of("/relative","javascript:alert(1)","data:text/html,hi","file://localhost/callback",
                "https://client.example.invalid/#fragment","https://client.example.invalid,",",https://client.example.invalid",
                "https://*.example.invalid/callback","https://user:password@client.example.invalid","https://client.example.invalid:99999/callback",
                "https://client.example.invalid/a b")) {
            String body=json.writeValueAsString(Map.of("name","无效","redirect",redirect));
            mvc.perform(post("/oauth/clients").with(user(owner)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.id").doesNotExist());
            mvc.perform(put("/oauth/clients/"+id).with(user(owner)).with(csrf()).contentType("application/json").content(body))
                    .andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.id").doesNotExist());
        }
        for(var body:List.of(Map.of("redirect","https://client.example.invalid"),Map.of("name","应用"),
                Map.of("name"," ","redirect","https://client.example.invalid"),Map.of("name","长".repeat(192),"redirect","https://client.example.invalid")))
            mvc.perform(post("/oauth/clients").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableEntity());
        assertThat(clients.list(owner.uid())).hasSize(1).first().satisfies(app-> {
            assertThat(app.name()).isEqualTo("应用"); assertThat(app.redirect()).isEqualTo("https://client.example.invalid/callback");
        });
    }

    @Test void revocationPreservesLegacyRowsAndRevokesOnlyRelatedGrants() throws Exception {
        long id=create("待撤销","https://client.example.invalid/callback",null).path("id").asLong();
        long untouched=clients.create(other.uid(),"其他用户","https://other.example.invalid",true).id();
        for(long client:List.of(id,untouched)) {
            jdbc.update("INSERT INTO oauth_access_tokens(id,user_id,client_id,scopes,revoked) VALUES(?,?,?,'[\"User.Read\"]',false)","access-"+client,owner.uid(),client);
            jdbc.update("INSERT INTO oauth_refresh_tokens(id,access_token_id,revoked) VALUES(?,?,false)","refresh-"+client,"access-"+client);
            jdbc.update("INSERT INTO oauth_auth_codes(id,user_id,client_id,scopes,revoked) VALUES(?,?,?,'[\"User.Read\"]',false)","code-"+client,owner.uid(),client);
        }
        mvc.perform(delete("/oauth/clients/"+id).with(user(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/oauth/clients").with(user(owner))).andExpect(content().json("[]"));
        for(String table:List.of("oauth_clients","oauth_access_tokens","oauth_refresh_tokens","oauth_auth_codes")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE revoked=true",Integer.class)).isOne();
        }
        assertThat(clients.list(other.uid())).hasSize(1).first().extracting(OAuthClient::id).isEqualTo(untouched);
        mvc.perform(delete("/oauth/clients/"+id).with(user(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(put("/oauth/clients/"+id).with(user(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"不能复活\",\"redirect\":\"https://client.example.invalid\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void scopesIncludeOriginalNamesCorrectedClosetPermissionAndDatabaseAdditions() throws Exception {
        jdbc.update("INSERT INTO scopes(name,description) VALUES('Union.Read','读取联合认证信息'),('User.Read','自定义资料说明')");
        var result=mvc.perform(get("/oauth/scopes").with(user(owner))).andExpect(status().isOk()).andReturn();
        var scopes=json.readTree(result.getResponse().getContentAsString());
        assertThat(scopes.findValuesAsString("id")).contains("User.Read","Player.ReadWrite","Closet.ReadWrite","Closet.ReadWrtie","UsersManagement.Read","ReportsManagement.ReadWrite","Union.Read")
                .doesNotHaveDuplicates();
        assertThat(scopes.findValuesAsString("description")).contains("自定义资料说明","读取联合认证信息");
    }

    private JsonNode create(String name,String redirect,Boolean confidential) throws Exception {
        var input=new java.util.HashMap<String,Object>(); input.put("name",name); input.put("redirect",redirect);
        if(confidential!=null) input.put("confidential",confidential);
        var result=mvc.perform(post("/oauth/clients").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
