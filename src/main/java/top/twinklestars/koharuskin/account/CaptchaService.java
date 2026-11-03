package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class CaptchaService {
    private final SiteOptions options;
    private final JsonMapper json;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    public CaptchaService(SiteOptions options,JsonMapper json,
                          @Value("${koharu.recaptcha-verify-url:https://www.recaptcha.net/recaptcha/api/siteverify}") URI endpoint) {
        this.options=options; this.json=json; this.endpoint=endpoint;
    }
    public String siteKey() { return options.text("recaptcha_secretkey","").isBlank()?"":options.text("recaptcha_sitekey",""); }
    public boolean verify(HttpSession session,String response) {
        String secret=options.text("recaptcha_secretkey","");
        if(secret.isBlank()) return CaptchaController.verify(session,response);
        if(response==null||response.isBlank()) return false;
        String form="secret="+URLEncoder.encode(secret,StandardCharsets.UTF_8)+"&response="+URLEncoder.encode(response,StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            var result=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200) throw unavailable();
            var success=json.readTree(result.body()).path("success");
            return success.isBoolean()&&success.asBoolean();
        } catch(IOException|tools.jackson.core.JacksonException error) { throw unavailable(); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException("人机验证服务暂时不可用，请稍后重试"); }
}
