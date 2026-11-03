package top.twinklestars.koharuskin.yggdrasil;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.twinklestars.koharuskin.account.*;
import top.twinklestars.koharuskin.admin.*;
import top.twinklestars.koharuskin.player.*;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.config.import=","koharu.union-network-enabled=true"})
@ActiveProfiles("test")
class UnionPlayerSyncTests {
    static HttpServer upstream;
    static final List<Request> requests=new CopyOnWriteArrayList<>();
    static final AtomicInteger responseStatus=new AtomicInteger(200);
    record Request(String method,String path,String key,JsonNode body) {}
    @DynamicPropertySource static void server(DynamicPropertyRegistry registry) throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/union/profile",exchange->{
            byte[] input=exchange.getRequestBody().readAllBytes();
            requests.add(new Request(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Union-Member-Key"),input.length==0?null:JsonMapper.builder().build().readTree(input)));
            byte[] body="{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(responseStatus.get(),body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        }); upstream.start();
    }
    @AfterAll static void stop(){upstream.stop(0);}
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteOptions options;
    @Autowired PlayerMapper mapper;
    @Autowired PlayerService players;
    @Autowired RegistrationService registrations;
    @Autowired ProfileService profiles;
    @Autowired AdminPlayersService adminPlayers;
    @Autowired AdminUsersService adminUsers;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void fixture() {
        for(String table:List.of("players","uuid","users","options"))jdbc.update("DELETE FROM "+table);
        options.insert("plugins_enabled","[\"yggdrasil-connect\"]");
        options.insert("union_api_root","http://127.0.0.1:"+upstream.getAddress().getPort()+"/union");
        options.insert("union_member_key","synthetic-member-key");
        options.insert("register_with_player_name","true"); options.insert("regs_per_ip","100");
        String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("test-password");
        for(int uid=2801;uid<=2803;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,permission,score,verified,ip,register_at,last_sign_at) VALUES(?,?,?, ?,?,1000,true,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,"sync"+uid+"@example.invalid","Sync"+uid,password,uid==2801?2:0);
        requests.clear(); responseStatus.set(200);
    }
    @Test void addRenameTextureAndDeleteKeepTheSameUuidAndUseOriginalProtocol() {
        var player=players.add(2802,"Before",false); String uuid=mapper.uuid("Before");
        assertThat(requests).hasSize(1);
        var added=requests.getFirst();
        assertThat(added.method()).isEqualTo("POST"); assertThat(added.path()).isEqualTo("/union/profile");
        assertThat(added.key()).isEqualTo("synthetic-member-key");
        assertThat(added.body().get("id").asString()).isEqualTo(uuid);
        assertThat(added.body().has("uuid")).isFalse();
        players.rename(2802,player.pid(),"After");
        players.rename(2802,player.pid(),"after");
        players.setTextures(2802,player.pid(),0,0);
        players.delete(2802,player.pid());
        assertThat(requests).hasSize(5);
        assertThat(requests.subList(1,5)).allSatisfy(request->assertThat(request.path()).isEqualTo("/union/profile/"+uuid));
        assertThat(requests.subList(1,4)).allSatisfy(request->assertThat(request.method()).isEqualTo("PUT"));
        assertThat(requests.get(1).body().get("name").asString()).isEqualTo("After");
        assertThat(requests.get(2).body().get("name").asString()).isEqualTo("after");
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
        assertThat(mapper.uuid("after")).isEqualTo(uuid); assertThat(mapper.find(player.pid())).isNull();
        assertThat(mapper.score(2802)).isEqualTo(1000);
    }
    @Test void registrationAndAccountDeletionSynchronizeAllProfilesWithoutDeletingUuids() {
        var account=registrations.register("new@example.invalid","test-password",null,"Registered","127.0.0.2");
        String first=mapper.uuid("Registered");
        players.add(account.uid(),"Second",false); String second=mapper.uuid("Second");
        assertThat(requests).hasSize(2);
        profiles.delete(account.uid(),"test-password");
        assertThat(requests.subList(2,4)).extracting(Request::path).containsExactly("/union/profile/"+first,"/union/profile/"+second);
        assertThat(requests.subList(2,4)).allSatisfy(request->assertThat(request.method()).isEqualTo("DELETE"));
        assertThat(mapper.list(account.uid())).isEmpty();
        assertThat(mapper.uuid("Registered")).isEqualTo(first); assertThat(mapper.uuid("Second")).isEqualTo(second);
    }
    @Test void adminChangesAndDeletionAlsoSynchronizeWithoutChargingOrRefundingScores() {
        var first=players.add(2802,"AdminFirst",false); var second=players.add(2802,"AdminSecond",false);
        String firstUuid=mapper.uuid(first.name()),secondUuid=mapper.uuid(second.name()); requests.clear();
        adminPlayers.owner(2801,first.pid(),2803);
        adminPlayers.name(2801,first.pid(),"AdminRenamed");
        adminPlayers.texture(2801,first.pid(),"skin",0);
        adminPlayers.delete(2801,first.pid());
        adminUsers.delete(2801,2802);
        assertThat(requests).hasSize(5);
        assertThat(requests.subList(0,4)).allSatisfy(request->assertThat(request.path()).isEqualTo("/union/profile/"+firstUuid));
        assertThat(requests.subList(0,3)).allSatisfy(request->assertThat(request.method()).isEqualTo("PUT"));
        assertThat(requests.get(3).method()).isEqualTo("DELETE");
        assertThat(requests.getLast().path()).isEqualTo("/union/profile/"+secondUuid);
        assertThat(mapper.score(2803)).isEqualTo(1000);
    }
    @Test void bindingKeepsSelectedUuidAndDeletesOnlyDiscardedProfiles() {
        var keep=players.add(2802,"Keep",false); var discard=players.add(2802,"Discard",false);
        String keepUuid=mapper.uuid(keep.name()),discardUuid=mapper.uuid(discard.name());
        options.update("plugins_enabled","[\"yggdrasil-connect\",\"single-player-limit\"]"); requests.clear();
        players.add(2802,"Keep",true);
        assertThat(requests).hasSize(1); assertThat(requests.getFirst().path()).isEqualTo("/union/profile/"+discardUuid);
        assertThat(requests.getFirst().method()).isEqualTo("DELETE"); assertThat(mapper.uuid("Keep")).isEqualTo(keepUuid);
        // 新名字绑定也须删除全部旧角色，然后向联盟注册新角色。
        options.update("plugins_enabled","[\"yggdrasil-connect\"]"); players.add(2802,"Another",false);
        options.update("plugins_enabled","[\"yggdrasil-connect\",\"single-player-limit\"]"); requests.clear();
        players.add(2802,"NewBinding",true);
        assertThat(requests).extracting(Request::method).containsExactly("DELETE","DELETE","POST");
        assertThat(mapper.list(2802)).extracting(Player::name).containsExactly("NewBinding");
        assertThat(mapper.uuid("Keep")).isEqualTo(keepUuid);
    }
    @Test void notificationsWaitForCommitAndRollbackNeverChangesUnion() {
        var transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            players.add(2802,"RolledBack",false);
            assertThat(requests).isEmpty(); status.setRollbackOnly();
        });
        assertThat(requests).isEmpty(); assertThat(mapper.findByName("RolledBack")).isNull(); assertThat(mapper.uuid("RolledBack")).isNull();
        transaction.executeWithoutResult(status->{players.add(2802,"Committed",false); assertThat(requests).isEmpty();});
        assertThat(requests).hasSize(1);
        requests.clear(); responseStatus.set(503);
        assertThatCode(()->players.rename(2802,mapper.findByName("Committed").pid(),"StillSaved")).doesNotThrowAnyException();
        assertThat(mapper.findByName("StillSaved")).isNotNull(); assertThat(requests).hasSize(1);
    }
    @Test void disabledExtensionDoesNotSendPlayerData() {
        options.update("plugins_enabled","[]");
        var player=players.add(2802,"LocalOnly",false);
        players.rename(2802,player.pid(),"LocalRenamed"); players.delete(2802,player.pid());
        registrations.register("local@example.invalid","test-password",null,"LocalRegistered","127.0.0.2");
        assertThat(requests).isEmpty();
    }
}
