package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminDashboardTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminDashboard dashboard;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean Clock clock;
    final AccountUser admin=new AccountUser(1301,"stats@example.invalid","Stats","unused",2);
    final Instant now=Instant.parse("2026-03-01T17:00:00Z");
    final LocalDate today=LocalDate.ofInstant(now,ZoneId.systemDefault());

    @BeforeEach void fixture() {
        when(clock.instant()).thenReturn(now);
        for(String table:List.of("notifications","users","options","players","textures")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('plugins_enabled','[\"yggdrasil-api\",\"restricted-email-domains\"]')");
        insertUser(1301,today.atTime(12,0),2); insertUser(1302,today.minusDays(30).atStartOfDay(),0);
        insertUser(1303,today.minusDays(31).atTime(23,59,59),-1); insertUser(1304,today.plusDays(1).atStartOfDay(),0);
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(1301,1301,'Stats',0,0,CURRENT_TIMESTAMP)");
        insertTexture(1301,today.atTime(23,59,59),1000); insertTexture(1302,today.minusDays(30).atStartOfDay(),24);
        insertTexture(1303,today.minusDays(30).atTime(5,0),1); insertTexture(1304,today.plusDays(1).atStartOfDay(),1);
    }
    void insertUser(int uid,LocalDateTime date,int permission) {
        jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,100,'127.0.0.1',?,?,true)",
                uid,uid==1301?"stats@example.invalid":"stats"+uid+"@example.invalid","Stats"+uid,permission,date,date);
    }
    void insertTexture(int tid,LocalDateTime date,int size) {
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(?,'Stats','steve',?,?,1301,true,0,?)",tid,"d".repeat(64),size,date);
    }
    @Test void chartIncludesExactly31LocalDaysAndFillsGapsAcrossMonthBoundary() throws Exception {
        var chart=dashboard.chart();
        assertThat(chart.xAxis()).hasSize(31).startsWith(today.minusDays(30).format(DateTimeFormatter.ofPattern("yyyy/M/d")))
                .endsWith(today.format(DateTimeFormatter.ofPattern("yyyy/M/d")));
        assertThat(chart.data().get(0)).hasSize(31).startsWith(1L).endsWith(1L);
        assertThat(chart.data().get(0).subList(1,30)).containsOnly(0L);
        assertThat(chart.data().get(1)).hasSize(31).startsWith(2L).endsWith(1L);
        assertThat(chart.data().get(1).stream().mapToLong(Long::longValue).sum()).isEqualTo(3);
        mvc.perform(get("/admin/chart").with(user(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value("用户注册")).andExpect(jsonPath("$.xAxis.length()").value(31))
                .andExpect(jsonPath("$.data[1][0]").value(2));
    }
    @Test void totalsKeepOriginalCountingAndEmptyDatabaseRemainsZero() throws Exception {
        assertThat(dashboard.totals()).isEqualTo(new AdminDashboardMapper.Totals(4,1,4,1026));
        mvc.perform(get("/admin").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"chart-users-registration\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"chart-textures-upload\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1.0 MB")));
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM players"); jdbc.update("DELETE FROM textures");
        assertThat(dashboard.totals()).isEqualTo(new AdminDashboardMapper.Totals(0,0,0,0));
        assertThat(dashboard.chart().data().get(0)).hasSize(31).containsOnly(0L);
    }
    @Test void runtimeStatusIsAdminOnlyAndDoesNotDumpConfigurationSecrets() throws Exception {
        var ordinary=new AccountUser(1302,"stats1302@example.invalid","Stats1302","unused",0);
        for(String route:List.of("/admin","/admin/chart","/admin/status")) {
            mvc.perform(get(route).with(user(ordinary))).andExpect(status().isForbidden());
            mvc.perform(get(route).accept("application/json")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/admin/status").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0.0.1-SNAPSHOT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("外置登录")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("邮箱域名限制")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("皮肤 API 扩展"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("remember-key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("spring.datasource.password"))));
        assertThat(dashboard.status().get("数据库").keySet()).containsExactly("类型","版本","数据库","用户名");
    }
    @Test void ideRunWithoutMavenBuildMetadataCanStillShowRuntimeStatus() throws Exception {
        var noMetadata=new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(org.springframework.boot.info.BuildProperties.class);
        var ide=new AdminDashboard(context.getBean(AdminDashboardMapper.class),clock,context.getBean(javax.sql.DataSource.class),
                noMetadata,context.getEnvironment(),context.getBean(jakarta.servlet.ServletContext.class));
        assertThat(ide.status().get("应用")).containsEntry("Koharu Skin","开发版本");
    }
}
