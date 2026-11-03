package top.twinklestars.koharuskin.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.BusinessException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayerTests {
    @Autowired PlayerService service;
    @Autowired PlayerMapper players;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final AccountUser owner = new AccountUser(1, "owner@example.invalid", "Owner", "unused", 0);

    @BeforeEach
    void fixture() {
        for (String table : new String[]{"user_closet", "textures", "uuid", "players", "users", "options"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('ygg_uuid_algorithm','v3')");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,verified,ip,register_at,last_sign_at) VALUES(1,'owner@example.invalid','Owner','unused',1000,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'other@example.invalid','Other','unused',1000,false,'127.0.0.2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    }

    @Test
    void addsAndDeletesWithAtomicScoreAndStableOfflineUuid() {
        Player player = service.add(1, "Steve", false);
        assertThat(players.score(1)).isEqualTo(900);
        assertThat(service.uuid("Steve")).isEqualTo("5627dd98e6be3c21b8a8e92344183641");
        assertThatThrownBy(() -> service.add(2, "steve", false)).isInstanceOf(BusinessException.class);
        assertThat(players.score(2)).isEqualTo(1000);
        service.delete(1, player.pid());
        assertThat(players.score(1)).isEqualTo(1000);
        assertThat(players.list(1)).isEmpty();
        assertThatThrownBy(() -> service.delete(1, player.pid())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(players.score(1)).isEqualTo(1000);
    }

    @Test
    void preservesExistingUuidOnRenameIncludingCapitalization() {
        Player player = service.add(1, "OldPlayer", false);
        String original = "legacy-uuid-kept-verbatim";
        jdbc.update("UPDATE uuid SET uuid=? WHERE name='OldPlayer'", original);
        service.rename(1, player.pid(), "NewPlayer");
        assertThat(service.uuid("NewPlayer")).isEqualTo(original);
        service.rename(1, player.pid(), "newplayer");
        assertThat(service.uuid("newplayer")).isEqualTo(original);
        assertThat(players.uuid("OldPlayer")).isNull();
    }

    @Test
    void rejectsCrossAccountChangesAndUnverifiedAccess() throws Exception {
        Player player = service.add(2, "OtherPlayer", false);
        mvc.perform(put("/user/player/" + player.pid() + "/name").with(user(owner)).with(csrf())
                        .contentType("application/json").content("{\"name\":\"TakenPlayer\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('require_verification','true')");
        var other = new AccountUser(2, "other@example.invalid", "Other", "unused", 0);
        mvc.perform(get("/user/player/list").with(user(other))).andExpect(status().isForbidden());
        mvc.perform(get("/user/player/list").with(user(owner))).andExpect(status().isOk());
    }

    @Test
    void validatesClosetOwnershipAndTypeBeforeApplyingEitherTexture() {
        Player player = service.add(1, "SkinPlayer", false);
        String hash = "a".repeat(64);
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,upload_at) VALUES(1,'Skin','alex',?,1,1,true,CURRENT_TIMESTAMP),(2,'Cape','cape',?,1,2,true,CURRENT_TIMESTAMP)",hash,hash);
        jdbc.update("INSERT INTO user_closet(user_uid,texture_tid) VALUES(1,1)");
        assertThatThrownBy(() -> service.setTextures(1, player.pid(), 1, 2)).isInstanceOf(BusinessException.class);
        assertThat(players.find(player.pid()).tidSkin()).isZero();
        jdbc.update("INSERT INTO user_closet(user_uid,texture_tid) VALUES(1,2)");
        Player changed = service.setTextures(1, player.pid(), 1, 2);
        assertThat(changed.tidSkin()).isEqualTo(1);
        assertThat(changed.tidCape()).isEqualTo(2);
        assertThatThrownBy(() -> service.setTextures(1, player.pid(), 2, null)).isInstanceOf(BusinessException.class);
        assertThat(service.setTextures(1, player.pid(), 0, null).tidCape()).isEqualTo(2);
    }

    @Test
    void singlePlayerExtensionAllowsFirstBindingAndKeepsNicknameInSync() {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','{\"single-player-limit\":\"3.0.4\"}')");
        assertThatThrownBy(() -> service.add(1, "PlayerOne", false)).isInstanceOf(BusinessException.class);
        Player player = service.add(1, "PlayerOne", true);
        assertThat(players.score(1)).isEqualTo(1000);
        assertThatThrownBy(() -> service.add(1, "PlayerTwo", true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(1, player.pid())).isInstanceOf(BusinessException.class);
        service.rename(1, player.pid(), "PlayerNew");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE uid=1", String.class)).isEqualTo("PlayerNew");
    }

    @Test
    void bindingRetainsSelectedLegacyPlayerWithUuidAndTextures() {
        Player keep = service.add(1, "KeepPlayer", false);
        Player remove = service.add(1, "OtherOwned", false);
        String uuid = service.uuid(keep.name());
        jdbc.update("UPDATE players SET tid_skin=123,tid_cape=456 WHERE pid=?",keep.pid());
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[{\"name\":\"single-player-limit\",\"version\":\"3.0.4\"}]')");
        int score = players.score(1);
        var bound = service.add(1,"KeepPlayer",true);
        assertThat(bound.pid()).isEqualTo(keep.pid());
        assertThat(bound.tidSkin()).isEqualTo(123);
        assertThat(bound.tidCape()).isEqualTo(456);
        assertThat(players.find(remove.pid())).isNull();
        assertThat(players.score(1)).isEqualTo(score);
        assertThat(service.uuid(keep.name())).isEqualTo(uuid);
    }

    @Test
    void bindingRedirectsIncompleteAccountAndWorksBeforeEmailVerification() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('plugins_enabled','[{\"name\":\"single-player-limit\",\"version\":\"3.0.4\"}]'),('require_verification','true')");
        jdbc.update("UPDATE users SET verified=false WHERE uid=1");
        mvc.perform(get("/user").with(user(owner))).andExpect(redirectedUrl("/user/player/bind"));
        mvc.perform(get("/user/player/bind").with(user(owner))).andExpect(status().isOk());
        mvc.perform(post("/user/sign").with(user(owner)).with(csrf()).accept("application/json"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.redirectTo").value("/user/player/bind"));
        mvc.perform(post("/user/player/bind").with(user(owner)).with(csrf()).contentType("application/json").content("{\"player\":\"BoundPlayer\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/user/player/bind").with(user(owner))).andExpect(redirectedUrl("/user"));
        mvc.perform(get("/user").with(user(owner))).andExpect(status().isOk());
    }
}
