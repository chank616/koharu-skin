package top.twinklestars.koharuskin.texture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import top.twinklestars.koharuskin.account.AccountUser;

@RestController
public class TextureController {
    private final TextureMapper textures;
    private final SiteOptions options;
    private final Path directory;
    public TextureController(TextureMapper textures, SiteOptions options, @Value("${koharu.textures:storage/textures}") String directory) {
        this.textures = textures;
        this.options = options;
        this.directory = Path.of(directory);
    }

    @GetMapping({"/skinlib/info/{tid}", "/texture/{tid}"})
    public Texture info(@PathVariable int tid, @AuthenticationPrincipal AccountUser user) {
        Texture texture = find(tid);
        if (!texture.visible() && (user == null || (user.uid() != texture.uploader() && user.permission() < 1))) {
            throw new ResponseStatusException(options.number("status_code_for_private", 403) == 404 ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN);
        }
        return texture;
    }

    private Texture find(int tid) {
        Texture texture = textures.find(tid);
        if (texture == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材质不存在");
        return texture;
    }

    @GetMapping({"/textures/{hash}", "/csl/textures/{hash}"})
    public ResponseEntity<Resource> file(@PathVariable String hash) throws IOException {
        if (!hash.matches("[a-fA-F0-9]{64}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Path file = directory.resolve(hash);
        if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(Files.size(file))
                .lastModified(Files.getLastModifiedTime(file).toMillis()).eTag(hash).body(new FileSystemResource(file));
    }

    @GetMapping("/raw/{tid}")
    public ResponseEntity<Resource> raw(@PathVariable int tid) throws IOException {
        if (!options.enabled("allow_downloading_texture", true)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return file(find(tid).hash());
    }

    @GetMapping(value = "/preview/{tid}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] preview(@PathVariable int tid, @RequestParam(defaultValue = "200") @Min(16) @Max(1024) int height) throws IOException {
        Texture texture = find(tid);
        var skin = ImageIO.read(directory.resolve(texture.hash()).toFile());
        return png(SkinImages.preview(skin, texture.type(), height));
    }

    @GetMapping(value = "/avatar/{tid}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] avatar(@PathVariable int tid, @RequestParam(defaultValue = "100") @Min(8) @Max(512) int size,
                         @RequestParam java.util.Map<String, String> query) throws IOException {
        Texture texture = textures.find(tid);
        java.awt.image.BufferedImage skin;
        if (texture == null || !Files.isRegularFile(directory.resolve(texture.hash()))) {
            try (var stream = new ClassPathResource("static/defaults/steve.png").getInputStream()) { skin = ImageIO.read(stream); }
        } else {
            if (texture.type().equals("cape")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            skin = ImageIO.read(directory.resolve(texture.hash()).toFile());
        }
        return png(SkinImages.avatar(skin, size, query.containsKey("3d")));
    }

    private byte[] png(java.awt.image.BufferedImage image) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
