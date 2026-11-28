#include "opengpu_native_geometry_abi.h"
#include "opengpu_typed_fragment_state_abi.h"
#include "opengpu_protected_tessellation_joint_geometry_abi.h"
/* Included in opengpu_private_snapshot.c to inspect the real owner graph; no address getter, policy discovery or submit is added. */
static bool snapshot_geometry_words(const struct opengpu_private_snapshot *o,
    const struct snapshot_segment *s,size_t offset,u32 *v,u32 n)
{
    for(u32 i=0;i<n;i++)if(opengpu_private_snapshot_read32(o,s->id,offset+4u*i,v+i))return false;
    return true;
}
static const struct snapshot_segment *snapshot_geometry_containing(const struct opengpu_private_snapshot *o,u32 address)
{
    const struct snapshot_segment *s;
    for(s=o->segments;s;s=s->next)if(address>=s->va && (u64)address-s->va<s->bytes)return s;
    return NULL;
}
/* Full GGA/GNG/GNT capacity. Prepare runs in process context; avoid placing
 * four KiB of record scratch on the kernel stack (including after inlining). */
struct snapshot_geometry_scratch {
    u32 attachment[256], program[256], sequence[512];
};
static bool snapshot_geometry_attachments(const struct opengpu_private_snapshot *o,const u32 root[128],
    struct snapshot_geometry_scratch *scratch)
{
    if(!root[17]) {
        for(u32 i=36;i<128;i++)if(root[i])return false;
        return true;
    }
    if(root[36]!=1 || root[40]!=root[17] || root[41]!=1024 ||
        (u64)root[42]!=64+(u64)root[40]*1024 ||
        !snapshot_joint_view(o,root[37],root[38]|(u64)root[39]<<32,root[42]))return false;
    for(u32 i=43;i<128;i++)if(root[i])return false;
    const struct snapshot_segment *table=snapshot_joint_target(o,root[37]);u32 h[16];
    const u32 expected[]={0x47474101u,root[42],1,1024,root[40],root[24],root[26],root[25],0,0,0,0,0,0,0,0};
    if(!snapshot_geometry_words(o,table,0,h,16) || memcmp(h,expected,64))return false;
    const struct snapshot_segment *sequences=root[17]?snapshot_joint_target(o,root[16]):NULL;
    const struct snapshot_segment *frame=snapshot_joint_target(o,root[21]);u32 state_base;
    if(!snapshot_geometry_words(o,frame,292*4,&state_base,1))return false;
    const struct snapshot_segment *states=root[17]?snapshot_joint_target(o,state_base):NULL;
    if(root[17] && (!states || states->executable || !states->mapped || states->bytes%512))return false;
    for(u32 i=0;i<root[40];i++) {
        u32 *e=scratch->attachment,*n=scratch->program,*d=scratch->sequence;
        if(!snapshot_geometry_words(o,table,64+(size_t)i*1024,e,256) || e[0]!=i || e[2]!=root[0]-0x474e5107u ||
            e[3] || e[4]!=root[24] || e[5]!=root[26] || e[6]!=root[25] || e[7]>=32 || e[15]!=1024 || e[180] ||
            !snapshot_joint_view(o,e[12],e[13]|(u64)e[14]<<32,1024))return false;
        for(u32 j=181;j<256;j++)if(j!=224 && j!=225 && e[j])return false;
        if((u64)e[1]*512>=states->bytes || !snapshot_geometry_words(o,sequences,(size_t)i*root[20],d,512) ||
            d[0]!=0x474e5407u+e[2] || d[1]!=2048 || d[2]!=7+e[2] || d[40]!=e[1])return false;
        const struct snapshot_segment *gng=snapshot_joint_target(o,e[12]);
        if(!snapshot_geometry_words(o,gng,0,n,256) || !opengpu_native_geometry_program_encoding_valid(gng->backing.cpu) ||
            n[3]!=e[4] || n[4]!=e[5] || n[5]!=e[6] || n[6]!=(d[220+(e[2]!=0)]?1u:e[2]?3u:2u) ||
            !n[7] || n[7]>32 || n[8]>2 || !n[9] || n[9]>256 || n[10]>64 || n[11]>64 || n[13]>16 || n[14]>16 || n[15]!=1 ||
            n[11]+(n[12]&1u)+((n[12]>>1)&1u)>64 || n[9]*(n[11]+(n[12]&1u)+((n[12]>>1)&1u))>1024)return false;
        u32 fs_varyings,fs_abi;
        if(!snapshot_geometry_words(o,states,(size_t)e[1]*512+4u*OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_VARYINGSLOTS_WORD,&fs_varyings,1) ||
            !snapshot_geometry_words(o,states,(size_t)e[1]*512+4u*OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_VERSION_WORD,&fs_abi,1) ||
            fs_varyings!=n[14] || (fs_abi!=5 && fs_abi!=7))return false;
        const struct snapshot_segment *code=snapshot_joint_target(o,n[17]);
        u64 code_limit=n[18]|(u64)n[19]<<32,allocation_limit=n[21]|(u64)n[22]<<32;
        if(!code || !code->mapped || !code->executable || code_limit<=code->va || code_limit>code->va+code->bytes ||
            ((code_limit-code->va+63u)&~63ULL)!=code->bytes ||
            n[20]!=n[17] || allocation_limit!=code->va+code->bytes || (n[16]&3u) ||
            n[16]<n[17] || n[16]>=code_limit)return false;
        if(n[23]) {
            if(n[23]!=2)return false;
            const struct snapshot_segment *uniforms=snapshot_geometry_containing(o,n[24]);u32 u[14];
            if(!uniforms || !uniforms->mapped || uniforms->executable ||
                !snapshot_geometry_words(o,uniforms,n[24]-uniforms->va,u,14) ||
                u[0]!=0x47555202u || u[1]!=64 || u[2]!=2 || u[3]!=e[4] || u[4]!=e[5] || u[5]!=e[6] || u[6]!=3)return false;
        }else if(n[24])return false;
        u32 exports=d[e[2]?237:236]; /* actual TES outputComponents */
        for(u32 j=0;j<64;j++) {
            u32 m=e[16+j],raw=n[32+j];
            if(j<n[10]) {
                if((m&~255u) || !(m&128u) || (m&127u)>=exports || (m&127u)>=64 || raw>=4+4*n[13])return false;
                for(u32 k=0;k<j;k++)if(n[32+k]==raw && e[16+k]!=m)return false;
            }else if(m || raw)return false;
        }
        for(u32 j=0;j<68;j++)if(n[96+2*j]?(j>=4+4*n[14] || n[97+2*j]>=n[11]):n[97+2*j]!=0)return false;
        if(n[14]<16 && ((n[25]|(u64)n[26]<<32)>>(4*n[14])))return false;
        for(u32 j=80;j<180;j++)if(e[j])return false;
    }
    return true;
}
int opengpu_private_snapshot_check_prepared_joint_geometry(struct opengpu_private_snapshot *owner,u32 id)
{
    struct snapshot_geometry_scratch *scratch=NULL;
    const struct snapshot_segment *root;
    struct opengpu_private_snapshot_joint_request request;
    u32 words[128];
    int ret=snapshot_joint_validate(owner,id,SNAPSHOT_JOINT_GNQ7,words,&root,&request);
    if(ret)return ret;
    if(words[17]) {
        scratch=kvzalloc(sizeof(*scratch),GFP_KERNEL);
        if(!scratch){ret=-ENOMEM;goto fail;}
    }
    if(!snapshot_geometry_attachments(owner,words,scratch)){ret=-EINVAL;goto fail;}
    ret=snapshot_joint_seal(owner,root,&request,SNAPSHOT_JOINT_GNQ7);
    if(ret)goto fail;
    kvfree(scratch);
    return 0;
fail:
    kvfree(scratch);
    owner->phase=SNAPSHOT_FAILED;
    return ret;
}
