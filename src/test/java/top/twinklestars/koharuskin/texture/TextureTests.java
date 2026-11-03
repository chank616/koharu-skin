package top.twinklestars.koharuskin.texture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.account.BusinessException;
import top.twinklestars.koharuskin.closet.ClosetService;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.player.PlayerMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TextureTests {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("koharu.textures", () -> storage.toString()); }
    @Autowired TextureService service;
    @Autowired TextureMapper textures;
    @Autowired ClosetService closet;
    @Autowired PlayerService players;
    @Autowired PlayerMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TextureRenders renders;
    private final AccountUser owner = new AccountUser(1,"owner@example.invalid","Owner","unused",0);
    private final AccountUser other = new AccountUser(2,"other@example.invalid","Other","unused",0);

    @BeforeEach void fixture() {
        renders.clear();
        for (String table : new String[]{"user_closet","textures","uuid","players","users","options"}) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('score_per_closet_item','2'),('score_per_storage','1'),('private_score_per_storage','10')");
        jdbc.update("INSERT INTO users(uid,email,nickname,password,score,ip,register_at,last_sign_at,verified) VALUES(1,'owner@example.invalid','Owner','unused',10000,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true),(2,'other@example.invalid','Other','unused',10000,'127.0.0.2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)");
    }

    @Test void multipartUploadStoresRealPngAndDeduplicatesWithoutChargingAgain() throws Exception {
        var result = mvc.perform(multipart("/texture").file(png(64,64)).param("name","Test skin").param("type","alex").param("public","1")
                        .with(user(owner)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn();
        int tid = jdbc.queryForObject("SELECT tid FROM textures", Integer.class);
        Texture texture = textures.find(tid);
        assertThat(Files.isRegularFile(storage.resolve(texture.hash()))).isTrue();
        assertThat(users.score(1)).isEqualTo(10000-texture.size()-2);
        assertThat(users.inCloset(1,tid)).isEqualTo(1);
        assertThat(service.upload(owner,"Again","alex",true,png(64,64)).get("code")).isEqualTo(2);
        assertThat(users.score(1)).isEqualTo(10000-texture.size()-2);
        mvc.perform(get("/preview/"+tid)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/avatar/"+tid).param("3d","")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/textures/"+texture.hash())).andExpect(status().isOk()).andExpect(content().bytes(Files.readAllBytes(storage.resolve(texture.hash()))));
    }

    @Test void privateTexturesAreHiddenAndSharedFilesSurviveUntilLastReferenceIsDeleted() throws Exception {
        int one = tid(service.upload(owner,"Private one","steve",false,png(64,64)));
        int two = tid(service.upload(other,"Private two","steve",false,png(64,64)));
        var path = storage.resolve(textures.find(one).hash());
        assertThat(textures.find(two).hash()).isEqualTo(textures.find(one).hash());
        mvc.perform(get("/texture/"+one)).andExpect(status().isForbidden());
        mvc.perform(get("/texture/"+one).with(user(other))).andExpect(status().isForbidden());
        mvc.perform(get("/skinlib/list")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/skinlib/list").with(user(owner))).andExpect(jsonPath("$.total").value(1));
        service.delete(owner,one);
        assertThat(Files.exists(path)).isTrue();
        service.delete(other,two);
        assertThat(Files.exists(path)).isFalse();
        assertThat(users.score(1)).isEqualTo(10000);
        assertThat(users.score(2)).isEqualTo(10000);
    }

    @Test void privacyChangeRemovesOtherCollectorsAndAppliedTexturesButKeepsOwner() throws Exception {
        int tid = tid(service.upload(owner,"Public skin","alex",true,png(64,64)));
        closet.add(other,tid,"Collected");
        var mine = players.add(1,"OwnerPlayer",false);
        var theirs = players.add(2,"OtherPlayer",false);
        players.setTextures(1,mine.pid(),tid,null);
        players.setTextures(2,theirs.pid(),tid,null);
        service.privacy(owner,tid);
        assertThat(users.inCloset(1,tid)).isEqualTo(1);
        assertThat(users.inCloset(2,tid)).isZero();
        assertThat(users.find(mine.pid()).tidSkin()).isEqualTo(tid);
        assertThat(users.find(theirs.pid()).tidSkin()).isZero();
        assertThat(users.score(2)).isEqualTo(9900);
        assertThat(textures.find(tid).likes()).isEqualTo(1);
    }

    @Test void rejectsInvalidDimensionsPermissionsAndInsufficientScoreWithoutRows() throws Exception {
        assertThatThrownBy(() -> service.upload(owner,"Bad","alex",true,png(64,32))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(owner,"Bad","cape",true,png(64,64))).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE users SET score=0 WHERE uid=1");
        assertThatThrownBy(() -> service.upload(owner,"No score","steve",true,png(64,64))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM textures",Integer.class)).isZero();
        int tid = tid(service.upload(other,"Other skin","steve",true,png(64,64)));
        mvc.perform(put("/texture/"+tid+"/name").with(user(owner)).with(csrf()).contentType("application/json").content("{\"name\":\"Stolen\"}"))
                .andExpect(status().isForbidden());
        assertThat(textures.find(tid).name()).isEqualTo("Other skin");
    }

    private int tid(Map<String,Object> result) { return ((Number)((Map<?,?>)result.get("data")).get("tid")).intValue(); }

    @Test void customSkinLoaderProfilesExposeAppliedTexturesModelsAndCacheHeaders() throws Exception {
        int skin=tid(service.upload(owner,"Private slim","alex",false,png(64,64)));
        int cape=tid(service.upload(owner,"Private cape","cape",false,png(64,32)));
        var player=players.add(1,"CslPlayer",false);
        players.setTextures(1,player.pid(),skin,cape);
        jdbc.update("UPDATE players SET last_modified='2025-01-02 03:04:05' WHERE pid=?",player.pid());
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('cache_expire_time','120')");
        for(String path:new String[]{"/CslPlayer.json","/csl/CslPlayer.json"}) {
            var result=mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("CslPlayer"))
                    .andExpect(jsonPath("$.skins.slim").value(textures.find(skin).hash()))
                    .andExpect(jsonPath("$.cape").value(textures.find(cape).hash()))
                    .andExpect(jsonPath("$.uid").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist())
                    .andExpect(header().string("Cache-Control","max-age=120, public")).andExpect(header().exists("Last-Modified")).andReturn();
            mvc.perform(get(path).header("If-Modified-Since",result.getResponse().getHeader("Last-Modified")))
                    .andExpect(status().isNotModified()).andExpect(content().bytes(new byte[0]));
            mvc.perform(head(path)).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        }
        mvc.perform(get("/csl/textures/"+textures.find(skin).hash())).andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(storage.resolve(textures.find(skin).hash()))));
        players.setTextures(1,player.pid(),0,0);
        mvc.perform(get("/CslPlayer.json")).andExpect(jsonPath("$.skins.default").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cape").value(org.hamcrest.Matchers.nullValue()));
        service.type(owner,skin,"steve"); players.setTextures(1,player.pid(),skin,null);
        mvc.perform(get("/CslPlayer.json")).andExpect(jsonPath("$.skins.default").value(textures.find(skin).hash()));
        jdbc.update("UPDATE users SET permission=-1 WHERE uid=1");
        mvc.perform(get("/CslPlayer.json").header("If-Modified-Since","Wed, 01 Jan 2031 00:00:00 GMT")).andExpect(status().isForbidden());
        mvc.perform(get("/csl/Missing.json")).andExpect(status().isNotFound());
    }

    @Test void previewHashMatchesTexturePreviewAndMissingFileReturnsNotFound() throws Exception {
        int tid=tid(service.upload(owner,"Preview","steve",false,png(64,64)));
        var texture=textures.find(tid);
        byte[] expected=mvc.perform(get("/preview/"+tid).param("height","128")).andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get("/preview/hash/"+texture.hash()).param("height","128")).andExpect(status().isOk()).andExpect(content().bytes(expected));
        mvc.perform(get("/preview/hash/"+texture.hash()).param("height","99999")).andExpect(status().isBadRequest());
        mvc.perform(get("/preview/hash/"+"0".repeat(64))).andExpect(status().isNotFound());
        Files.delete(storage.resolve(texture.hash()));
        mvc.perform(get("/preview/"+tid)).andExpect(status().isNotFound());
        mvc.perform(get("/preview/hash/"+texture.hash())).andExpect(status().isNotFound());
    }

    @Test void avatarAliasesRenderSelectedSkinAnonymouslyAndHandleMissingProfiles() throws Exception {
        int tid=tid(service.upload(owner,"Avatar skin","alex",false,png(64,64)));
        service.avatar(owner,tid);
        var player=players.add(1,"AvatarPlayer",false);
        players.setTextures(1,player.pid(),tid,null);
        byte[] expected=mvc.perform(get("/avatar/"+tid).param("size","64").param("3d",""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        for(String path:new String[]{"/avatar/user/1","/avatar/player/AvatarPlayer","/avatar/hash/"+textures.find(tid).hash()}) {
            mvc.perform(get(path).param("size","64").param("3d","")).andExpect(status().isOk())
                    .andExpect(content().contentType("image/png")).andExpect(content().bytes(expected));
            mvc.perform(get(path).param("size","99999")).andExpect(status().isBadRequest());
        }
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(expected)).getWidth()).isEqualTo(64);
        mvc.perform(get("/avatar/player/Nonexistent")).andExpect(status().isNotFound());
        byte[] fallback=mvc.perform(get("/avatar/0")).andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get("/avatar/user/99999")).andExpect(status().isOk()).andExpect(content().bytes(fallback));
        mvc.perform(get("/avatar/hash/"+"0".repeat(64))).andExpect(status().isOk()).andExpect(content().bytes(fallback));
        Files.delete(storage.resolve(textures.find(tid).hash()));
        mvc.perform(get("/avatar/user/1")).andExpect(status().isOk()).andExpect(content().bytes(fallback));
    }
    @Test void renderCacheTracksModelSizeAndMissingFiles() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('enable_preview_cache','true'),('enable_avatar_cache','true')");
        int tid=tid(service.upload(owner,"Cached","steve",true,png(64,64)));
        var texture=textures.find(tid);
        var preview=renders.preview(texture,128);
        assertThat(renders.preview(texture,128)).isSameAs(preview);
        assertThat(renders.preview(texture,256)).isNotSameAs(preview);
        service.type(owner,tid,"alex");
        assertThat(renders.preview(textures.find(tid),128)).isNotSameAs(preview);
        var avatar=renders.avatar(texture,64,false);
        assertThat(renders.avatar(texture,64,false)).isSameAs(avatar);
        assertThat(renders.avatar(texture,64,true)).isNotSameAs(avatar);
        jdbc.update("UPDATE options SET option_value='false' WHERE option_name='enable_preview_cache'");
        assertThat(renders.preview(texture,128)).isNotSameAs(preview);
        Files.delete(storage.resolve(texture.hash()));
        mvc.perform(get("/preview/"+tid)).andExpect(status().isNotFound());
        assertThat(renders.avatar(texture,64,false).bytes()).isEqualTo(renders.avatar(null,64,false).bytes());
    }

    @Test void avatarRevalidatesAfterChangingPlayerSkinAndClearKeepsUploads() throws Exception {
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('enable_avatar_cache','true')");
        int tid=tid(service.upload(owner,"Cached","steve",true,png(64,64)));
        var player=players.add(1,"CachedPlayer",false);
        String url="/avatar/player/"+player.name();
        var previous=mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-cache")).andReturn().getResponse();
        mvc.perform(get(url).header("If-None-Match",previous.getHeader("ETag"))).andExpect(status().isNotModified());
        players.setTextures(1,player.pid(),tid,null);
        var changed=mvc.perform(get(url).header("If-None-Match",previous.getHeader("ETag"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(changed.getHeader("ETag")).isNotEqualTo(previous.getHeader("ETag"));
        mvc.perform(head(url)).andExpect(status().isOk()).andExpect(header().string("ETag",changed.getHeader("ETag")));
        var texture=textures.find(tid);
        var cached=renders.avatar(texture,64,false);
        var admin=new AccountUser(1,"owner@example.invalid","Owner","unused",2);
        mvc.perform(post("/admin/resource/clear-cache").with(user(owner)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/resource/clear-cache").with(user(admin))).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET permission=2 WHERE uid=1");
        mvc.perform(post("/admin/resource/clear-cache").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/resource")).andExpect(flash().attribute("cacheCleared",true));
        var regenerated=renders.avatar(texture,64,false);
        assertThat(regenerated).isNotSameAs(cached);
        assertThat(regenerated.bytes()).isEqualTo(cached.bytes());
        assertThat(Files.isRegularFile(storage.resolve(texture.hash()))).isTrue();
    }

    private MockMultipartFile png(int width,int height) throws Exception {
        var image = new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) image.setRGB(x,y,0xff004488 | ((x * 3) << 16));
        var output = new ByteArrayOutputStream(); ImageIO.write(image,"png",output);
        return new MockMultipartFile("file","test.png","image/png",output.toByteArray());
    }
}
