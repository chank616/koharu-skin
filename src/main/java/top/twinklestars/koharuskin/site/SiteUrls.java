package top.twinklestars.koharuskin.site;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;

@Service
@DependsOnDatabaseInitialization
public class SiteUrls {
    private final SiteOptions options;
    private final String override;
    public SiteUrls(SiteOptions options,@Value("${koharu.public-url:}") String override) {
        this.options=options;this.override=override.isBlank()?"":normalize(override);
    }
    public boolean overridden() { return !override.isEmpty(); }
    public boolean configured() { return overridden()||!options.text("site_url","").isBlank(); }
    public String publicUrl() {
        if(overridden()) return override;
        String stored=options.text("site_url","");
        String root=normalize(stored.isBlank()?"http://localhost:8080":stored);
        return options.enabled("force_ssl",false)?https(root):root;
    }
    public String webBase(HttpServletRequest request) {
        if(!options.enabled("auto_detect_asset_url",true)) return publicUrl();
        if(options.enabled("force_ssl",false)&&!request.isSecure()) {
            var root=UriComponentsBuilder.newInstance().scheme("https").host(request.getServerName())
                    .port(request.getServerPort()==80?-1:request.getServerPort()).path(request.getContextPath());
            return root.build().toUriString();
        }
        return request.getContextPath();
    }
    public static String webLink(String base,String context,String url) {
        if(!url.startsWith("/")||url.startsWith("//")) return url;
        String path=!context.isEmpty()&&(url.equals(context)||url.startsWith(context+"/"))?url.substring(context.length()):url;
        return base+path;
    }
    public static String normalize(String value) {
        var uri=URI.create(value.trim());
        if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null
                ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("站点地址必须是完整 HTTP(S) 地址，不能带账号、查询参数或片段");
        return uri.normalize().toString().replaceAll("/+$","");
    }
    private String https(String url) { return url.replaceFirst("(?i)^http:","https:"); }
}
