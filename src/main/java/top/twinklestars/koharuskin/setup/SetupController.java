package top.twinklestars.koharuskin.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/setup")
public class SetupController {
    private final SetupService setup;
    private final top.twinklestars.koharuskin.site.SiteUrls urls;
    public SetupController(SetupService setup,top.twinklestars.koharuskin.site.SiteUrls urls) { this.setup=setup;this.urls=urls; }
    public record Form(@NotBlank @Size(max=100) String siteName,@NotBlank @Email @Size(max=100) String email,
                       @NotBlank @Size(max=50) String nickname,@NotBlank @Size(min=8,max=32) String password,@NotBlank String confirmation) {}
    @GetMapping({"","/"}) public String page(Model model) {
        setup.requirePending();model.addAttribute("form",new Form("Koharu Skin","","","",""));return "setup";
    }
    @PostMapping({"","/"}) public String install(@Valid @ModelAttribute("form") Form form,BindingResult errors,HttpServletRequest request) {
        setup.requirePending();
        if(!java.util.Objects.equals(form.password(),form.confirmation())) errors.rejectValue("confirmation","confirmation","两次输入的密码不一致");
        if(errors.hasErrors()) return "setup";
        String root=urls.overridden()?urls.publicUrl():org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        setup.install(form.siteName(),form.email(),form.nickname(),form.password(),request.getRemoteAddr(),root);
        return "redirect:/auth/login";
    }
}
