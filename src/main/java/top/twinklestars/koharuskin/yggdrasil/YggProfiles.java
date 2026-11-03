package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import top.twinklestars.koharuskin.player.Player;
import top.twinklestars.koharuskin.player.PlayerService;
import top.twinklestars.koharuskin.site.SiteOptions;
import top.twinklestars.koharuskin.texture.TextureMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

@Service
public class YggProfiles {
    private final PlayerService players;
    private final TextureMapper textures;
    private final SiteOptions options;
    private final YggKeys keys;
    private final JsonMapper json;
    private final Clock clock;
    private final String publicUrl;
    public YggProfiles(PlayerService players, TextureMapper textures, SiteOptions options, YggKeys keys,
                       JsonMapper json, Clock clock, @Value("${koharu.public-url}") String publicUrl) {
        this.players=players; this.textures=textures; this.options=options; this.keys=keys; this.json=json; this.clock=clock;
        this.publicUrl=publicUrl.replaceAll("/+$","");
    }

    public Map<String,String> simple(Player player) {
        return Map.of("id",players.uuid(player.name()).replace("-",""),"name",player.name());
    }

    public Map<String,Object> full(Player player, boolean unsigned) {
        var profile=simple(player);
        Map<String,Object> textureData=new LinkedHashMap<>();
        var skin=textures.find(player.tidSkin());
        if (skin!=null && !skin.type().equals("cape")) textureData.put("SKIN",Map.of("url",publicUrl+"/textures/"+skin.hash(),
                "metadata",Map.of("model",skin.type().equals("alex")?"slim":"default")));
        var cape=textures.find(player.tidCape());
        if (cape!=null && cape.type().equals("cape")) textureData.put("CAPE",Map.of("url",publicUrl+"/textures/"+cape.hash()));
        Map<String,Object> payload=new LinkedHashMap<>(Map.of("timestamp",clock.instant().toEpochMilli(),"profileId",profile.get("id"),"profileName",player.name(),"textures",textureData));
        if (!unsigned) payload.put("signatureRequired",true);
        var properties=new ArrayList<Map<String,String>>();
        properties.add(new LinkedHashMap<>(Map.of("name","textures","value",Base64.getEncoder().encodeToString(json.writeValueAsBytes(payload)))));
        properties.add(new LinkedHashMap<>(Map.of("name","uploadableTextures","value","skin,cape")));
        if (!unsigned) {
            var key=keys.pair().getPrivate();
            properties.forEach(property -> property.put("signature",keys.sign(property.get("value"),key)));
        }
        return Map.of("id",profile.get("id"),"name",profile.get("name"),"properties",properties);
    }

    public Map<String,Object> metadata() {
        Set<String> domains=new LinkedHashSet<>();
        domains.add(URI.create(publicUrl).getHost());
        Arrays.stream(options.text("ygg_skin_domain","").split(",")).map(String::trim).filter(domain -> !domain.isEmpty()).forEach(domains::add);
        return Map.of("meta",Map.of("serverName",options.text("site_name","Koharu Skin"),"implementationName","Koharu Skin",
                        "implementationVersion","0.0.1","links",Map.of("homepage",publicUrl,"register",publicUrl+"/auth/register"),"feature.non_email_login",true),
                "skinDomains",domains,"signaturePublickey",YggKeys.pem("PUBLIC KEY",keys.pair().getPublic().getEncoded()));
    }
}
