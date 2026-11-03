package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import top.twinklestars.koharuskin.account.AccountUser;
import top.twinklestars.koharuskin.site.SiteOptions;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.Clock;
import java.util.*;

@Service
public class UnionOAuth {
    public static final String ROOT="/api/union/member/oauth2";
    private final UnionMember union;
    private final SiteOptions options;
    private final JsonMapper json;
    private final Clock clock;
    public UnionOAuth(UnionMember union,SiteOptions options,JsonMapper json,Clock clock) {
        this.union=union; this.options=options; this.json=json; this.clock=clock;
    }
    public void requireEnabled() {
        union.requireEnabled();
        if(!options.enabled("union_enable_oauth2",false)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"联盟授权未启用");
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public String publicKey() {
        requireEnabled();
        return YggKeys.pem("PUBLIC KEY",pair().getPublic().getEncoded());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public String grant(AccountUser user,MultiValueMap<String,String> query) {
        requireEnabled();
        String secret=options.text("union_member_key","");
        if(secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"尚未配置联盟成员密钥");
        String publicKey=union.request("GET","/oauth2/backend",null).path("publicKey").asString("");
        var signingKey=pair();
        try {
            String userInfo=Base64.getEncoder().encodeToString(json.writeValueAsBytes(Map.of("uid",user.uid(),"nickname",user.nickname(),
                    "email",user.email(),"expires_at",clock.instant().getEpochSecond()+600)));
            var hmac=Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String mac=HexFormat.of().formatHex(hmac.doFinal(userInfo.getBytes(StandardCharsets.UTF_8)));
            var signature=Signature.getInstance("SHA256withRSA"); signature.initSign(signingKey.getPrivate());
            signature.update((userInfo+"."+mac).getBytes(StandardCharsets.UTF_8));
            byte[] payload=json.writeValueAsBytes(Map.of("userInfo",userInfo,"mac",mac,"signature",Base64.getEncoder().encodeToString(signature.sign())));
            var encryptionKey=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(publicKey,"PUBLIC KEY")));
            var cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE,encryptionKey);
            int block=(encryptionKey.getModulus().bitLength()+7)/8-11;
            var encrypted=new ByteArrayOutputStream();
            for(int offset=0;offset<payload.length;offset+=block) encrypted.writeBytes(cipher.doFinal(payload,offset,Math.min(block,payload.length-offset)));
            var params=new ArrayList<String>();
            params.add("userInfoToken="+encode(Base64.getEncoder().encodeToString(encrypted.toByteArray())));
            query.forEach((name,values)->{if(!name.equals("userInfoToken")) for(String value:values) params.add(encode(name)+"="+encode(value));});
            return options.text("union_api_root","").replaceAll("/+$","")+"/oauth2/continue?"+String.join("&",params);
        } catch(GeneralSecurityException|IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"联盟授权加密失败，请检查联盟公钥"); }
    }
    private KeyPair pair() {
        options.lockRegistration();
        String privatePem=options.text("union_oauth2_sig_private_key",""),publicPem=options.text("union_oauth2_sig_public_key","");
        try {
            if(privatePem.isBlank()) {
                if(!publicPem.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"联盟授权签名私钥缺失");
                var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(4096);
                privatePem=YggKeys.pem("PRIVATE KEY",generator.generateKeyPair().getPrivate().getEncoded());
                save("union_oauth2_sig_private_key",privatePem);
            }
            var factory=KeyFactory.getInstance("RSA");
            var privateKey=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privatePem,"PRIVATE KEY")));
            var publicKey=factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),privateKey.getPublicExponent()));
            if(publicPem.isBlank()) save("union_oauth2_sig_public_key",YggKeys.pem("PUBLIC KEY",publicKey.getEncoded()));
            else if(!MessageDigest.isEqual(publicKey.getEncoded(),factory.generatePublic(new X509EncodedKeySpec(decode(publicPem,"PUBLIC KEY"))).getEncoded()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"联盟授权签名公私钥不匹配");
            return new KeyPair(publicKey,privateKey);
        } catch(GeneralSecurityException|IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"联盟授权签名密钥无效"); }
    }
    private byte[] decode(String pem,String type) { return Base64.getDecoder().decode(pem.replace("-----BEGIN "+type+"-----","").replace("-----END "+type+"-----","").replaceAll("\\s","")); }
    private String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    private void save(String name,String value) { if(options.get(name)==null) options.insert(name,value); else options.update(name,value); }
}
