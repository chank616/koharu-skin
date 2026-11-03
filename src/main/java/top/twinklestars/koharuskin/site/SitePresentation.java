package top.twinklestars.koharuskin.site;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.time.Year;
import java.util.List;

@Service
public class SitePresentation {
    private final SiteOptions options;
    private final ObjectProvider<BuildProperties> build;
    private final Parser markdown;
    private final HtmlRenderer html;
    public SitePresentation(SiteOptions options,ObjectProvider<BuildProperties> build) {
        this.options=options; this.build=build;
        var extensions=List.<org.commonmark.Extension>of(org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),org.commonmark.ext.autolink.AutolinkExtension.create());
        markdown=Parser.builder().extensions(extensions).build();
        // 公告和页脚由管理员编写，原站支持其中的 HTML。
        html=HtmlRenderer.builder().extensions(extensions).build();
    }
    public record Home(String picture,boolean fixed,boolean hideIntro,boolean transparent,String navbar,String powered,String copyright) {}
    public Home home() {
        String picture=options.text("home_pic_url","/official/site/bg.webp");
        if(picture.isBlank()||picture.equals("./app/bg.webp")||picture.equals("app/bg.webp")) picture="/official/site/bg.webp";
        String color=options.text("navbar_color","cyan");
        String navbar="navbar-"+color+(List.of("light","warning","white","orange","lime").contains(color)?" navbar-light":" navbar-dark");
        String powered=switch(options.localized("copyright_prefer","0")) {
            case "1"->"Powered by"; case "2"->"Proudly powered by"; case "3"->"强力驱动：";
            case "4"->"采用"; case "5"->"稳定运行于"; case "6"->"自豪地采用"; default->"Powered with ❤ by";
        };
        String copyright=options.localized("copyright_text","Copyright &copy; {year} {site_name}.")
                .replace("{year}",Year.now().toString()).replace("{site_name}",HtmlUtils.htmlEscape(options.localized("site_name","Koharu Skin")))
                .replace("{site_url}","/");
        return new Home(picture,options.enabled("fixed_bg",false),options.enabled("hide_intro",false),
                options.enabled("transparent_navbar",false),navbar,powered,copyright);
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
