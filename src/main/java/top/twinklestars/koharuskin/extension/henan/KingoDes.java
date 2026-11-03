package top.twinklestars.koharuskin.extension.henan;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** 兼容华水教务 jkingo.des.js 的 UTF-16 分组和密钥置换。 */
final class KingoDes {
    private static final int[] PC1={56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35,62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3};
    static String encrypt(String data,String key) throws GeneralSecurityException {
        if(key.isEmpty()) throw new IllegalArgumentException("缺少临时加密密钥");
        var result=new StringBuilder();
        var cipher=Cipher.getInstance("DES/ECB/NoPadding");
        for(int offset=0;offset<data.length();offset+=4) {
            byte[] block=block(data,offset);
            for(int index=0;index<key.length();index+=4) {
                byte[] source=block(key,index),transformed=new byte[8];
                for(int position=0;position<56;position++) {
                    int sourceBit=8*(7-position%8)+position/8,targetBit=PC1[position];
                    int bit=(source[sourceBit/8]>>(7-sourceBit%8))&1;
                    transformed[targetBit/8]|=(byte)(bit<<(7-targetBit%8));
                }
                cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(transformed,"DES"));
                block=cipher.doFinal(block);
            }
            result.append(HexFormat.of().withUpperCase().formatHex(block));
        }
        return result.toString();
    }
    private static byte[] block(String value,int offset) {
        byte[] bytes=new byte[8];
        for(int i=0;i<4&&offset+i<value.length();i++) {
            char unit=value.charAt(offset+i);
            bytes[i*2]=(byte)(unit>>8); bytes[i*2+1]=(byte)unit;
        }
        return bytes;
    }
}
