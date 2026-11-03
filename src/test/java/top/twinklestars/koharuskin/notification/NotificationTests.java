package top.twinklestars.koharuskin.notification;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;
    @Autowired JsonMapper json;
    final AccountUser admin=new AccountUser(1201,"notice1201@example.invalid","Admin","unused",2);
    final AccountUser reader=new AccountUser(1202,"notice1202@example.invalid","Reader","unused",0);

    @BeforeEach void fixture() {
        for(String table:List.of("notifications","users","options","players")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true')");
        int[] permissions={2,0,0,1,-1};
        for(int i=0;i<permissions.length;i++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,100,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                1201+i,"notice"+(1201+i)+"@example.invalid","Notice"+i,permissions[i]);
    }

    @Test void sendingTargetsOriginalRecipientGroupsAndPreservesLegacyStorage() {
        assertThat(notifications.send(admin,"all",null,null,"全站","内容")).isEqualTo(5);
        assertThat(notifications.send(admin,"normal",null,null,"普通","")).isEqualTo(2);
        assertThat(notifications.send(admin,"uid",1202L,null,"指定 ID",null)).isOne();
        jdbc.update("UPDATE users SET email='notice1202@example.invalid' WHERE uid=1203");
        assertThat(notifications.send(admin,"email",null,"notice1202@example.invalid","指定邮箱","")).isEqualTo(2);
        assertThat(notifications.unread(1202).stream().map(NotificationService.Summary::title)).containsExactlyInAnyOrder("全站","普通","指定 ID","指定邮箱");
        assertThat(notifications.unread(1204)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type=? AND notifiable_type=?",Integer.class,
                "App\\Notifications\\SiteMessage","App\\Models\\User")).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT id FROM notifications",String.class)).allSatisfy(id->assertThat(UUID.fromString(id)).isNotNull());
    }

    @Test void oldUnreadMessagesAreOwnerScopedAndMarkdownIsRenderedWithoutActiveHtml() throws Exception {
        String id=UUID.randomUUID().toString();
        String markdown="**重要** ~~过期~~\n\n- [x] 完成\n\n|项目|状态|\n|---|---|\n|迁移|完成|\n\nhttps://example.invalid\n\n<script>alert(1)</script>\n\n[危险](javascript:alert%281%29)";
        jdbc.update("INSERT INTO notifications(id,type,notifiable_type,notifiable_id,data,created_at,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,"App\\Notifications\\SiteMessage","App\\Models\\User",1202,json.writeValueAsString(Map.of("title","旧站通知","content",markdown)));
        mvc.perform(get("/api/user/notifications").with(user(reader))).andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].title").value("旧站通知"));
        mvc.perform(post("/user/notifications/"+id).with(user(admin)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/user/notifications/"+id).with(user(reader))).andExpect(status().isForbidden());
        var response=mvc.perform(post("/api/user/notifications/"+id).with(user(reader)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("旧站通知")).andReturn();
        String html=json.readTree(response.getResponse().getContentAsString()).path("content").asString();
        assertThat(html).contains("<strong>重要</strong>","<del>过期</del>","<table>","type=\"checkbox\"","href=\"https://example.invalid\"","&lt;script&gt;")
                .doesNotContain("<script>","href=\"javascript:");
        assertThat(jdbc.queryForObject("SELECT read_at IS NOT NULL FROM notifications WHERE id=?",Boolean.class,id)).isTrue();
        mvc.perform(get("/api/user/notifications").with(user(reader))).andExpect(content().json("[]"));
        mvc.perform(post("/user/notifications/"+id).with(user(reader)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void adminFormAndNavigationReuseExistingNotificationWidget() throws Exception {
        mvc.perform(get("/admin").with(user(admin))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/notifications/send")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(post("/admin/notifications/send").with(user(admin)).with(csrf())
                        .param("receiver","uid").param("uid","1202").param("title","<通知>").param("content","正文"))
                .andExpect(redirectedUrl("/admin")).andExpect(flash().attribute("notificationSent",true));
        mvc.perform(get("/user").with(user(reader))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-notifications=")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;通知&gt;")));
        mvc.perform(get("/skinlib")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-notifications="))));
    }

    @Test void invalidRecipientsAndPermissionsCannotCreateMessages() throws Exception {
        mvc.perform(post("/api/admin/notifications").with(user(reader)).with(csrf()).contentType("application/json")
                .content("{\"receiver\":\"all\",\"title\":\"越权\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/notifications/send").with(user(admin)).param("receiver","all").param("title","无令牌"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/notifications/send").with(user(admin)).with(csrf()).param("receiver","uid").param("uid","abc").param("title","无效"))
                .andExpect(redirectedUrl("/admin")).andExpect(flash().attributeExists("notificationError"));
        for(String receiver:List.of("uid","email")) assertThatThrownBy(()->notifications.send(admin,receiver,null,null,"标题",""))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->notifications.send(admin,"uid",99999L,null,"标题","")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->notifications.send(admin,"all",null,null,"长".repeat(21),"")).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications",Integer.class)).isZero();
        mvc.perform(post("/api/admin/notifications").with(user(admin)).with(csrf()).contentType("application/json")
                .content("{\"receiver\":\"normal\",\"title\":\"API 通知\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.count").value(2));
    }
}
