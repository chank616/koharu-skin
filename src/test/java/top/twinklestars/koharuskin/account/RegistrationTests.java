package top.twinklestars.koharuskin.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registrations;
    @Autowired AccountMapper accounts;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM players");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM options");
        option("register_with_player_name", "true");
        option("user_initial_score", "2000");
    }

    @Test
    void createsAccountAndPlayerAndLogsInThroughOriginalFormContract() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/auth/captcha").session(session)).andExpect(status().isOk());
        String code = session.getAttribute("captcha").toString();
        mvc.perform(post("/auth/register").session(session).with(csrf()).contentType("application/json")
                        .content("""
                                {"email":"new@example.invalid","password":"new-password","player_name":"NewPlayer","captcha":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/user").session(session)).andExpect(status().isOk());
        var user = accounts.findByEmail("new@example.invalid");
        assertThat(passwords.matches("new-password", user.password())).isTrue();
        assertThat(jdbc.queryForObject("SELECT score FROM users WHERE uid=?", Integer.class, user.uid())).isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT uid FROM players WHERE name='NewPlayer'", Long.class)).isEqualTo(user.uid());
        assertThat(session.getAttribute("captcha")).isNull();
    }

    @Test
    void rejectsDuplicateNamesEmailsAndIpLimitWithoutPartialAccounts() {
        registrations.register("one@example.invalid", "password-one", null, "PlayerOne", "127.0.0.1");
        assertThatThrownBy(() -> registrations.register("ONE@example.invalid", "password-two", null, "PlayerTwo", "127.0.0.2"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("邮箱已注册");
        assertThatThrownBy(() -> registrations.register("two@example.invalid", "password-two", null, "playerone", "127.0.0.2"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("角色名已被使用");
        option("regs_per_ip", "1");
        assertThatThrownBy(() -> registrations.register("two@example.invalid", "password-two", null, "PlayerTwo", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("注册数量");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players", Integer.class)).isEqualTo(1);
    }

    @Test
    void appliesOfficialEmailRestrictionAndSupportsNicknameOnlyRegistration() {
        option("plugins_enabled", "{\"restricted-email-domains\":\"0.3.0\"}");
        option("restricted-email-domains.allow", "[\"school.example\"]");
        assertThatThrownBy(() -> registrations.register("one@example.invalid", "password-one", null, "PlayerOne", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("邮箱域名");
        jdbc.update("UPDATE options SET option_value='false' WHERE option_name='register_with_player_name'");
        registrations.register("one@school.example", "password-one", "测试昵称", null, "127.0.0.1");
        assertThat(accounts.findByEmail("one@school.example").nickname()).isEqualTo("测试昵称");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM players", Integer.class)).isZero();
    }

    @Test
    void requiresCaptchaAndValidInput() throws Exception {
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json")
                        .content("{\"email\":\"new@example.invalid\",\"password\":\"new-password\",\"player_name\":\"NewPlayer\"}"))
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }

    private void option(String name, String value) {
        jdbc.update("INSERT INTO options (option_name,option_value) VALUES (?,?)", name, value);
    }
}
