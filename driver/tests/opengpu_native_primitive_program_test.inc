/* ABI8/9 admission through the real validator, opaque owner and MMU.
 * Literal programs exercise permissions/ranges; they are not Mesa captures. */
static u32 primitive_store_program(unsigned slice)
{
    unsigned n=64;
    literal_word(n,0x01007557u);n+=4;
    literal_word(n,7u<<25|5u<<7|0x0bu);n+=4;
    literal_word(n,23u<<26|1u<<25|5u<<15|4u<<12|2u<<7|0x57u);n+=4;
    literal_word(n,1u<<15|6u<<7|0x13u);n+=4;
    unsigned bytes=slice*512;
    while(bytes){unsigned step=bytes>2047?2047:bytes;
        literal_word(n,step<<20|6u<<15|6u<<7|0x13u);n+=4;bytes-=step;}
    literal_word(n,1u<<25|6u<<15|6u<<12|2u<<7|0x27u);n+=4;
    literal_word(n,OPENGPU_SHADER_CEASE);n+=4;
    return n-64;
}
static bool primitive_validate(const u32 *words,u32 count,
    const struct opengpu_native_program_profile *p)
{
    return opengpu_native_shader_validate_words_primitive_inputs(words,count,
        p->staging_bytes,p->capacity,p->stage,p->version,p->attributes,p->varyings,
        p->colors,p->scalar_fpu,p->owned_uniforms,p->geometry_inputs,p->sample_inputs,p->primitive_inputs);
}
static void primitive_program_tests(struct opengpu_device *gpu,struct opengpu_render_context *context)
{
    const u32 ceased[]={OPENGPU_SHADER_CEASE};
    for(u32 version=8;version<=9;version++){
        const unsigned first=version==8?73:76,last=first+18;
        struct opengpu_native_program_profile p={.stage=1,.version=version,.capacity=128,
            .varyings=16,.colors=4,.staging_bytes=(u64)last*512,
            .owned_uniforms=true,.scalar_fpu=true,.primitive_inputs=true,.sample_inputs=version==9};
        assert(p.staging_bytes==(version==8?46592u:48128u));
        assert(primitive_validate(ceased,1,&p));
        assert(!opengpu_native_shader_validate_words_sample_inputs(ceased,1,p.staging_bytes,
            128,1,version,0,16,4,true,true,true,true));
        assert(!opengpu_native_shader_validate_words(ceased,1,p.staging_bytes,
            128,1,version,0,16,4,true,true,true));
        struct opengpu_native_program_profile bad=p;
        bad.primitive_inputs=false;assert(!primitive_validate(ceased,1,&bad));
        bad=p;bad.geometry_inputs=true;assert(!primitive_validate(ceased,1,&bad));
        bad=p;bad.sample_inputs=false;assert(primitive_validate(ceased,1,&bad)==(version==8));
        bad=p;bad.stage=0;assert(!primitive_validate(ceased,1,&bad));
        bad=p;bad.version=10;assert(!primitive_validate(ceased,1,&bad));
        for(unsigned legacy=1;legacy<=7;legacy++){
            bad=p;bad.version=legacy;bad.geometry_inputs=legacy==5 || legacy==7;
            bad.sample_inputs=legacy>=6;assert(!primitive_validate(ceased,1,&bad));
        }
        /* Metadata gates must reject before reading any source byte. */
        for(unsigned field=0;field<9;field++){
            if(field==8 && version==8)continue;
            bad=p;
            switch(field){
            case 0:bad.primitive_inputs=false;break;
            case 1:bad.geometry_inputs=true;break;
            case 2:bad.version=7;bad.geometry_inputs=true;bad.sample_inputs=true;break;
            case 3:bad.version=10;break;
            case 4:bad.stage=0;break;
            case 5:bad.capacity=256;break;
            case 6:bad.varyings=17;break;
            case 7:bad.colors=5;break;
            case 8:bad.sample_inputs=false;break;
            }
            struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
            literal_word(64,OPENGPU_SHADER_CEASE);
            struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=4};
            unsigned copies=code_copies;reject(owner,view,bad,-EINVAL);assert(copies==code_copies);
            assert(!opengpu_private_snapshot_retire(owner));
        }
        for(unsigned slice=0;slice<=last;slice++){
            p.staging_bytes=(u64)(last+4)*512; /* Test writable bounds, not allocation bounds. */
            struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=primitive_store_program(slice)};
            if(slice>=first && slice<last)accepted(gpu,context,view,p);
            else{
                struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
                reject(owner,view,p,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));
            }
        }
        /* Exact full prefix is admitted; one byte short remains invalid. */
        p.staging_bytes=(u64)last*512;
        literal_word(64,0xffffffffu);literal_word(68,OPENGPU_SHADER_CEASE);
        struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=8,.entry_offset=4};
        accepted(gpu,context,view,p);
        struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        p.staging_bytes--;literal_word(64,OPENGPU_SHADER_CEASE);view.bytes=4;view.entry_offset=0;
        reject(owner,view,p,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));
        p.staging_bytes++;
        for(unsigned i=0;i<512;i++)literal_word(64+4*i,i==511?OPENGPU_SHADER_CEASE:0x13u);
        view.bytes=2048;accepted(gpu,context,view,p);
        owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        unsigned copies=code_copies;view.bytes=2052;reject(owner,view,p,-EINVAL);assert(code_copies==copies);
        assert(!opengpu_private_snapshot_retire(owner));
    }
}
static void primitive_artifact(struct opengpu_device *gpu,struct opengpu_render_context *context,const char *path)
{
    /* Independent reader for the actual compiler's private OGNSF002 bytes.
     * These CLI fixtures explicitly supply verified TEST permissions; the
     * artifact's requirement flags do not authorize a production submission. */
    FILE *f=fopen(path,"rb");assert(f);unsigned char raw[176];
    assert(fread(raw,1,sizeof(raw),f)==sizeof(raw) && !memcmp(raw,"OGNSF002",8));
    u32 h[42];
    for(unsigned i=0;i<42;i++)h[i]=(u32)raw[8+4*i]|(u32)raw[9+4*i]<<8|
        (u32)raw[10+4*i]<<16|(u32)raw[11+4*i]<<24;
    assert(h[0]==2 && (h[1]==8 || h[1]==9) && !h[41] && h[14] && h[14]<=512);
    assert(h[11]==128 && h[12]<=16 && h[13]<=4 && h[15]==1 && !h[10]);
    assert(h[9]==(h[1]==9) && h[16]<=1 && h[3]==512);
    assert(h[2]==9+4*h[12]+(h[1]==9?3u:0u) && h[4]==512*(h[2]+4*h[13]+2));
    unsigned prefix=h[14]<512,suffix=h[14]<511;
    literal_word(64,0xffffffffu);
    size_t bytes=4*h[14];assert(fread(original_code+64+4*prefix,1,bytes,f)==bytes);
    assert(fgetc(f)==EOF && !ferror(f) && !fclose(f));
    if(suffix)literal_word(64+4*prefix+(unsigned)bytes,0xffffffffu);
    struct drm_opengpu_native_program_view view={.handle=71,.offset=64,
        .bytes=(u32)bytes+4*(prefix+suffix),.entry_offset=4*prefix};
    struct opengpu_native_program_profile p={.stage=1,.version=h[1],.capacity=128,
        .varyings=h[12],.colors=h[13],.staging_bytes=h[4],.scalar_fpu=true,
        .owned_uniforms=true,.primitive_inputs=true,.sample_inputs=h[1]==9};
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
    struct opengpu_native_program_profile denied=p;denied.primitive_inputs=false;
    unsigned copies=code_copies;reject(owner,view,denied,-EINVAL);assert(copies==code_copies);
    assert(!opengpu_private_snapshot_retire(owner));
    if(p.version==9){
        owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        denied=p;denied.sample_inputs=false;copies=code_copies;
        reject(owner,view,denied,-EINVAL);assert(copies==code_copies);
        assert(!opengpu_private_snapshot_retire(owner));
    }
    if(h[16]){
        owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        denied=p;denied.owned_uniforms=false;reject(owner,view,denied,-EINVAL);
        assert(!opengpu_private_snapshot_retire(owner));
    }else p.owned_uniforms=false; /* No incidental uniform grant to raw shaders. */
    accepted(gpu,context,view,p);
    printf("original artifact %s: ABI%u / %u words validator+RX/MMU PASS (CPU only)\n",path,p.version,h[14]);
}
