package top.twinklestars.koharuskin.site;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.time.Year;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;

@Service
public class SitePresentation {
    public static final Map<String,String> COLORS=Map.ofEntries(entry("primary","#007bff"),entry("secondary","#6c757d"),entry("success","#28a745"),
            entry("danger","#dc3545"),entry("indigo","#6610f2"),entry("purple","#6f42c1"),entry("pink","#e83e8c"),entry("teal","#20c997"),
            entry("cyan","#17a2b8"),entry("dark","#343a40"),entry("gray","#6c757d"),entry("fuchsia","#f012be"),entry("maroon","#d81b60"),
            entry("olive","#3d9970"),entry("navy","#001f3f"),entry("lime","#01ff70"),entry("light","#f8f9fa"),entry("warning","#ffc107"),
            entry("white","#ffffff"),entry("orange","#fd7e14"));
    private final SiteOptions options;
    private final SiteUrls urls;
    private final ObjectProvider<BuildProperties> build;
    private final Parser markdown;
    private final HtmlRenderer html;
    public SitePresentation(SiteOptions options,ObjectProvider<BuildProperties> build,SiteUrls urls) {
        this.options=options; this.build=build;this.urls=urls;
        var extensions=List.<org.commonmark.Extension>of(org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),org.commonmark.ext.autolink.AutolinkExtension.create());
        markdown=Parser.builder().extensions(extensions).build();
        // 公告和页脚由管理员编写，原站支持其中的 HTML。
        html=HtmlRenderer.builder().extensions(extensions).build();
    }
    public record Home(String picture,boolean fixed,boolean hideIntro,boolean transparent,String navbar,String powered,String copyright) {}
    public record Assets(String navbar,String themeColor,String keywords,String description,String meta,String cdn,boolean css,boolean js) {}
    public Assets assets() {
        String color=options.text("navbar_color","cyan");
        String meta=org.jsoup.Jsoup.parse(options.text("meta_extras","")).select("meta").outerHtml();
        return new Assets(navbar(),COLORS.get(color),options.text("meta_keywords",""),
                options.text("meta_description",options.localized("site_description","")),meta,
                options.text("cdn_address","").replaceAll("/+$",""),!options.text("custom_css","").isBlank(),!options.text("custom_js","").isBlank());
    }
    private String navbar() {
        String color=options.text("navbar_color","cyan");
        return "navbar-"+color+(List.of("light","warning","white","orange","lime").contains(color)?" navbar-light":" navbar-dark");
    }
    public Home home() {
        String picture=options.text("home_pic_url","/official/site/bg.webp");
        if(picture.isBlank()||picture.equals("./app/bg.webp")||picture.equals("app/bg.webp")) picture="/official/site/bg.webp";
        String powered=switch(options.localized("copyright_prefer","0")) {
            case "1"->"Powered by"; case "2"->"Proudly powered by"; case "3"->"强力驱动：";
            case "4"->"采用"; case "5"->"稳定运行于"; case "6"->"自豪地采用"; default->"Powered with ❤ by";
        };
        String copyright=options.localized("copyright_text","Copyright &copy; {year} {site_name}.")
                .replace("{year}",Year.now().toString()).replace("{site_name}",HtmlUtils.htmlEscape(options.localized("site_name","Koharu Skin")))
                .replace("{site_url}",HtmlUtils.htmlEscape(urls.publicUrl()));
        return new Home(picture,options.enabled("fixed_bg",false),options.enabled("hide_intro",false),
                options.enabled("transparent_navbar",false),navbar(),powered,copyright);
    }
    public String favicon() {
        String favicon=options.text("favicon_url","/official/site/favicon.ico");
        return favicon.equals("app/favicon.ico")||favicon.equals("./app/favicon.ico")?"/official/site/favicon.ico":favicon;
    }
    public String announcement() {
        var info=build.getIfAvailable();
        String value=options.localized("announcement","欢迎使用 Koharu Skin {version}！")
                .replace("{version}",info==null?"development":info.getVersion());
        return html.render(markdown.parse(value));
    }
}
