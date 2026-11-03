package top.twinklestars.koharuskin.account;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountMailTests {
    static final Inbox inbox = new Inbox();
    static final AtomicLong sequence = new AtomicLong(100);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> inbox.server.getLocalPort());
        properties.add("koharu.public-url", () -> "http://localhost:18080");
        properties.add("koharu.mail-from", () -> "noreply@koharu.invalid");
    }
    @Autowired AccountMailService mail;
    @Autowired AccountMapper accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    long uid;
    Instant now;

    @BeforeEach void fixture() {
        uid = sequence.incrementAndGet();
        now = Instant.now();
        when(clock.instant()).thenReturn(now);
        inbox.messages.clear();
        jdbc.update("DELETE FROM players"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM options");
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('require_verification','true')");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,register_at,last_sign_at) VALUES(?,'mail@example.invalid','MailUser',?,1000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",uid,new BCryptPasswordEncoder(4).encode("old-password"));
    }

    @AfterAll static void close() throws IOException { inbox.close(); }

    @Test void sendsRealSmtpVerificationAndConfirmsMatchingEmail() throws Exception {
        mail.sendVerification(uid);
        var link = received();
        mvc.perform(get(link.pathAndQuery())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        assertThatThrownBy(() -> mail.verify(uid,link.expires(),link.signature(),"wrong@example.invalid"))
                .isInstanceOf(BusinessException.class);
        assertThat(accounts.verified(uid)).isFalse();
        mvc.perform(post(link.pathAndQuery()).with(csrf()).param("email","mail@example.invalid"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/user"));
        assertThat(accounts.verified(uid)).isTrue();
        assertThatThrownBy(() -> mail.sendVerification(uid)).isInstanceOf(BusinessException.class);
    }

    @Test void resetChangesPasswordOnceAndRevokesExistingSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/auth/login").session(session).with(csrf()).contentType("application/json")
                        .content("{\"identification\":\"mail@example.invalid\",\"password\":\"old-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mail.forgot("mail@example.invalid","reset-"+uid);
        var link = received();
        mvc.perform(get(link.pathAndQuery())).andExpect(status().isOk());
        mvc.perform(post(link.pathAndQuery()).with(csrf()).contentType("application/json").content("{\"password\":\"new-password\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(new BCryptPasswordEncoder().matches("new-password",accounts.findById(uid).password())).isTrue();
        mvc.perform(get("/user").session(session).accept("application/json")).andExpect(status().isUnauthorized());
        mvc.perform(get(link.pathAndQuery())).andExpect(status().isForbidden());
        mvc.perform(post(link.pathAndQuery()).with(csrf()).contentType("application/json").content("{\"password\":\"replayed-password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void rejectsTamperingWrongPurposeExpiredLinksAndChangedEmail() throws Exception {
        mail.forgot("mail@example.invalid","expire-"+uid);
        var link = received();
        assertThatThrownBy(() -> mail.validate(uid,"verify",link.expires(),link.signature())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> mail.validate(uid,"reset",link.expires()+1,link.signature())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(3601));
        assertThatThrownBy(() -> mail.validate(uid,"reset",link.expires(),link.signature())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(clock.instant()).thenReturn(now);
        jdbc.update("UPDATE users SET email='changed@example.invalid' WHERE uid=?",uid);
        assertThatThrownBy(() -> mail.validate(uid,"reset",link.expires(),link.signature())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void forgotRequiresCaptchaAndRateLimitsDelivery() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/auth/forgot").session(session).with(csrf()).contentType("application/json").content("{\"email\":\"mail@example.invalid\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertThat(inbox.messages).isEmpty();
        mvc.perform(get("/auth/captcha").session(session));
        String captcha = session.getAttribute("captcha").toString();
        mvc.perform(post("/auth/forgot").session(session).with(csrf()).contentType("application/json")
                        .content("{\"email\":\"mail@example.invalid\",\"captcha\":\""+captcha+"\"}"))
                .andExpect(jsonPath("$.code").value(0));
        received();
        assertThatThrownBy(() -> mail.forgot("mail@example.invalid","127.0.0.1")).isInstanceOf(BusinessException.class).hasMessageContaining("频繁");
    }

    private Link received() throws Exception {
        byte[] message = inbox.messages.poll(5,TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        var mime = new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(message));
        assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("mail@example.invalid");
        String text = mime.getContent().toString();
        String address = text.lines().filter(line -> line.startsWith("http://")).findFirst().orElseThrow().trim();
        URI uri = URI.create(address);
        var parameters = Arrays.stream(uri.getQuery().split("&")).map(pair -> pair.split("=",2))
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0],pair -> pair[1]));
        return new Link(uri.getRawPath()+"?"+uri.getRawQuery(),Long.parseLong(parameters.get("expires")),parameters.get("signature"));
    }
    record Link(String pathAndQuery,long expires,String signature) {}

    static class Inbox implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        Inbox() {
            try { server = new ServerSocket(0,10,InetAddress.getLoopbackAddress()); }
            catch(IOException error) { throw new UncheckedIOException(error); }
            worker.submit(() -> {
                while(!server.isClosed()) {
                    try(var socket=server.accept();var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var writer=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
                        writer.println("220 localhost test SMTP");
                        String line;
                        while((line=reader.readLine())!=null) {
                            if(line.startsWith("QUIT")) { writer.println("221 bye"); break; }
                            if(line.equals("DATA")) {
                                writer.println("354 send message");
                                var data=new StringBuilder();
                                while((line=reader.readLine())!=null && !line.equals(".")) data.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                                messages.add(data.toString().getBytes(StandardCharsets.UTF_8));
                            }
                            writer.println("250 OK");
                        }
                    } catch(IOException error) { if(!server.isClosed()) throw new UncheckedIOException(error); }
                }
            });
        }
        @Override public void close() throws IOException { server.close(); worker.close(); }
    }
}
