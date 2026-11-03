package top.twinklestars.koharuskin.texture;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TextureMapper {
    @Select("SELECT tid,name,type,hash,size,uploader,public AS visible,upload_at,likes FROM textures WHERE tid=#{tid}")
    Texture find(int tid);
}
