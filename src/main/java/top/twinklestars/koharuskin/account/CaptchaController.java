package top.twinklestars.koharuskin.account;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.SecureRandom;

@RestController
public class CaptchaController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @GetMapping("/auth/captcha")
    public void captcha(HttpSession session, HttpServletResponse response) throws IOException {
        var answer = new StringBuilder();
        for (int i = 0; i < 5; i++) answer.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        session.setAttribute("captcha", answer.toString());
        var image = new BufferedImage(130, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 130, 40);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        graphics.setColor(new Color(40, 65, 100));
        for (int i = 0; i < 5; i++) graphics.drawString(answer.substring(i, i + 1), 8 + i * 24, 28 + RANDOM.nextInt(5));
        for (int i = 0; i < 6; i++) graphics.drawLine(RANDOM.nextInt(130), RANDOM.nextInt(40), RANDOM.nextInt(130), RANDOM.nextInt(40));
        graphics.dispose();
        response.setContentType("image/png");
        response.setHeader("Cache-Control", "no-store");
        ImageIO.write(image, "png", response.getOutputStream());
    }

    static boolean verify(HttpSession session, String submitted) {
        String answer = (String) session.getAttribute("captcha");
        session.removeAttribute("captcha");
        return answer != null && answer.equalsIgnoreCase(submitted);
    }
}
