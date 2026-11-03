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
import top.twinklestars.koharuskin.player.PlayerMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPlayersTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlayerMapper players;
    static final String UUID="123456781234123412341234567890ab";

    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM players"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM options");
        jdbc.update("DELETE FROM textures"); jdbc.update("DELETE FROM uuid"); jdbc.update("DELETE FROM user_closet");
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true')");
        String hash=new BCryptPasswordEncoder(4).encode("player-admin-password");
        for(int uid=801;uid<=805;uid++) {
            jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,?,?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                    uid,"manage"+uid+"@example.invalid","Managed"+uid,hash,uid==801?2:uid<=803?1:0);
            jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(?,?,?,0,0,CURRENT_TIMESTAMP)",uid,uid,"Managed"+uid);
        }
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('Managed804',?)",UUID);
        for(int tid=801;tid<=803;tid++) jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(?,?,?,?,1,804,false,0,CURRENT_TIMESTAMP)",
                tid,"AdminTexture"+tid,tid==801?"steve":tid==802?"alex":"cape","a".repeat(64));
    }

    @Test void listAndPageAreAdminOnlyAndReturnOriginalFrontendFields() throws Exception {
        mvc.perform(get("/admin/players/list").accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/players/list").session(login(804))).andExpect(status().isForbidden());
        var admin=login(802);
        mvc.perform(get("/admin/players").session(admin)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("admin/players")));
        mvc.perform(get("/admin/players/list").session(admin)).andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.data[0].pid").value(805)).andExpect(jsonPath("$.data[0].uid").value(805))
                .andExpect(jsonPath("$.data[0].tid_skin").value(0)).andExpect(jsonPath("$.data[0].last_modified").isString());
        mvc.perform(get("/admin/players/list").param("q","Managed804").session(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/admin/players/list").param("q","' OR 1=1 --").session(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/admin/players/list").param("page","2").session(admin)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void renameRetainsUuidAndSynchronizesSinglePlayerNickname() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[\"single-player-limit\"]')");
        var admin=login(802);
        update(admin,804,"name","{\"player_name\":\"Managed805\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"name","{\"player_name\":\"bad name\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"name","{\"player_name\":\"Renamed804\"}").andExpect(jsonPath("$.code").value(0));
        assertThat(players.uuid("Managed804")).isNull();
        assertThat(players.uuid("Renamed804")).isEqualTo(UUID);
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE uid=804",String.class)).isEqualTo("Renamed804");
        update(admin,804,"name","{\"player_name\":\"renamed804\"}").andExpect(jsonPath("$.code").value(0));
        assertThat(players.uuid("renamed804")).isEqualTo(UUID);
        update(admin,803,"name","{\"player_name\":\"Denied803\"}").andExpect(status().isForbidden());
        update(admin,801,"name","{\"player_name\":\"Denied801\"}").andExpect(status().isForbidden());
        update(admin,99999,"name","{\"player_name\":\"Missing\"}").andExpect(status().isNotFound());
    }

    @Test void ownershipTransferPreservesAssociationsAndChecksBothOwners() throws Exception {
        players.textures(804,802,803);
        var admin=login(802);
        update(admin,804,"owner","{\"uid\":99999}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"owner","{\"uid\":803}").andExpect(status().isForbidden());
        update(admin,803,"owner","{\"uid\":805}").andExpect(status().isForbidden());
        update(admin,804,"owner","{}").andExpect(status().isUnprocessableEntity());
        assertThat(players.find(804).uid()).isEqualTo(804);
        update(admin,804,"owner","{\"uid\":805}").andExpect(jsonPath("$.code").value(0));
        assertThat(players.find(804).uid()).isEqualTo(805);
        assertThat(players.find(804).tidSkin()).isEqualTo(802);
        assertThat(players.find(804).tidCape()).isEqualTo(803);
        assertThat(players.uuid("Managed804")).isEqualTo(UUID);
        assertThat(players.score(804)).isEqualTo(100);
        assertThat(players.score(805)).isEqualTo(100);
        update(admin,804,"owner","{\"uid\":802}").andExpect(jsonPath("$.code").value(0));
        assertThat(players.find(804).uid()).isEqualTo(802);
    }

    @Test void adminCanSetPrivateTextureOutsideClosetButCannotMixSkinAndCape() throws Exception {
        var admin=login(802);
        update(admin,804,"textures","{\"tid\":802,\"type\":\"skin\"}").andExpect(jsonPath("$.code").value(0));
        update(admin,804,"textures","{\"tid\":803,\"type\":\"cape\"}").andExpect(jsonPath("$.code").value(0));
        update(admin,804,"textures","{\"tid\":803,\"type\":\"skin\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"textures","{\"tid\":801,\"type\":\"cape\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"textures","{\"tid\":99999,\"type\":\"skin\"}").andExpect(jsonPath("$.code").value(1));
        update(admin,804,"textures","{\"tid\":-1,\"type\":\"skin\"}").andExpect(status().isUnprocessableEntity());
        update(admin,804,"textures","{\"tid\":0,\"type\":\"uid\"}").andExpect(status().isUnprocessableEntity());
        update(admin,803,"textures","{\"tid\":801,\"type\":\"skin\"}").andExpect(status().isForbidden());
        assertThat(players.find(804).tidSkin()).isEqualTo(802);
        assertThat(players.find(804).tidCape()).isEqualTo(803);
        assertThat(players.inCloset(804,802)).isZero();
        update(admin,804,"textures","{\"tid\":0,\"type\":\"skin\"}").andExpect(jsonPath("$.code").value(0));
        assertThat(players.find(804).tidSkin()).isZero();
        assertThat(players.find(804).tidCape()).isEqualTo(803);
    }

    @Test void adminDeletionBypassesSingleLimitWithoutRefundAndRetainsUuid() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[\"single-player-limit\"]')");
        var admin=login(802);
        mvc.perform(delete("/admin/players/804").session(admin)).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/players/803").session(admin).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/admin/players/804").session(admin).with(csrf())).andExpect(jsonPath("$.code").value(0));
        assertThat(players.find(804)).isNull();
        assertThat(players.uuid("Managed804")).isEqualTo(UUID);
        assertThat(players.score(804)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM textures",Integer.class)).isEqualTo(3);
    }

    private MockHttpSession login(int uid) throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"identification\":\"manage"+uid+"@example.invalid\",\"password\":\"player-admin-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return session;
    }
    private ResultActions update(MockHttpSession session, long pid, String field, String body) throws Exception {
        return mvc.perform(put("/admin/players/"+pid+"/"+field).session(session).with(csrf()).contentType("application/json").content(body));
    }
}
