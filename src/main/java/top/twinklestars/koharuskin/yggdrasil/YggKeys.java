package top.twinklestars.koharuskin.yggdrasil;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.twinklestars.koharuskin.site.SiteOptions;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.Base64;

@Service
public class YggKeys {
    private final SiteOptions options;
    private volatile Cached cached;
    public YggKeys(SiteOptions options) { this.options=options; }

    @Transactional
    public KeyPair pair() {
        String pem=options.get("ygg_private_key");
        try {
            if (pem==null || pem.isBlank()) {
                options.lockRegistration();
                pem=options.get("ygg_private_key");
                if (pem==null || pem.isBlank()) {
                    var generator=KeyPairGenerator.getInstance("RSA");
                    generator.initialize(4096);
                    boolean missing=pem==null;
                    pem=pem("PRIVATE KEY",generator.generateKeyPair().getPrivate().getEncoded());
                    if (missing) options.insert("ygg_private_key",pem); else options.update("ygg_private_key",pem);
                }
            }
            var previous=cached;
            if (previous!=null && previous.pem().equals(pem)) return previous.pair();
            byte[] bytes=Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s",""));
            var factory=KeyFactory.getInstance("RSA");
            var privateKey=(RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
            if (privateKey.getModulus().bitLength()<4096) throw new IllegalStateException("Yggdrasil RSA 密钥至少需要 4096 位");
            var publicKey=factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),privateKey.getPublicExponent()));
            var pair=new KeyPair(publicKey,privateKey);
            cached=new Cached(pem,pair);
            return pair;
        } catch (GeneralSecurityException error) { throw new IllegalStateException("无法读取 Yggdrasil 签名密钥",error); }
    }

    public String sign(String value, PrivateKey key) {
        try {
            var signature=Signature.getInstance("SHA1withRSA");
            signature.initSign(key); signature.update(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }
    static String pem(String type,byte[] bytes) {
        return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n";
    }
    private record Cached(String pem,KeyPair pair) {}
}
