package top.twinklestars.koharuskin.admin;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import top.twinklestars.koharuskin.account.AccountUser;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSearchTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    AccountUser admin=new AccountUser(1901,"search1@example.invalid","John Doe","unused",2);
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM options");jdbc.update("DELETE FROM users");jdbc.update("DELETE FROM players");jdbc.update("DELETE FROM reports");
        for(int i=1;i<=14;i++) {
            String name=switch(i) {case 1->"John Doe";case 2->"Jane Doe";case 3->"O'Reilly";default->"Account"+i;};
            String date=i<=8?"2024-02-29 12:34:56":"2025-01-01 00:00:00";
            jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'unused',?,?,'127.0.0.1',?,?,?)",
                    1900+i,"search"+i+"@example.invalid",name,i==1?2:0,100+i*10,date,date,i%2==0);
            jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(?,?,?,?,0,?)",1900+i,1900+i,"Managed"+i,i%2,date);
            jdbc.update("INSERT INTO reports(id,tid,uploader,reporter,reason,status,report_at) VALUES(?,0,1901,1902,?,?,?)",1900+i,"Report "+i,i%3,date);
        }
    }
    @Test void userFiltersCombineComparisonsBooleansGroupsAndSorting() throws Exception {
        search("users","verified score>=200 sort:-score").andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.data[0].uid").value(1914));
        search("users","(nickname:\"John Doe\" or nickname:\"Jane Doe\") and not verified sort:uid")
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.data[0].uid").value(1901));
        search("users","uid in (1901,1903) sort:uid").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.data[1].uid").value(1903));
        search("users","uid:1901,1902 sort:-uid").andExpect(jsonPath("$.data[0].uid").value(1902));
        search("users","not (uid:1901 or uid:1902) sort:permission,-score").andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.data[0].uid").value(1914)).andExpect(jsonPath("$.last_page").value(2));
        search("users","Doe search1").andExpect(jsonPath("$.total").value(1));
        search("users","uid:1901 or uid:1902 sort:uid").andExpect(jsonPath("$.total").value(2));
    }
    @Test void datesPreserveYearMonthDayAndTimePrecision() throws Exception {
        search("users","register_at:2024-02 not verified").andExpect(jsonPath("$.total").value(4));
        search("users","register_at>2024").andExpect(jsonPath("$.total").value(6));
        search("users","register_at:\"2024-02-29 12:34\"").andExpect(jsonPath("$.total").value(8));
        search("users","register_at:\"2024-02-29 12\"").andExpect(jsonPath("$.total").value(8));
        search("users","register_at:\"2024-02-29 12:34:55\"").andExpect(jsonPath("$.total").value(0));
        search("users","register_at<2024-02-29").andExpect(jsonPath("$.total").value(0));
        search("users","register_at<=2024-02-29").andExpect(jsonPath("$.total").value(8));
        search("users","not register_at:2024").andExpect(jsonPath("$.total").value(6));
        search("users","register_at:02/2024").andExpect(jsonPath("$.total").value(8));
        jdbc.update("UPDATE users SET register_at=CURRENT_TIMESTAMP WHERE uid=1901");
        search("users","register_at:today").andExpect(jsonPath("$.total").value(1));
    }
    @Test void playerAliasesAndReportFieldsUseTheSameConditionsForCountAndPage() throws Exception {
        search("players","skin:1 cape:0 uid>=1910 sort:-pid").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.data[0].pid").value(1913));
        search("players","tid_skin:1 not uid:1901 sort:pid").andExpect(jsonPath("$.total").value(6));
        search("players","last_modified>=2025").andExpect(jsonPath("$.total").value(6));
        search("reports","status:0,1 report_at:2024 sort:-id").andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.data[0].id").value(1907));
        search("reports","reason:\"Report 2\"").andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/admin/reports/list").with(user(admin)).param("q","sort:id").param("page","2"))
                .andExpect(jsonPath("$.data[0].id").value(1910)).andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.total").value(14)).andExpect(jsonPath("$.last_page").value(2));
    }
    @Test void inputIsBoundAndInvalidSyntaxDoesNotBecomeAnUnfilteredQuery() throws Exception {
        search("users","nickname:\"O'Reilly\"").andExpect(jsonPath("$.total").value(1));
        search("users","nickname:\"' OR 1=1 --\"").andExpect(jsonPath("$.total").value(0));
        search("users","nickname:NULL").andExpect(jsonPath("$.total").value(0));
        search("users","not nickname:NULL").andExpect(jsonPath("$.total").value(14));
        for(String query:new String[]{"password:secret","sort:password","sort:uid;DROP","uid:","uid in ()","(uid:1901","\"unterminated","verified:perhaps","register_at:2024-02-30"}) {
            search("users",query).andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data").doesNotExist());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(14);
    }
    private ResultActions search(String list,String query) throws Exception {
        return mvc.perform(get("/admin/"+list+"/list").with(user(admin)).param("q",query)).andExpect(status().isOk());
    }
}
