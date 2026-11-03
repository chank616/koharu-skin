package top.twinklestars.koharuskin.texture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.closet.ClosetMapper;
import top.twinklestars.koharuskin.player.PlayerMapper;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.site.SiteOptions;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class TextureService {
    private final TextureMapper textures;
    private final ClosetMapper closet;
    private final PlayerMapper users;
    private final PlayerService players;
    private final SiteOptions options;
    private final Path directory;
    private final TransactionTemplate cleanup;

    public TextureService(TextureMapper textures, ClosetMapper closet, PlayerMapper users, PlayerService players,
                          SiteOptions options, @Value("${koharu.textures:storage/textures}") String directory,
                          PlatformTransactionManager transactions) {
        this.textures = textures; this.closet = closet; this.users = users; this.players = players; this.options = options;
        this.directory = Path.of(directory);
        cleanup = new TransactionTemplate(transactions);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Texture readable(int tid, AccountUser user) {
        var texture = textures.find(tid);
        if (texture == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材质不存在");
        if (!texture.visible() && (user == null || (user.uid() != texture.uploader() && user.permission() < 1))) {
            throw new ResponseStatusException(options.number("status_code_for_private", 403) == 404 ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN);
        }
        return texture;
    }

    public Texture view(int tid, AccountUser user) {
        var texture=readable(tid,user);
        if(Files.isRegularFile(directory.resolve(texture.hash()))) return texture;
        if(options.enabled("auto_del_invalid_texture",false)) cleanup.executeWithoutResult(status -> {
            options.lockRegistration();
            var missing=readable(tid,user);
            if(Files.notExists(directory.resolve(missing.hash()))) {
                // 原站自动清理不会触发删除积分结算；只解除失效材质的引用。
                for(long uid:textures.collectors(tid)) closet.remove(uid,tid);
                textures.clearPlayers(tid,-1);
                textures.clearAvatars(tid);
                textures.delete(tid);
            }
        });
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"材质文件不存在");
    }

    private Texture owned(int tid, AccountUser user) {
        players.requireVerified(user.uid());
        var texture = readable(tid, user);
        if (texture.uploader() != user.uid() && user.permission() < 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改此材质");
        return texture;
    }

    @Transactional(rollbackFor = IOException.class)
    public Map<String, Object> upload(AccountUser user, String name, String type, boolean visible, MultipartFile file) throws IOException {
        return upload(user,name,type,visible,file,false);
    }

    @Transactional(rollbackFor = IOException.class)
    public int uploadPrivate(AccountUser user, String name, String type, MultipartFile file) throws IOException {
        var result=upload(user,name,type,false,file,true);
        return ((Number)((Map<?,?>)result.get("data")).get("tid")).intValue();
    }

    private Map<String,Object> upload(AccountUser user,String name,String type,boolean visible,MultipartFile file,boolean ownOnly) throws IOException {
        players.requireVerified(user.uid());
        validateName(name);
        if (file.isEmpty() || file.getSize() > options.number("max_upload_file_size", 1024) * 1024L) throw new BusinessException("文件为空或超过站点大小限制");
        byte[] original = file.getBytes();
        var input = ImageIO.createImageInputStream(new ByteArrayInputStream(original));
        byte[] sanitized;
        try (input) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("请上传有效的 PNG 图片");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw new BusinessException("仅支持 PNG 图片");
                reader.setInput(input);
                dimensions(reader.getWidth(0), reader.getHeight(0), type);
                var image = reader.read(0);
                var output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                sanitized = output.toByteArray();
            } finally { reader.dispose(); }
        }
        String hash = hash(sanitized);
        options.lockRegistration();
        Texture duplicate = ownOnly?textures.ownedDuplicate(hash(original),user.uid(),type):textures.duplicate(hash(original), user.uid());
        if (duplicate == null) duplicate = ownOnly?textures.ownedDuplicate(hash,user.uid(),type):textures.duplicate(hash, user.uid());
        if (duplicate != null) return duplicate(duplicate.tid());
        int size = (sanitized.length + 1023) / 1024;
        int cost = size * options.number(visible ? "score_per_storage" : "private_score_per_storage", visible ? 1 : 10)
                + options.number("score_per_closet_item", 0) - (visible ? options.number("score_award_per_texture", 0) : 0);
        if (users.lockScore(user.uid()) < cost) throw new BusinessException("积分不足，无法上传");
        Files.createDirectories(directory);
        Path destination = directory.resolve(hash);
        if (!Files.exists(destination)) Files.write(destination, sanitized, StandardOpenOption.CREATE_NEW);
        Map<String, Object> row = new HashMap<>(Map.of("name", name, "type", type, "hash", hash, "size", size, "uploader", user.uid(), "visible", visible));
        textures.insert(row);
        int tid = ((Number) row.get("tid")).intValue();
        closet.add(user.uid(), tid, name);
        users.changeScore(user.uid(), -cost);
        return Map.of("code", 0, "message", "材质上传成功", "data", Map.of("tid", tid));
    }

    @Transactional
    public void rename(AccountUser user, int tid, String name) {
        owned(tid, user);
        validateName(name);
        textures.rename(tid, name);
    }

    @Transactional(rollbackFor = IOException.class)
    public void type(AccountUser user, int tid, String type) throws IOException {
        options.lockRegistration();
        var texture = owned(tid, user);
        var image = ImageIO.read(directory.resolve(texture.hash()).toFile());
        dimensions(image.getWidth(), image.getHeight(), type);
        if (texture.type().equals("cape") != type.equals("cape")) textures.clearPlayers(tid, -1);
        textures.type(tid, type);
    }

    @Transactional
    public Map<String, Object> privacy(AccountUser user, int tid) {
        options.lockRegistration();
        var texture = owned(tid, user);
        if (!texture.visible()) {
            var duplicate = textures.duplicate(texture.hash(), -1);
            if (duplicate != null) return duplicate(duplicate.tid());
        }
        int difference = texture.size() * (options.number("private_score_per_storage", 10) - options.number("score_per_storage", 1))
                * (texture.visible() ? -1 : 1);
        if (texture.visible() && options.enabled("take_back_scores_after_deletion", true)) difference -= options.number("score_award_per_texture", 0);
        if (users.lockScore(texture.uploader()) + difference < 0) throw new BusinessException("积分不足，无法切换私密状态");
        users.changeScore(texture.uploader(), difference);
        textures.privacy(tid, !texture.visible());
        if (texture.visible()) {
            clearCollectors(texture, false);
            textures.clearPlayers(tid, texture.uploader());
        }
        return Map.of("code", 0, "message", texture.visible() ? "材质已设为私密" : "材质已公开");
    }

    @Transactional
    public void delete(AccountUser user, int tid) {
        options.lockRegistration();
        var texture = owned(tid, user);
        int refund = options.enabled("return_score", true) ? texture.size() * options.number(texture.visible() ? "score_per_storage" : "private_score_per_storage", texture.visible() ? 1 : 10) : 0;
        if (texture.visible() && options.enabled("take_back_scores_after_deletion", true)) refund -= options.number("score_award_per_texture", 0);
        users.changeScore(texture.uploader(), refund);
        clearCollectors(texture, true);
        textures.clearPlayers(tid, -1);
        textures.clearAvatars(tid);
        textures.delete(tid);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                cleanup.executeWithoutResult(status -> {
                    options.lockRegistration();
                    if (textures.countHash(texture.hash()) == 0) {
                        try { Files.deleteIfExists(directory.resolve(texture.hash())); }
                        catch (IOException error) { throw new UncheckedIOException(error); }
                    }
                });
            }
        });
    }

    private void clearCollectors(Texture texture, boolean deleting) {
        for (long uid : textures.collectors(texture.tid())) {
            if (!deleting && uid == texture.uploader()) continue;
            closet.remove(uid, texture.tid());
            if (options.enabled("return_score", true)) users.changeScore(uid, options.number("score_per_closet_item", 0));
            if (!deleting) closet.likes(texture.tid(), -1);
        }
    }

    public void avatar(AccountUser user, int tid) {
        if (tid != 0 && readable(tid, user).type().equals("cape")) throw new BusinessException("披风不能设为头像");
        textures.avatar(user.uid(), tid);
    }

    private void validateName(String name) {
        if (name.isBlank() || name.length() > 50) throw new BusinessException("材质名称不能为空且不能超过 50 个字符");
        String regex = options.text("texture_name_regexp", "");
        if (regex.startsWith("/") && regex.lastIndexOf('/') > 0) regex = regex.substring(1, regex.lastIndexOf('/'));
        if (!regex.isEmpty() && !java.util.regex.Pattern.compile(regex).matcher(name).find()) throw new BusinessException("材质名称不符合站点规则");
    }

    private void dimensions(int width, int height, String type) {
        if (!Set.of("steve", "alex", "cape").contains(type)) throw new BusinessException("无效的材质类型");
        boolean square = width == height;
        boolean rectangle = width == height * 2;
        if (width > options.number("max_texture_width", 8192) || width < 64 || width % 64 != 0 || height % 32 != 0
                || (type.equals("alex") ? !square : type.equals("cape") ? !rectangle : !(square || rectangle))) {
            throw new BusinessException("图片尺寸不符合皮肤或披风格式");
        }
    }

    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private Map<String, Object> duplicate(int tid) { return Map.of("code", 2, "message", "此材质已上传", "data", Map.of("tid", tid)); }
}
