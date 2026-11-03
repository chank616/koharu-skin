package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import top.twinklestars.koharuskin.account.AccountMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUsersTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountMapper accounts;

    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM players"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM options");
        jdbc.update("DELETE FROM textures"); jdbc.update("DELETE FROM uuid");
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true')");
        String hash=new BCryptPasswordEncoder(4).encode("admin-test-password");
        for(int uid=701;uid<=714;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,?,?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                uid,"admin"+uid+"@example.invalid","Account"+uid,hash,uid==701?2:uid<=703?1:0);
    }

    @Test void onlyAdminsCanReadAndPaginationDoesNotExposeCredentials() throws Exception {
        mvc.perform(get("/admin/users/list").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/users/list").session(login(704))).andExpect(status().isForbidden());
        var admin=login(702);
        mvc.perform(get("/admin/users").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("admin/users")));
        mvc.perform(get("/admin/users/list").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(14)).andExpect(jsonPath("$.last_page").value(2))
                .andExpect(jsonPath("$.data.length()").value(10)).andExpect(jsonPath("$.data[0].uid").value(714))
                .andExpect(jsonPath("$.data[0].password").doesNotExist()).andExpect(jsonPath("$.data[0].remember_token").doesNotExist())
                .andExpect(jsonPath("$.data[0].verification_token").doesNotExist());
        mvc.perform(get("/admin/users/list").param("page","2").session(admin)).andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(get("/admin/users/list").param("q","admin704@").session(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/admin/users/list").param("q","703").session(admin)).andExpect(jsonPath("$.data[0].uid").value(703));
        mvc.perform(get("/admin/users/list").param("q","' OR 1=1 --").session(admin)).andExpect(jsonPath("$.total").value(0));
    }

    @Test void permissionsCannotBeEscalatedOrChangedForPeersAndSelf() throws Exception {
        var admin=login(702); var root=login(701);
        update(admin,703,"nickname","{\"nickname\":\"denied\"}").andExpect(status().isForbidden());
        update(admin,701,"score","{\"score\":10}").andExpect(status().isForbidden());
        update(admin,704,"permission","{\"permission\":1}").andExpect(status().isForbidden());
        update(root,701,"permission","{\"permission\":0}").andExpect(status().isForbidden());
        update(root,704,"permission","{\"permission\":2}").andExpect(status().isUnprocessableEntity());
        update(root,704,"permission","{}").andExpect(status().isUnprocessableEntity());
        update(root,704,"permission","{\"permission\":1}").andExpect(jsonPath("$.code").value(0));
        update(admin,704,"nickname","{\"nickname\":\"denied\"}").andExpect(status().isForbidden());
        update(root,702,"permission","{\"permission\":0}").andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/admin/users/list").session(admin)).andExpect(status().isForbidden());
        assertThat(accounts.findById(704).permission()).isOne();
        assertThat(accounts.findById(703).nickname()).isEqualTo("Account703");
    }

    @Test void profileEditsValidateDuplicatesPreserveVerificationAndRevokeLoginOnEmailChange() throws Exception {
        var admin=login(702); var target=login(704);
        update(admin,704,"email","{\"email\":\"ADMIN705@example.invalid\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,704,"email","{\"email\":\"admin704@example.invalid\"}").andExpect(jsonPath("$.code").value(0));
        update(admin,704,"email","{\"email\":\"invalid\"}").andExpect(status().isUnprocessableEntity());
        update(admin,704,"nickname","{\"nickname\":\"后台修改\"}").andExpect(jsonPath("$.code").value(0));
        update(admin,704,"score","{\"score\":321}").andExpect(jsonPath("$.code").value(0));
        update(admin,704,"score","{}").andExpect(status().isUnprocessableEntity());
        update(admin,704,"verification","").andExpect(jsonPath("$.code").value(0));
        assertThat(accounts.verified(704)).isFalse();
        update(admin,704,"verification","").andExpect(jsonPath("$.code").value(0));
        update(admin,704,"email","{\"email\":\"replaced@example.invalid\"}").andExpect(jsonPath("$.code").value(0));
        assertThat(accounts.verified(704)).isTrue();
        assertThat(accounts.findById(704).nickname()).isEqualTo("后台修改");
        assertThat(jdbc.queryForObject("SELECT score FROM users WHERE uid=704",Integer.class)).isEqualTo(321);
        mvc.perform(get("/api/user").session(target).accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test void resetAndBanInvalidateExistingSessionsAndRequireCsrf() throws Exception {
        var admin=login(702); var target=login(704); var other=login(705);
        mvc.perform(put("/admin/users/704/password").session(admin).contentType("application/json").content("{\"password\":\"changed-password\"}"))
                .andExpect(status().isForbidden());
        update(admin,704,"password","{\"password\":\"short\"}").andExpect(status().isUnprocessableEntity());
        update(admin,704,"password","{\"password\":\"changed-password\"}").andExpect(jsonPath("$.code").value(0));
        assertThat(new BCryptPasswordEncoder().matches("changed-password",accounts.findById(704).password())).isTrue();
        mvc.perform(get("/api/user").session(target).accept("application/json")).andExpect(status().isUnauthorized());
        update(admin,705,"permission","{\"permission\":-1}").andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/user").session(other).accept("application/json")).andExpect(status().isUnauthorized());
        update(admin,705,"permission","{\"permission\":0}").andExpect(jsonPath("$.code").value(0));
        login(705);
    }

    @Test void deletingAccountRemovesPlayersButPreservesTextureAndUuidAndRejectsPeers() throws Exception {
        jdbc.update("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(704,'RetainedAdmin',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('RetainedAdmin','123456781234123412341234567890ab')");
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(704,'Retained','steve',?,1,704,true,0,CURRENT_TIMESTAMP)","a".repeat(64));
        var admin=login(702); var target=login(704);
        mvc.perform(delete("/admin/users/703").session(admin).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/users/704").session(admin).with(csrf())).andExpect(jsonPath("$.code").value(0));
        assertThat(accounts.findById(704)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players WHERE uid=704",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM textures WHERE uploader=704",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uuid WHERE name='RetainedAdmin'",Integer.class)).isOne();
        mvc.perform(get("/api/user").session(target).accept("application/json")).andExpect(status().isUnauthorized());
        update(admin,99999,"nickname","{\"nickname\":\"missing\"}").andExpect(status().isNotFound());
    }

    private MockHttpSession login(int uid) throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"identification\":\"admin"+uid+"@example.invalid\",\"password\":\"admin-test-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return session;
    }
    private ResultActions update(MockHttpSession session, long uid, String field, String body) throws Exception {
        return mvc.perform(put("/admin/users/"+uid+"/"+field).session(session).with(csrf()).contentType("application/json").content(body));
    }
}
