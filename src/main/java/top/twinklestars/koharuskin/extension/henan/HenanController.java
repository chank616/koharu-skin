package top.twinklestars.koharuskin.extension.henan;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import top.twinklestars.koharuskin.account.*;
import java.util.*;

@Controller
public class HenanController {
    private final HenanLogin login;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository contexts;
    public HenanController(HenanLogin login,SessionAuthenticationStrategy sessions,SecurityContextRepository contexts) {
        this.login=login; this.sessions=sessions; this.contexts=contexts;
    }
    public record Credentials(@NotNull @Pattern(regexp="ncwu|zzu") String school,@NotBlank @Size(max=255) String identification,
                              @NotBlank String password,String site_password,String player_name,String captcha) {}
    @PostMapping("/auth/{operation:login|register}/henan")
    public String authenticate(@PathVariable String operation,@Valid @ModelAttribute Credentials input,BindingResult errors,
                               @AuthenticationPrincipal AccountUser current,HttpServletRequest request,HttpServletResponse response,RedirectAttributes flash) {
        login.requireEnabled(); if(current!=null) return "redirect:/user";
        String target="redirect:/auth/"+operation+"/henan";
        flash.addFlashAttribute("henanForm",form(input.school(),input.identification(),input.player_name()));
        if(errors.hasErrors()) { flash.addFlashAttribute("henanError","请填写学校、学号和学校密码"); return target; }
        try {
            if(operation.equals("register")&&!CaptchaController.verify(request.getSession(),input.captcha())) throw new BusinessException("请输入正确的图片验证码");
            var result=login.start(operation,input.school(),input.identification(),input.password(),input.site_password(),input.player_name(),request.getRemoteAddr(),request.getSession());
            if(result.token()!=null) return "redirect:/auth/mfa/henan/"+result.token();
            establish(result.user(),request,response); return "redirect:/user";
        } catch(BusinessException error) { flash.addFlashAttribute("henanError",error.getMessage()); return target; }
    }
    @PostMapping("/auth/mfa/henan/{token:[a-f0-9]{64}}/{action:send|verify|cancel}")
    public String mfa(@PathVariable String token,@PathVariable String action,@RequestParam(required=false) String code,
                      @AuthenticationPrincipal AccountUser current,HttpServletRequest request,HttpServletResponse response,RedirectAttributes flash) {
        login.requireEnabled(); if(current!=null) return "redirect:/user";
        var session=request.getSession();
        if(action.equals("cancel")) return "redirect:/auth/"+login.cancel(session,token)+"/henan";
        var pending=login.pending(session,token);
        try {
            if(action.equals("send")) { login.send(session,token); flash.addFlashAttribute("henanMessage","短信验证码已发送，请查看学校绑定的手机"); }
            else {
                if(code==null||!code.matches("[0-9]{4,8}")) throw new BusinessException("请输入 4～8 位数字验证码");
                var account=login.verify(session,token,code,request.getRemoteAddr()); establish(account,request,response); return "redirect:/user";
            }
        } catch(HenanLogin.Expired error) {
            flash.addFlashAttribute("henanError",error.getMessage()); return "redirect:/auth/login/henan";
        } catch(BusinessException error) {
            flash.addFlashAttribute("henanError",error.getMessage());
            if(pending!=null&&login.pending(session,token)==null) {
                flash.addFlashAttribute("henanForm",form(pending.school(),pending.identification(),pending.playerName()));
                return "redirect:/auth/"+pending.operation()+"/henan";
            }
        }
        return "redirect:/auth/mfa/henan/"+token;
    }
    private Map<String,String> form(String school,String identification,String playerName) {
        var values=new LinkedHashMap<String,String>(); values.put("school",school); values.put("identification",identification); values.put("player_name",playerName); return values;
    }
    private void establish(AccountUser user,HttpServletRequest request,HttpServletResponse response) {
        var authentication=UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities());
        sessions.onAuthentication(authentication,request,response);
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
    }
}
