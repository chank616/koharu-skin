package top.twinklestars.koharuskin.account;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.site.SiteOptions;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AccountMailService {
    private final AccountMapper accounts;
    private final SiteOptions options;
    private final PasswordEncoder passwords;
    private final ObjectProvider<JavaMailSender> mail;
    private final String publicUrl;
    private final String from;
    private final byte[] key;
    private final java.time.Clock clock;
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    public AccountMailService(AccountMapper accounts, SiteOptions options, PasswordEncoder passwords,
                              ObjectProvider<JavaMailSender> mail, @Value("${koharu.public-url}") String publicUrl,
                              @Value("${koharu.mail-from}") String from, @Value("${koharu.remember-key}") String key, java.time.Clock clock) {
        this.accounts = accounts; this.options = options; this.passwords = passwords; this.mail = mail;
        this.publicUrl = publicUrl.replaceAll("/+$", ""); this.from = from; this.key = key.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public boolean available() { return mail.getIfAvailable() != null; }

    public void forgot(String email, String ip) {
        var user = accounts.findByEmail(email);
        if (user == null || !user.isEnabled()) throw new BusinessException("此账号不存在或已停用");
        send(user, "reset", "重置密码", 3600, "reset:" + ip, 180);
    }

    public void sendVerification(long uid) {
        if (!options.enabled("require_verification", false)) throw new BusinessException("本站未启用邮箱验证");
        if (accounts.verified(uid)) throw new BusinessException("邮箱已经验证");
        send(accounts.findById(uid), "verify", "验证邮箱", 86400, "verify:" + uid, 60);
    }

    public String afterRegistration(long uid) {
        if (!options.enabled("require_verification", false)) return "注册成功";
        if (!available()) return "注册成功，验证邮件暂不可用，请稍后在用户中心重新发送";
        try {
            sendVerification(uid);
            return "注册成功，请查收验证邮件";
        } catch (MailException error) {
            return "注册成功，验证邮件发送失败，请稍后在用户中心重新发送";
        }
    }

    private void send(AccountUser user, String purpose, String title, int lifetime, String rateKey, int interval) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new BusinessException("邮件服务暂不可用，请联系站点管理员");
        long now = clock.instant().getEpochSecond();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        long until = cooldowns.compute(rateKey, (key, previous) -> {
            if (previous != null && previous > now) throw new BusinessException("邮件发送过于频繁，请稍后再试");
            return now + interval;
        });
        long expires = now + lifetime;
        String url = publicUrl + "/auth/" + purpose + "/" + user.uid() + "?expires=" + expires + "&signature=" + signature(user, purpose, expires);
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(user.email());
        message.setSubject(options.text("site_name", "Koharu Skin") + " - " + title);
        message.setText("请打开以下链接" + title + "：\n\n" + url + "\n\n链接在 " + lifetime / 60 + " 分钟内有效。如果不是你发起的操作，请忽略此邮件。");
        try { sender.send(message); }
        catch (MailException error) { cooldowns.remove(rateKey, until); throw error; }
    }

    public void validate(long uid, String purpose, long expires, String signature) {
        validate(accounts.findById(uid), purpose, expires, signature);
    }

    private void validate(AccountUser user, String purpose, long expires, String supplied) {
        if (user == null || !user.isEnabled() || expires <= clock.instant().getEpochSecond()
                || !MessageDigest.isEqual(signature(user, purpose, expires).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "链接无效或已过期，请重新申请");
        }
    }

    @Transactional
    public void reset(long uid, long expires, String signature, String password) {
        var user = accounts.lockById(uid);
        validate(user, "reset", expires, signature);
        accounts.password(uid, passwords.encode(password));
    }

    @Transactional
    public void verify(long uid, long expires, String signature, String email) {
        var user = accounts.lockById(uid);
        validate(user, "verify", expires, signature);
        if (!user.email().equalsIgnoreCase(email)) throw new BusinessException("邮箱与账号不匹配");
        accounts.verify(uid);
    }

    private String signature(AccountUser user, String purpose, long expires) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String value = "account-mail\n" + purpose + "\n" + user.uid() + "\n" + user.email() + "\n" + user.password() + "\n" + expires;
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
}
