package top.twinklestars.koharuskin.texture;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.site.SiteOptions;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Service
public class TextureRenders {
    public record Image(byte[] bytes,String etag) {}
    private record Key(String hash,String type,int size,boolean avatar,boolean threeD,long modified) {}
    private final SiteOptions options;
    private final Path directory;
    private final ClassPathResource defaultSkin=new ClassPathResource("static/defaults/steve.png");
    private final LoadingCache<Key,Image> cache=Caffeine.newBuilder().maximumWeight(32*1024*1024)
            .weigher((Key key,Image value)->value.bytes().length).expireAfterWrite(Duration.ofDays(365)).build(this::render);

    public TextureRenders(SiteOptions options,@Value("${koharu.textures:storage/textures}") String directory) {
        this.options=options;this.directory=Path.of(directory);
    }
    public Image preview(Texture texture,int height) throws IOException {
        var path=directory.resolve(texture.hash());
        if(!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"材质文件不存在");
        var key=new Key(texture.hash(),texture.type(),height,false,false,Files.getLastModifiedTime(path).toMillis());
        return options.enabled("enable_preview_cache",false)?cache.get(key):render(key);
    }
    public Image avatar(Texture texture,int size,boolean threeD) throws IOException {
        if(texture!=null&&texture.type().equals("cape")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        boolean fallback=texture==null||!Files.isRegularFile(directory.resolve(texture.hash()));
        var key=new Key(fallback?"default":texture.hash(),"avatar",size,true,threeD,
                fallback?defaultSkin.lastModified():Files.getLastModifiedTime(directory.resolve(texture.hash())).toMillis());
        return options.enabled("enable_avatar_cache",false)?cache.get(key):render(key);
    }
    private Image render(Key key) throws IOException {
        java.awt.image.BufferedImage skin;
        if(key.hash().equals("default")) {
            try(var stream=defaultSkin.getInputStream()) { skin=ImageIO.read(stream); }
        } else skin=ImageIO.read(directory.resolve(key.hash()).toFile());
        var image=key.avatar()?SkinImages.avatar(skin,key.size(),key.threeD()):SkinImages.preview(skin,key.type(),key.size());
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
        byte[] bytes=output.toByteArray();
        return new Image(bytes,DigestUtils.md5DigestAsHex(bytes));
    }
    public void clear() { cache.invalidateAll(); }
}
