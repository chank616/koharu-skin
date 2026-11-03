package top.twinklestars.koharuskin.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.closet.ClosetService;
import top.twinklestars.koharuskin.player.PlayerMapper;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EconomyTests {
    @Autowired ClosetService closet;
    @Autowired ScoreService scores;
    @Autowired PlayerMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final AccountUser owner = new AccountUser(1,"owner@example.invalid","Owner","unused",0);

    @BeforeEach
    void fixture() {
        for (String table : new String[]{"user_closet","textures","players","users","options"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('score_per_closet_item','10'),('score_award_per_like','3'),('sign_score','7,7')");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,register_at,last_sign_at) VALUES(1,'owner@example.invalid','Owner','unused',1000,'127.0.0.1',CURRENT_TIMESTAMP,?),(2,'uploader@example.invalid','Uploader','unused',1000,'127.0.0.2',CURRENT_TIMESTAMP,?)", LocalDateTime.now().minusDays(2),LocalDateTime.now().minusDays(2));
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,upload_at) VALUES(1,'Public skin','steve',?,1,2,true,CURRENT_TIMESTAMP),(2,'Private cape','cape',?,1,2,false,CURRENT_TIMESTAMP)", "a".repeat(64),"b".repeat(64));
    }

    @Test
    void closetKeepsCostsLikesAndUploaderRewardsConsistent() {
        closet.add(owner,1,"My skin");
        assertThat(users.score(1)).isEqualTo(990);
        assertThat(users.score(2)).isEqualTo(1003);
        assertThatThrownBy(() -> closet.add(owner,1,"Duplicate")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> closet.add(owner,2,"Private")).isInstanceOf(BusinessException.class);
        assertThat(users.score(1)).isEqualTo(990);
        jdbc.update("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(1,'AppliedSkin',1,0,CURRENT_TIMESTAMP)");
        closet.remove(1,1);
        assertThat(users.score(1)).isEqualTo(1000);
        assertThat(users.score(2)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT likes FROM textures WHERE tid=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT tid_skin FROM players WHERE uid=1", Integer.class)).isZero();
        assertThatThrownBy(() -> closet.remove(1,1)).isInstanceOf(BusinessException.class);
        assertThat(users.score(1)).isEqualTo(1000);
    }

    @Test
    void ownUploadDoesNotLoseUnawardedLikePointsAndRenameCannotCrossAccounts() {
        var uploader = new AccountUser(2,"uploader@example.invalid","Uploader","unused",0);
        closet.add(uploader,2,"Own cape");
        assertThat(users.score(2)).isEqualTo(990);
        assertThatThrownBy(() -> closet.rename(1,2,"Stolen")).isInstanceOf(BusinessException.class);
        closet.rename(2,2,"Renamed");
        closet.remove(2,2);
        assertThat(users.score(2)).isEqualTo(1000);
    }

    @Test
    void returnsOriginalReactPaginationAndPivotShape() throws Exception {
        closet.add(owner,1,"My skin");
        mvc.perform(get("/user/closet/list").with(user(owner)).param("q","My"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.last_page").value(1)).andExpect(jsonPath("$.data[0].public").value(true))
                .andExpect(jsonPath("$.data[0].pivot.item_name").value("My skin"))
                .andExpect(jsonPath("$.data[0].pivot.user_uid").value(1));
        mvc.perform(get("/user/closet/list").with(user(owner)).param("category","cape"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/user/closet/list").with(user(owner)).param("perPage","-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentSignRequestsAwardOnlyOnce() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0,8).mapToObj(i -> executor.submit(() -> scores.sign(1))).toList();
            int succeeded = 0;
            for (var task : tasks) if (task.get().get("code").equals(0)) succeeded++;
            assertThat(succeeded).isEqualTo(1);
        }
        assertThat(users.score(1)).isEqualTo(1007);
    }

    @Test
    void midnightModeAllowsPreviousDayButRejectsToday() {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('sign_after_zero','true')");
        jdbc.update("UPDATE users SET last_sign_at=? WHERE uid=1",LocalDateTime.now().toLocalDate().atStartOfDay().minusSeconds(1));
        assertThat(scores.sign(1).get("code")).isEqualTo(0);
        assertThat(scores.sign(1).get("code")).isEqualTo(1);
    }
}
