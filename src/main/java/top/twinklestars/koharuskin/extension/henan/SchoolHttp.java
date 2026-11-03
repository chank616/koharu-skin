package top.twinklestars.koharuskin.extension.henan;

import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.account.BusinessException;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class SchoolHttp implements AutoCloseable {
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient direct,redirecting;
    private final JsonMapper json;
    private final URI login;
    SchoolHttp(URI login,JsonMapper json,Map<String,String> savedCookies) {
        this.login=login; this.json=json;
        savedCookies.forEach((name,value)->{
            var cookie=new HttpCookie(name,value); cookie.setPath("/"); cookie.setDomain(login.getHost()); cookie.setVersion(0);
            cookies.getCookieStore().add(login,cookie);
        });
        direct=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        redirecting=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NORMAL).build();
    }
    HttpResponse<String> get(URI uri) { return send(redirecting,request(uri).GET().build()); }
    HttpResponse<String> getAjax(URI uri) { return send(redirecting,ajax(request(uri)).GET().build()); }
    HttpResponse<String> form(URI uri,Map<String,String> values) {
        return form(uri,values,false);
    }
    HttpResponse<String> formAjax(URI uri,Map<String,String> values) { return form(uri,values,true); }
    private HttpResponse<String> form(URI uri,Map<String,String> values,boolean isAjax) {
        String encoded=values.entrySet().stream().map(entry->encode(entry.getKey())+"="+encode(entry.getValue())).collect(Collectors.joining("&"));
        var builder=post(uri).header("Content-Type","application/x-www-form-urlencoded");
        if(isAjax) ajax(builder);
        return send(direct,builder.POST(HttpRequest.BodyPublishers.ofString(encoded)).build());
    }
    HttpResponse<String> json(URI uri,Map<String,String> values) {
        return send(direct,post(uri).setHeader("Accept","application/json, text/plain, */*").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(values))).build());
    }
    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36")
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").header("Accept-Language","zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer",login.toString());
    }
    private HttpRequest.Builder post(URI uri) { return request(uri).header("Origin",login.getScheme()+"://"+login.getRawAuthority()); }
    private HttpRequest.Builder ajax(HttpRequest.Builder builder) { return builder.header("X-Requested-With","XMLHttpRequest").setHeader("Accept","application/json, text/javascript, */*; q=0.01"); }
    private HttpResponse<String> send(HttpClient client,HttpRequest request) {
        try { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
        catch(IOException error) { throw new BusinessException("学校认证服务暂时不可用，请稍后重试"); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new BusinessException("学校认证请求已中断，请重试"); }
    }
    JsonNode object(HttpResponse<String> response) {
        requireSuccess(response);
        try {
            var result=json.readTree(response.body());
            if(!result.isObject()) throw new BusinessException("学校认证服务返回了无法识别的数据");
            return result;
        } catch(JacksonException error) { throw new BusinessException("学校认证服务返回了无法识别的数据"); }
    }
    static void requireSuccess(HttpResponse<?> response) {
        if(response.statusCode()<200||response.statusCode()>=300) throw new BusinessException("学校认证服务暂时不可用，请稍后重试");
    }
    Map<String,String> cookies() {
        var values=new LinkedHashMap<String,String>();
        cookies.getCookieStore().getCookies().forEach(cookie->values.put(cookie.getName(),cookie.getValue()));
        return values;
    }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    static String input(String html,String name,String fallback) {
        var inputs=Pattern.compile("<input\\b[^>]*>",Pattern.CASE_INSENSITIVE).matcher(html);
        var namePattern=Pattern.compile("\\bname\\s*=\\s*(['\"])"+Pattern.quote(name)+"\\1",Pattern.CASE_INSENSITIVE);
        var valuePattern=Pattern.compile("\\bvalue\\s*=\\s*(['\"])(.*?)\\1",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        while(inputs.find()) if(namePattern.matcher(inputs.group()).find()) {
            var value=valuePattern.matcher(inputs.group());
            return value.find()?HtmlUtils.htmlUnescape(value.group(2)):fallback;
        }
        return fallback;
    }
    @Override public void close() { direct.close(); redirecting.close(); }
}
