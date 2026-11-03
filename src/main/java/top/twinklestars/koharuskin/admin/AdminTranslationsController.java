package top.twinklestars.koharuskin.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import top.twinklestars.koharuskin.site.SiteTranslations;
import java.util.Map;

@Controller
@RequestMapping("/admin/i18n")
public class AdminTranslationsController {
    private final SiteTranslations translations;
    public AdminTranslationsController(SiteTranslations translations) { this.translations=translations; }
    @GetMapping("/list") @ResponseBody
    public Map<String,Object> list(@RequestParam(defaultValue="1") int page) { return translations.page(page); }
    @PostMapping
    public String create(@Valid @ModelAttribute Form form,BindingResult errors,RedirectAttributes flash) {
        if(errors.hasErrors()) { flash.addFlashAttribute("translationError","请填写分组、键和文本，分组与键不能超过 255 字符");flash.addFlashAttribute("translationForm",form); }
        else { translations.create(form.group(),form.key(),form.text());flash.addFlashAttribute("translationAdded",true); }
        return "redirect:/admin/i18n";
    }
    @PutMapping("/{id}") @ResponseBody
    public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody Text form) {
        translations.update(id,form.text());return Map.of("code",0,"message",translations.text("admin.i18n.updated"));
    }
    @DeleteMapping("/{id}") @ResponseBody
    public Map<String,Object> delete(@PathVariable long id) {
        translations.delete(id);return Map.of("code",0,"message",translations.text("admin.i18n.deleted"));
    }
    public record Form(@NotBlank @Size(max=255) String group,@NotBlank @Size(max=255) String key,@NotBlank String text) {}
    public record Text(@NotBlank String text) {}
}
