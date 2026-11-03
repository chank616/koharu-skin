package top.twinklestars.koharuskin.report;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.texture.TextureMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @Autowired PlayerMapper users;
    @Autowired TextureMapper textures;
    @Autowired AccountMapper accounts;
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("koharu.textures",()->storage.toString()); }
    final AccountUser admin=new AccountUser(1101,"report1101@example.invalid","Admin","unused",2);
    final AccountUser reporter=new AccountUser(1102,"report1102@example.invalid","Reporter","unused",0);
    final AccountUser uploader=new AccountUser(1103,"report1103@example.invalid","Uploader","unused",0);
    static final String HASH="c".repeat(64);

    @BeforeEach void fixture() throws Exception {
        for(String table:List.of("reports","players","users","options","textures","user_closet")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('reporter_score_modification','-5'),('reporter_reward_score','9')");
        for(int uid=1101;uid<=1104;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                uid,"report"+uid+"@example.invalid","Report"+uid,uid==1101?2:0);
        for(int tid=1101;tid<=1102;tid++) jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(?,?,'steve',?,1,1103,true,0,CURRENT_TIMESTAMP)",tid,"Reported"+tid,HASH);
        Files.write(storage.resolve(HASH),new byte[]{1,2,3});
    }

    @Test void submissionChecksCsrfScorePrivacyAndDuplicatesAtomically() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('require_verification','true')");
        jdbc.update("UPDATE users SET verified=false WHERE uid=1102");
        mvc.perform(post("/skinlib/report").with(user(reporter)).with(csrf()).contentType("application/json").content("{\"tid\":1101,\"reason\":\"未验证\"}"))
                .andExpect(status().isForbidden());
        assertThat(users.score(1102)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports",Integer.class)).isZero();
        jdbc.update("UPDATE users SET verified=true WHERE uid=1102");
        mvc.perform(post("/skinlib/report").with(user(reporter)).contentType("application/json").content("{\"tid\":1101,\"reason\":\"违规\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/skinlib/report").with(user(reporter)).with(csrf()).contentType("application/json").content("{\"tid\":1101,\"reason\":\"违规\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(users.score(1102)).isEqualTo(95);
        assertThatThrownBy(()->reports.submit(reporter,1101,"重复")).isInstanceOf(BusinessException.class);
        assertThat(users.score(1102)).isEqualTo(95);
        jdbc.update("UPDATE textures SET public=false WHERE tid=1102");
        mvc.perform(post("/skinlib/report").with(user(reporter)).with(csrf()).contentType("application/json").content("{\"tid\":1102,\"reason\":\"不可见\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE textures SET public=true WHERE tid=1102"); jdbc.update("UPDATE users SET score=1 WHERE uid=1102");
        assertThatThrownBy(()->reports.submit(reporter,1102,"不足")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports",Integer.class)).isOne();
    }

    @Test void ownTrackingAndAdminFilteringExposeOnlyPermittedData() throws Exception {
        reports.submit(reporter,1101,"<script>mine</script>"); reports.submit(uploader,1102,"other-report");
        mvc.perform(get("/user/reports").with(user(reporter))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;mine&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("other-report"))));
        mvc.perform(get("/admin/reports/list").with(user(reporter))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/reports").with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/admin/reports/list").with(user(admin)).param("q","status:0 sort:-report_at reporter:1102"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.data[0].texture.tid").value(1101))
                .andExpect(jsonPath("$.data[0].informer.uid").value(1102)).andExpect(jsonPath("$.data[0].texture_uploader.uid").value(1103))
                .andExpect(jsonPath("$.data[0].informer.password").doesNotExist());
        mvc.perform(get("/admin/reports/list").with(user(admin)).param("q","' OR 1=1 --")).andExpect(jsonPath("$.total").value(0));
    }

    @Test void deletingReportedTextureCleansReferencesKeepsSharedFileAndRewardsOnlyOnce() throws Exception {
        jdbc.update("INSERT INTO user_closet(user_uid,texture_tid,item_name) VALUES(1102,1101,'Collected')");
        jdbc.update("INSERT INTO players(uid,name,tid_skin,tid_cape,last_modified) VALUES(1102,'ReportedPlayer',1101,0,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE users SET avatar=1101 WHERE uid=1102");
        reports.submit(reporter,1101,"违规"); long id=id();
        mvc.perform(put("/admin/reports/"+id).with(user(admin)).with(csrf()).contentType("application/json").content("{\"action\":\"delete\"}"))
                .andExpect(jsonPath("$.data.status").value(1));
        assertThat(users.score(1102)).isEqualTo(109);
        assertThat(textures.find(1101)).isNull(); assertThat(textures.find(1102)).isNotNull(); assertThat(Files.exists(storage.resolve(HASH))).isTrue();
        assertThat(users.inCloset(1102,1101)).isZero(); assertThat(textures.avatarId(1102)).isZero();
        assertThat(users.findByName("ReportedPlayer").tidSkin()).isZero();
        reports.review(admin,id,"delete"); assertThat(users.score(1102)).isEqualTo(109);
        reports.submit(reporter,1102,"最后一个副本");
        reports.review(admin,jdbc.queryForObject("SELECT MAX(id) FROM reports",Long.class),"delete");
        assertThat(Files.exists(storage.resolve(HASH))).isFalse();
    }

    @Test void alreadyDeletedTextureReturnsDepositWithoutAwardAndMissingInformerIsAllowed() {
        reports.submit(reporter,1101,"已经删除"); long id=id();
        jdbc.update("DELETE FROM textures WHERE tid=1101");
        assertThat(reports.review(admin,id,"delete")).isOne(); assertThat(users.score(1102)).isEqualTo(100);
        reports.submit(uploader,1102,"注销的举报者");
        jdbc.update("DELETE FROM users WHERE uid=1103");
        reports.review(admin,jdbc.queryForObject("SELECT MAX(id) FROM reports",Long.class),"delete");
        assertThat(textures.find(1102)).isNull();
    }

    @Test void rejectClawsBackPositiveSubmissionRewardOnlyOnceAndBanRespectsHierarchy() throws Exception {
        jdbc.update("UPDATE options SET option_value='5' WHERE option_name='reporter_score_modification'");
        reports.submit(reporter,1101,"错误举报"); long id=id(); assertThat(users.score(1102)).isEqualTo(105);
        reports.review(admin,id,"reject"); reports.review(admin,id,"reject"); assertThat(users.score(1102)).isEqualTo(100);
        reports.submit(reporter,1102,"需封禁"); long second=jdbc.queryForObject("SELECT MAX(id) FROM reports",Long.class);
        jdbc.update("UPDATE users SET permission=2 WHERE uid=1103");
        assertThatThrownBy(()->reports.review(admin,second,"ban")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM reports WHERE id=?",Integer.class,second)).isZero();
        jdbc.update("UPDATE users SET permission=0 WHERE uid=1103");
        reports.review(admin,second,"ban"); assertThat(accounts.findById(1103).permission()).isEqualTo(-1);
        assertThat(users.score(1102)).isEqualTo(114);
        mvc.perform(get("/api/user").with(user(uploader)).accept("application/json")).andExpect(status().isUnauthorized());
    }

    @Test void concurrentReviewsDoNotDoubleRefundOrReward() throws Exception {
        reports.submit(reporter,1101,"并发审核"); long id=id();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=executor.submit(()->{gate.await(); return reports.review(admin,id,"ban");});
            var b=executor.submit(()->{gate.await(); return reports.review(admin,id,"ban");});
            gate.countDown(); assertThat(a.get(10,TimeUnit.SECONDS)).isOne(); assertThat(b.get(10,TimeUnit.SECONDS)).isOne();
        }
        assertThat(users.score(1102)).isEqualTo(109);
        assertThat(accounts.findById(1103).permission()).isEqualTo(-1);
    }

    private long id() { return jdbc.queryForObject("SELECT MIN(id) FROM reports",Long.class); }
}
