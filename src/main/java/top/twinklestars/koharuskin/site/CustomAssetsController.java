package top.twinklestars.koharuskin.site;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomAssetsController {
    private final SiteOptions options;
    public CustomAssetsController(SiteOptions options) { this.options=options; }
    // 单独作为资源输出，JS 中的 HTML 字符串不会截断页面的 script/style 标签。
    @GetMapping(value="/official/site/custom.css",produces="text/css;charset=UTF-8")
    public ResponseEntity<String> css() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options.text("custom_css","")); }
    @GetMapping(value="/official/site/custom.js",produces="text/javascript;charset=UTF-8")
    public ResponseEntity<String> js() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options.text("custom_js","")); }
}
