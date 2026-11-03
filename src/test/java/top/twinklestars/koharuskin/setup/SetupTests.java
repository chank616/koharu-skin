package top.twinklestars.koharuskin.setup;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import top.twinklestars.koharuskin.account.AccountMapper;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","koharu.union-network-enabled=false","koharu.public-url="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SetupTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder passwords;
    @BeforeEach void emptySite() {
        jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM options");
        options.insert("register_with_player_name","true");options.insert("koharu_installed","false");
    }
    @AfterEach void restoreInstalledFixture() { jdbc.update("DELETE FROM options WHERE option_name='koharu_installed'"); }
    @Test void emptySiteRoutesToSetupAndCannotRegisterBeforeAdministratorExists() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
        mvc.perform(get("/auth/login")).andExpect(redirectedUrl("/setup"));
        mvc.perform(get("/api/site")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/auth/register").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("请先完成站点安装"));
        var page=mvc.perform(get("/setup")).andExpect(status().isOk()).andExpect(view().name("setup")).andReturn();
        assertThat(page.getResponse().getContentAsString()).contains("name=\"_csrf\"","创建站点管理员");
        mvc.perform(post("/setup").param("siteName","Example").param("email","admin@example.invalid").param("nickname","Admin")
                .param("password","setup-password").param("confirmation","setup-password")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isZero();
    }
    @Test void validatesFormWithoutLeakingPasswordOrCompletingInstallation() throws Exception {
        var result=mvc.perform(post("/setup").with(csrf()).param("siteName","").param("email","not-an-email").param("nickname","")
                .param("password","setup-password").param("confirmation","different-password"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form","siteName","email","nickname","confirmation")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("setup-password","different-password");
        assertThat(options.get("koharu_installed")).isEqualTo("false");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isZero();
    }
    @Test void createsVerifiedSuperAdministratorWithHashedPasswordAndEnablesExternalLogin() throws Exception {
        install("admin@example.invalid").andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login"));
        var admin=accounts.findByEmail("admin@example.invalid");
        assertThat(admin.permission()).isEqualTo(2);assertThat(accounts.verified(admin.uid())).isTrue();
        assertThat(passwords.matches("setup-password",admin.password())).isTrue();assertThat(admin.password()).isNotEqualTo("setup-password");
        assertThat(options.get("koharu_installed")).isEqualTo("true");assertThat(options.get("site_name")).isEqualTo("测试皮肤站");
        assertThat(options.get("plugins_enabled")).isEqualTo("[\"yggdrasil-api\"]");
        assertThat(options.get("site_url")).isEqualTo("http://localhost");
        mvc.perform(get("/auth/login")).andExpect(status().isOk());mvc.perform(get("/api/yggdrasil")).andExpect(status().isOk());
        mvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                .content("{\"identification\":\"admin@example.invalid\",\"password\":\"setup-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        install("another@example.invalid").andExpect(status().isConflict());mvc.perform(get("/setup")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isOne();
    }
    @Test void existingAccountsCannotBeTakenOverEvenWithAnIncorrectInstallationFlag() throws Exception {
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,permission,register_at,last_sign_at) VALUES(8801,'old@example.invalid','Existing','legacy-hash',37,'127.0.0.1',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        install("attacker@example.invalid").andExpect(status().isConflict());
        assertThat(accounts.findByEmail("attacker@example.invalid")).isNull();
        assertThat(accounts.findById(8801).password()).isEqualTo("legacy-hash");
        assertThat(accounts.findById(8801).permission()).isZero();
    }
    @Test void concurrentInstallationsHaveExactlyOneWinner() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var first=executor.submit(()->{start.await();return install("first@example.invalid").andReturn().getResponse().getStatus();});
            var second=executor.submit(()->{start.await();return install("second@example.invalid").andReturn().getResponse().getStatus();});
            start.countDown();assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(302,409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE permission=2",Integer.class)).isOne();
    }
    private ResultActions install(String email) throws Exception {
        return mvc.perform(post("/setup").with(csrf()).param("siteName","测试皮肤站").param("email",email).param("nickname","站长")
                .param("password","setup-password").param("confirmation","setup-password"));
    }
}
