package top.twinklestars.koharuskin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.util.Map;

@RestController
public class SiteController {
    private final SiteOptions options;
    private final ObjectProvider<BuildProperties> build;
    public SiteController(SiteOptions options,ObjectProvider<BuildProperties> build) { this.options=options; this.build=build; }

    @RequestMapping({"/api","/api/"})
    public Map<String,Object> apiRoot() {
        return Map.of("blessing_skin",version(),"koharu_skin",version(),"spec",0,"copyright","Powered by Koharu Skin.",
                "site_name",options.text("site_name","Koharu Skin"));
    }

    @GetMapping("/api/site")
    public SiteInfo getSiteInfo() {
        return new SiteInfo(options.text("site_name","Koharu Skin"),options.text("site_description","Minecraft 皮肤站"),version());
    }

    private String version() { var info=build.getIfAvailable(); return info==null?"development":info.getVersion(); }

    public record SiteInfo(String name, String description, String version) {
    }
}
