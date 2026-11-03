package top.twinklestars.koharuskin.extension;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class XiqueerVerification {
    private static final String SESSION_KEY="xiqueer-verification.challenge";
    private static final String SUFFIX="@stu.ncwu.edu.cn";
    private final OfficialExtensions extensions;
    private final AccountMapper users;
    private final Clock clock;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final SecureRandom random=new SecureRandom();
    private final ConcurrentHashMap<RateKey,Rate> rates=new ConcurrentHashMap<>();
    public XiqueerVerification(OfficialExtensions extensions,AccountMapper users,Clock clock,
                              @Value("${koharu.xiqueer-url:https://jwmis.ncwu.edu.cn/hsjw/frame/LoginBar.jsp}") URI endpoint) {
        this.extensions=extensions; this.users=users; this.clock=clock; this.endpoint=endpoint;
    }
    public record Page(boolean verified,boolean eligible,String email,String challenge) {}
    public record Result(int code,String message,Map<String,Object> data) {}
    record Challenge(long uid,String email,String code,Instant expiresAt) implements java.io.Serializable {}
    private record RateKey(long uid,boolean polling) {}
    private record Rate(int count,Instant resetsAt) {}

    public void requireEnabled() {
        if(!extensions.enabled("xiqueer-verification")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private boolean eligible(AccountUser user) { return user.email().toLowerCase(Locale.ROOT).endsWith(SUFFIX); }
    public Page page(AccountUser user,HttpSession session) {
        requireEnabled();
        boolean verified=users.verified(user.uid()),eligible=eligible(user);
        String challenge=null;
        if(!verified&&eligible) challenge=issue(user,session);
        else session.removeAttribute(SESSION_KEY);
        return new Page(verified,eligible,user.email(),challenge);
    }
    public Result challenge(AccountUser user,HttpSession session) {
        requireEnabled(); throttle(user.uid(),false,12);
        if(users.verified(user.uid())) {
            session.removeAttribute(SESSION_KEY);
            return new Result(0,"当前账号已经完成邮箱验证。",Map.of("verified",true));
        }
        if(!eligible(user)) {
            session.removeAttribute(SESSION_KEY);
            return new Result(2,"当前账号不是受支持的华水学生邮箱。",Map.of());
        }
        return new Result(0,"",Map.of("challenge",issue(user,session),"email",user.email()));
    }
    public Result status(AccountUser user,HttpSession session) {
        requireEnabled(); throttle(user.uid(),true,40);
        if(users.verified(user.uid())) return result(0,"认证成功。");
        if(!eligible(user)) return result(2,"当前账号不是受支持的华水学生邮箱。");
        var challenge=(Challenge)session.getAttribute(SESSION_KEY);
        if(challenge==null||challenge.uid()!=user.uid()||!challenge.email().equals(user.email())||!clock.instant().isBefore(challenge.expiresAt())) {
            session.removeAttribute(SESSION_KEY);
            return new Result(3,"二维码已过期，请生成新的二维码。",Map.of("reload",true));
        }
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6))
                .header("Content-Type","application/x-www-form-urlencoded").header("Accept","*/*")
                .header("User-Agent","Mozilla/5.0 (Xiqueer Verification Plugin)")
                .POST(HttpRequest.BodyPublishers.ofString("operate=query&qrCode="+challenge.code())).build();
        HttpResponse<String> response;
        try { response=http.send(request,HttpResponse.BodyHandlers.ofString()); }
        catch(IOException error) { return result(2,"暂时无法连接学校认证服务，请稍后重试。"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); return result(2,"认证请求已中断，请重试。"); }
        if(response.statusCode()<200||response.statusCode()>=300) return result(2,"暂时无法连接学校认证服务，请稍后重试。");
        String studentId=response.body().trim();
        if(studentId.isEmpty()) return result(1,"等待喜鹊儿 APP 扫码……");
        if(!studentId.matches("[A-Za-z0-9._-]{3,64}")) return result(2,"学校认证服务返回了无法识别的数据，请稍后重试。");
        if(!(studentId+SUFFIX).equalsIgnoreCase(user.email().trim())) {
            session.removeAttribute(SESSION_KEY);
            return new Result(3,"扫码账号与当前皮肤站邮箱不一致，请确认账号后重试。",Map.of("reload",true));
        }
        synchronized(session) {
            if(!challenge.equals(session.getAttribute(SESSION_KEY))||!clock.instant().isBefore(challenge.expiresAt()))
                return new Result(3,"二维码已过期，请生成新的二维码。",Map.of("reload",true));
            if(users.verifyEmail(user.uid(),challenge.email())==0) {
                session.removeAttribute(SESSION_KEY);
                return new Result(3,"账号信息已更改，请重新验证。",Map.of("reload",true));
            }
            session.removeAttribute(SESSION_KEY);
        }
        return result(0,"认证成功。");
    }
    private Result result(int code,String message) { return new Result(code,message,Map.of()); }
    private String issue(AccountUser user,HttpSession session) {
        var code=new StringBuilder("smdljwxt");
        for(int i=0;i<16;i++) code.append("0123456789ABCDEFGHIJKLMNOPQRSTUV".charAt(random.nextInt(32)));
        session.setAttribute(SESSION_KEY,new Challenge(user.uid(),user.email(),code.toString(),clock.instant().plusSeconds(300)));
        return code.toString();
    }
    private void throttle(long uid,boolean polling,int limit) {
        var now=clock.instant();
        rates.entrySet().removeIf(entry->!now.isBefore(entry.getValue().resetsAt()));
        var rate=rates.compute(new RateKey(uid,polling),(key,previous)->previous==null?new Rate(1,now.plusSeconds(60)):new Rate(previous.count()+1,previous.resetsAt()));
        if(rate.count()>limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"操作过于频繁，请稍后重试");
    }
}
