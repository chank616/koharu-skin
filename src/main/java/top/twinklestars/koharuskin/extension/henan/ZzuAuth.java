package top.twinklestars.koharuskin.extension.henan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.BusinessException;
import javax.crypto.Cipher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class ZzuAuth {
    private final String base;
    private final JsonMapper json;
    public ZzuAuth(@Value("${koharu.henan.zzu-url:https://cas.s.zzu.edu.cn}") String base,JsonMapper json) {
        this.base=base.replaceAll("/+$",""); this.json=json;
    }
    public record Challenge(String execution,String encryptedPassword,String state,Map<String,String> cookies,String server,String gid) implements java.io.Serializable {}
    public record Login(boolean authenticated,Challenge challenge,String destination) {}
    public Login login(String username,String password) {
        try(var http=session(Map.of())) {
            var page=http.get(uri("/cas/a/login")); SchoolHttp.requireSuccess(page);
            String execution=SchoolHttp.input(page.body(),"execution","");
            if(execution.isEmpty()) throw new BusinessException("郑州大学统一认证登录页缺少会话令牌");
            var keyResponse=http.get(uri("/cas/jwt/publicKey")); SchoolHttp.requireSuccess(keyResponse);
            String key=keyResponse.body().replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            var publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
            var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE,publicKey);
            String encrypted="__RSA__"+Base64.getEncoder().encodeToString(rsa.doFinal(password.getBytes(StandardCharsets.UTF_8)));
            var detected=data(http,http.formAjax(uri("/cas/mfa/detect"),Map.of("username",username,"password",encrypted,"fpVisitorId","")));
            String state=required(detected,"state");
            if(detected.path("need").asBoolean(false)) {
                if(!detected.path("mfaTypeSecurePhone").asBoolean(false)) throw new BusinessException("学校未提供可用的短信安全验证方式");
                var initialized=data(http,http.getAjax(uri("/cas/mfa/initByType/securephone?state="+SchoolHttp.encode(state))));
                String server=server(required(initialized,"attestServerUrl"));
                var challenge=new Challenge(execution,encrypted,state,http.cookies(),server,required(initialized,"gid"));
                return new Login(false,challenge,required(initialized,"securePhone"));
            }
            var context=new Challenge(execution,encrypted,state,http.cookies(),"","");
            return new Login(submit(username,context,http),null,"");
        } catch(GeneralSecurityException|IllegalArgumentException error) {
            throw new BusinessException("无法加密郑州大学统一认证密码");
        }
    }
    public Challenge send(Challenge challenge) {
        try(var http=session(challenge.cookies())) {
            var result=mfa(http,challenge,"/api/guard/securephone/send",Map.of("gid",challenge.gid()));
            if(!result.path("code").asString().equals("0")) throw new BusinessException("学校短信验证码发送失败，请稍后重试");
            return withCookies(challenge,http.cookies());
        }
    }
    public enum Verification { INVALID_CODE, LOGIN_REJECTED, AUTHENTICATED }
    public Verification verifyAndLogin(String username,Challenge challenge,String code) {
        try(var http=session(challenge.cookies())) {
            var result=mfa(http,challenge,"/api/guard/securephone/valid",Map.of("gid",challenge.gid(),"code",code));
            if(result.path("data").path("result").asString().equals("expired")) throw new BusinessException("学校短信验证已过期，请重新登录");
            if(!result.path("code").asString().equals("0")||!result.path("data").path("status").asString().equals("2")) return Verification.INVALID_CODE;
            return submit(username,withCookies(challenge,http.cookies()),http)?Verification.AUTHENTICATED:Verification.LOGIN_REJECTED;
        }
    }
    private JsonNode mfa(SchoolHttp http,Challenge challenge,String path,Map<String,String> values) {
        var result=http.object(http.json(URI.create(server(challenge.server())+path),values));
        if(!result.has("code")) throw new BusinessException("学校短信验证返回了无法识别的结果");
        return result;
    }
    private boolean submit(String username,Challenge challenge,SchoolHttp http) {
        var fields=new LinkedHashMap<String,String>();
        fields.put("username",username); fields.put("password",challenge.encryptedPassword()); fields.put("captcha",""); fields.put("currentMenu","1");
        fields.put("failN","0"); fields.put("mfaState",challenge.state()); fields.put("execution",challenge.execution()); fields.put("_eventId","submit");
        fields.put("geolocation",""); fields.put("fpVisitorId",""); fields.put("trustAgent",""); fields.put("submit1","Login1");
        var response=http.form(uri("/cas/a/login"),fields);
        String body=response.body(),errors="";
        var matcher=Pattern.compile("\\bvar\\s+errors\\s*=\\s*(\\[[^;]*\\])\\s*;",Pattern.CASE_INSENSITIVE).matcher(body);
        if(matcher.find()) {
            try {
                var messages=json.readTree(matcher.group(1));
                if(messages.isArray()) errors=messages.valueStream().map(value->value.asString()).collect(java.util.stream.Collectors.joining("\n"));
            } catch(JacksonException ignored) { /* 认证页的错误数组不是有效 JSON 时按未知结果处理。 */ }
        }
        if(response.statusCode()==401&&Pattern.compile("账号或密码错误|用户名或密码错误|账号不存在|用户不存在|密码错误").matcher(errors).find()) return false;
        if(http.cookies().containsKey("TGC")||http.cookies().containsKey("CASTGC")) return true;
        if(response.statusCode()>=300&&response.statusCode()<400&&response.headers().firstValue("Location").orElse("").contains("ticket=ST-")) return true;
        if(response.statusCode()>=200&&response.statusCode()<300&&!Pattern.compile("\\bid\\s*=\\s*['\"]fm1['\"]",Pattern.CASE_INSENSITIVE).matcher(body).find()&&body.contains("登录成功")) return true;
        if(errors.contains("验证码")) throw new BusinessException("郑州大学统一认证当前要求验证码，暂时无法自动认证");
        throw new BusinessException("郑州大学统一认证返回了无法识别的登录结果");
    }
    private JsonNode data(SchoolHttp http,java.net.http.HttpResponse<String> response) {
        var root=http.object(response);
        if(!root.path("code").asString().equals("0")||!root.path("data").isObject()) throw new BusinessException("郑州大学统一认证返回了无法识别的数据");
        return root.path("data");
    }
    private String required(JsonNode node,String field) {
        String value=node.path(field).asString("");
        if(value.isEmpty()) throw new BusinessException("郑州大学统一认证未返回必要的认证信息");
        return value;
    }
    private String server(String value) {
        String url=value.replaceAll("/+$","");
        if(url.equals("/attest")||url.equals("/mf")) url=base+url;
        if(!url.equals(base+"/attest")&&!url.equals(base+"/mf")) throw new BusinessException("学校返回了无效的短信验证服务器地址");
        return url;
    }
    private Challenge withCookies(Challenge previous,Map<String,String> cookies) { return new Challenge(previous.execution(),previous.encryptedPassword(),previous.state(),cookies,previous.server(),previous.gid()); }
    private SchoolHttp session(Map<String,String> cookies) { return new SchoolHttp(uri("/cas/a/login"),json,cookies); }
    private URI uri(String path) { return URI.create(base+path); }
}
