package top.twinklestars.koharuskin.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import top.twinklestars.koharuskin.texture.TextureRenders;

@Controller
public class AdminResourceController {
    private final TextureRenders renders;
    public AdminResourceController(TextureRenders renders) { this.renders=renders; }

    @PostMapping("/admin/resource/clear-cache")
    public String clear(RedirectAttributes flash) {
        renders.clear();flash.addFlashAttribute("cacheCleared",true);
        return "redirect:/admin/resource";
    }
}
