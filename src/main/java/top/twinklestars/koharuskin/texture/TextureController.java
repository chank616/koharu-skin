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
    public Texture info(@PathVariable int tid) {
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
        return file(info(tid).hash());
    }
}
