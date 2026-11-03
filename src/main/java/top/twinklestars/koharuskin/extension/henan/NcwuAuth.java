package top.twinklestars.koharuskin.extension.henan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class NcwuAuth {
    private final String base;
    private final JsonMapper json;
    public NcwuAuth(@Value("${koharu.henan.ncwu-url:https://jwmis.ncwu.edu.cn/hsjw}") String base,JsonMapper json) {
        this.base=base.replaceAll("/+$",""); this.json=json;
    }
    public boolean login(String username,String password) {
        try(var http=new SchoolHttp(URI.create(base+"/cas/login.action"),json,Map.of())) {
            var page=http.get(URI.create(base+"/cas/login.action")); SchoolHttp.requireSuccess(page);
            String session=http.cookies().get("JSESSIONID");
            if(session==null) throw new BusinessException("华水教务系统未返回登录会话");
            String flag=SchoolHttp.input(page.body(),"hid_flag","1");
            if(!flag.equals("1")) throw new BusinessException("华水教务系统当前要求验证码，暂时无法自动认证");
            String key=sessionValue(http,"getTempDeskey"),timestamp=sessionValue(http,"getTempNowtime");
            boolean scan=Pattern.compile("\\bisXqeScanQrCode\\s*=\\s*true\\b",Pattern.CASE_INSENSITIVE).matcher(page.body()).find();
            String encryptedPassword=scan?md5(md5(password)+md5("")):password;
            int expression=0; byte[] passwordBytes=password.getBytes(StandardCharsets.UTF_8);
            for(byte value:passwordBytes) {
                int c=value&255;
                expression|=c>=48&&c<=57?8:c>=97&&c<=122?4:c>=65&&c<=90?2:1;
            }
            String raw="_u="+Base64.getEncoder().encodeToString((username+";;"+session).getBytes(StandardCharsets.UTF_8))+"&_p="+encryptedPassword
                    +"&randnumber=&isPasswordPolicy="+(!password.isEmpty()&&!password.equals(username)&&passwordBytes.length>=6?"1":"0")
                    +"&txt_mm_expression="+expression+"&txt_mm_length="+passwordBytes.length
                    +"&txt_mm_userzh="+(password.trim().toLowerCase(Locale.ROOT).contains(username.trim().toLowerCase(Locale.ROOT))?"1":"0")
                    +"&hid_flag="+flag+"&hidlag=1&hid_dxyzm="+SchoolHttp.input(page.body(),"hid_dxyzm","");
            String params=Base64.getEncoder().encodeToString(KingoDes.encrypt(raw,key).getBytes(StandardCharsets.US_ASCII));
            var result=http.object(http.formAjax(URI.create(base+"/cas/logon.action"),Map.of("params",params,"token",md5(md5(raw)+md5(timestamp)),"timestamp",timestamp,"deskey","","ssessionid",session)));
            return switch(result.path("status").asString()) {
                case "200"->true;
                case "401","402","403","404"->false;
                case "405","406","407","505"->throw new BusinessException("华水教务系统要求额外验证，暂时无法自动认证");
                default->throw new BusinessException("华水教务系统返回了无法识别的登录结果");
            };
        } catch(GeneralSecurityException error) { throw new BusinessException("无法加密华水登录参数"); }
    }
    private String sessionValue(SchoolHttp http,String method) {
        var response=http.get(URI.create(base+"/frame/homepage?method="+method)); SchoolHttp.requireSuccess(response);
        String value=response.body().trim();
        if(value.isEmpty()) throw new BusinessException("华水教务系统未返回临时认证参数");
        return value;
    }
    static String md5(String value) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
