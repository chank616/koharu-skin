package top.twinklestars.koharuskin.extension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import top.twinklestars.koharuskin.player.PlayerMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","koharu.skin-api-tokens=test-skin-api-primary,test-skin-api-secondary"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkinApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlayerMapper players;
    @Autowired OfficialExtensions extensions;
    @Autowired top.twinklestars.koharuskin.admin.AdminSettings settings;
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("koharu.textures",()->storage.toString()); }
    static final String TOKEN="test-skin-api-primary";
    static final String HASH="b".repeat(64);
    static final String OLD_UUID="123456781234123412341234567890ab";

    @BeforeEach void fixture() throws Exception {
        for(String table:List.of("players","users","options","uuid","textures")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO options(option_name,option_value) VALUES('register_with_player_name','true'),('plugins_enabled','[{\"name\":\"skin-api-extension\",\"version\":\"0.6.2\"}]'),('ygg_uuid_algorithm','v3')");
        for(int uid=1001;uid<=1004;uid++) jdbc.update("INSERT INTO users(uid,email,nickname,password,score,avatar,ip,register_at,last_sign_at,verified) VALUES(?,?,?,'not-returned',100,?,'127.0.0.1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,true)",
                uid,"member"+uid+(uid==1004?"@other.invalid":"@School.EDU"),"Member"+uid,uid==1001?1001:0);
        jdbc.update("INSERT INTO players(pid,uid,name,tid_skin,tid_cape,last_modified) VALUES(1012,1001,'SecondRole',0,0,CURRENT_TIMESTAMP),(1011,1001,'FirstRole',1001,0,CURRENT_TIMESTAMP),(1013,1003,'ThirdRole',0,0,CURRENT_TIMESTAMP),(1014,1004,'OtherRole',0,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO uuid(name,uuid) VALUES('FirstRole',?)",OLD_UUID);
        jdbc.update("INSERT INTO textures(tid,name,type,hash,size,uploader,public,likes,upload_at) VALUES(1001,'API skin','steve',?,1,1001,false,0,CURRENT_TIMESTAMP)",HASH);
        var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=8;y<16;y++) for(int x=8;x<16;x++) image.setRGB(x,y,0xff2255aa);
        ImageIO.write(image,"png",storage.resolve(HASH).toFile());
    }

    @Test void unauthorizedRequestsFailBeforeDataAndBothTokenFormsWorkWithoutSession() throws Exception {
        mvc.perform(post("/ext/get-uids-by-suffix").param("suffix","@school.edu"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("无效的token"));
        mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","FirstRole").header("Authorization","Bearer wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/ext/get-uids-by-suffix").param("suffix","@school.edu").param("token",TOKEN))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/ext/get-uids-by-suffix").contentType("application/json")
                        .content("{\"suffix\":\"@school.edu\",\"token\":\"test-skin-api-secondary\"}"))
                .andExpect(status().isOk());
        extensions.setEnabled("skin-api-extension",false);
        mvc.perform(post("/ext/get-uids-by-suffix").param("suffix","@school.edu").param("token",TOKEN)).andExpect(status().isNotFound());
    }

    @Test void suffixQueryPreservesOldUuidOrderingEmptyAccountsAndExactDomainBoundary() throws Exception {
        mvc.perform(post("/ext/get-uids-by-suffix").header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{\"suffix\":\" @SCHOOL.edu \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suffix").value("@SCHOOL.edu"))
                .andExpect(jsonPath("$.accounts.length()").value(3))
                .andExpect(jsonPath("$.accounts[0].account_name").value("Member1001"))
                .andExpect(jsonPath("$.accounts[0].roles[0].game_id").value("FirstRole"))
                .andExpect(jsonPath("$.accounts[0].roles[0].uuid").value(OLD_UUID))
                .andExpect(jsonPath("$.accounts[0].roles[1].game_id").value("SecondRole"))
                .andExpect(jsonPath("$.accounts[1].roles.length()").value(0))
                .andExpect(jsonPath("$.accounts[2].roles[0].game_id").value("ThirdRole"))
                .andExpect(jsonPath("$.accounts[0].email").doesNotExist()).andExpect(jsonPath("$.accounts[0].password").doesNotExist());
        String generated=players.uuid("SecondRole");
        assertThat(generated).isEqualTo(UUID.nameUUIDFromBytes("OfflinePlayer:SecondRole".getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));
        mvc.perform(post("/ext/get-uids-by-suffix").header("Authorization","Bearer "+TOKEN).param("suffix","@school.edu")).andExpect(status().isOk());
        assertThat(players.uuid("SecondRole")).isEqualTo(generated);
        assertThat(players.uuid("FirstRole")).isEqualTo(OLD_UUID);
        mvc.perform(post("/ext/get-uids-by-suffix").param("token",TOKEN).param("suffix","@edu")).andExpect(jsonPath("$.accounts.length()").value(0));
        for(String suffix:List.of("","school.edu","@school%edu","@school.edu' OR 1=1"))
            mvc.perform(post("/ext/get-uids-by-suffix").param("token",TOKEN).param("suffix",suffix)).andExpect(status().isBadRequest());
    }

    @Test void gameAvatarHasRealPixelsClampedSizePrivateCacheAndConditionalResponses() throws Exception {
        var response=mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id"," FirstRole ").param("size","64").header("Authorization","Bearer "+TOKEN))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","max-age=86400, private")).andReturn().getResponse();
        var image=ImageIO.read(new ByteArrayInputStream(response.getContentAsByteArray()));
        assertThat(image.getWidth()).isEqualTo(64); assertThat(image.getRGB(32,32)).isEqualTo(0xff2255aa);
        String etag="\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((HASH+":64").getBytes(StandardCharsets.UTF_8)))+"\"";
        assertThat(response.getHeader("ETag")).isEqualTo(etag);
        mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","FirstRole").param("size","64").param("token",TOKEN).header("If-None-Match",etag))
                .andExpect(status().isNotModified()).andExpect(content().bytes(new byte[0]));
        mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","FirstRole").param("size","64").header("If-None-Match",etag))
                .andExpect(status().isUnauthorized());
        for(var size:Map.of("1",16,"9999",512).entrySet()) {
            byte[] bytes=mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","FirstRole").param("size",size.getKey()).param("token",TOKEN))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(ImageIO.read(new ByteArrayInputStream(bytes)).getWidth()).isEqualTo(size.getValue());
        }
        Files.delete(storage.resolve(HASH));
        mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","FirstRole").param("size","64").param("token",TOKEN).header("If-None-Match",etag))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/ext/get-avatar-by-game-id").param("game_id","Missing").param("token",TOKEN)).andExpect(status().isNotFound());
        mvc.perform(get("/ext/get-avatar-by-game-id").param("token",TOKEN)).andExpect(status().isBadRequest());
    }

    @Test void userAvatarUsesAccountAvatarAndOriginalFallbackSize() throws Exception {
        byte[] skin=mvc.perform(post("/ext/get-avatar-by-uid").header("Authorization","Bearer "+TOKEN).contentType("application/json").content("{\"uid\":1001}"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
        var rendered=ImageIO.read(new ByteArrayInputStream(skin));
        assertThat(rendered.getWidth()).isEqualTo(225); assertThat(rendered.getRGB(100,100)).isEqualTo(0xff2255aa);
        var hd=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        var graphics=hd.createGraphics(); graphics.drawImage(ImageIO.read(storage.resolve(HASH).toFile()),0,0,128,128,null); graphics.dispose();
        ImageIO.write(hd,"png",storage.resolve(HASH).toFile());
        byte[] hdAvatar=mvc.perform(post("/ext/get-avatar-by-uid").param("token",TOKEN).param("uid","1001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(hdAvatar)).getWidth()).isEqualTo(450);
        byte[] fallback=mvc.perform(post("/ext/get-avatar-by-uid").param("token",TOKEN).param("uid","1002"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(fallback)).getWidth()).isEqualTo(128);
        for(String uid:List.of("","0","abc","999999999999999999999999"))
            mvc.perform(post("/ext/get-avatar-by-uid").param("token",TOKEN).param("uid",uid)).andExpect(status().isBadRequest());
        mvc.perform(post("/ext/get-avatar-by-uid").param("token",TOKEN).param("uid","99999")).andExpect(status().isNotFound());
    }

    @Test void configuredTokenTakesEffectImmediatelyAndBlankSubmissionKeepsItPrivate() throws Exception {
        settings.save("extensions","skin-api-extension",Map.of("skin_api_tokens","rotated-skin-api-test-token"));
        mvc.perform(post("/ext/get-avatar-by-uid").param("token",TOKEN).param("uid","1001")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ext/get-avatar-by-uid").param("token","rotated-skin-api-test-token").param("uid","1001")).andExpect(status().isOk());
        assertThat(settings.values("extensions").get("skin_api_tokens")).isEmpty();
        jdbc.update("UPDATE users SET permission=2 WHERE uid=1001");
        var admin=new top.twinklestars.koharuskin.account.AccountUser(1001,"member1001@School.EDU","Member1001","not-returned",2);
        mvc.perform(get("/admin/extensions/config").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(admin)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rotated-skin-api-test-token"))));
        settings.save("extensions","skin-api-extension",Map.of("skin_api_tokens",""));
        mvc.perform(post("/ext/get-avatar-by-uid").param("token","rotated-skin-api-test-token").param("uid","1001")).andExpect(status().isOk());
        assertThatThrownBy(()->settings.save("extensions","skin-api-extension",Map.of("skin_api_tokens","short")))
                .isInstanceOf(top.twinklestars.koharuskin.account.BusinessException.class);
        jdbc.update("UPDATE options SET option_value='' WHERE option_name='skin_api_tokens'");
        mvc.perform(post("/ext/get-avatar-by-uid").param("token","").param("uid","1001")).andExpect(status().isUnauthorized());
    }
}
