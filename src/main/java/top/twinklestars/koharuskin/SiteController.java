package top.twinklestars.koharuskin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteController {
    @GetMapping("/api/site")
    public SiteInfo getSiteInfo() {
        return new SiteInfo("Koharu Skin", "Minecraft 皮肤站", "v0.0.1");
    }

    public record SiteInfo(String name, String description, String version) {
    }
}
