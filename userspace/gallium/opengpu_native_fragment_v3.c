/* SPDX-License-Identifier: MIT */
#include "opengpu_native_fragment_v3.h"
#include <stdio.h>
#include <string.h>
#define INPUT_BITS (1u|2u|4u|8u|16u|32u|64u|128u|256u|512u)
int opengpu_native_fragment_metadata_init_v3(const struct opengpu_native_fragment_reflection_v3 *r,
    const struct opengpu_native_fragment_config_v3 *c,struct opengpu_native_fragment_metadata_v2 *out)
{
    struct opengpu_native_fragment_metadata_v2 m={0};
    if(!r||!c||!out||r->version!=3||r->essl_version!=320||r->linked_geometry!=1||
       (r->static_inputs&~INPUT_BITS)||c->thread_capacity!=128||c->varying_slots>16||
       c->color_slots>4||c->geometry_transport!=1||c->sample_transport>1||c->geometry_pixel_mask_transport>1)return -1;
    m.requires_sample_owner=!!(r->static_inputs&(1u|2u|512u));
    if((m.requires_sample_owner&&!c->sample_transport)||
       (m.requires_sample_owner&&c->geometry_pixel_mask_transport))return -1;
    m.abi_version=c->geometry_pixel_mask_transport?10:m.requires_sample_owner?7:5;
    m.thread_capacity=128;m.varying_slots=c->varying_slots;m.color_slots=c->color_slots;
    m.input_arrays=10+4*c->varying_slots+3*m.requires_sample_owner;
    m.array_stride=512;m.prefix_bytes=(m.input_arrays+4*c->color_slots+2)*512;
    m.requires_geometry_owner=1;m.static_inputs=r->static_inputs;
    for(unsigned i=0;i<4;i++){
        if(r->color_types[i]>2||(i>=c->color_slots&&r->color_types[i]))return -1;
        m.color_types[i]=r->color_types[i];
    }
    *out=m;return 0;
}
int opengpu_native_fragment_metadata_valid_v3(const struct opengpu_native_fragment_metadata_v2 *m)
{
    if(!m||(m->abi_version!=5&&m->abi_version!=7&&m->abi_version!=10)||m->thread_capacity!=128||
       m->varying_slots>16||m->color_slots>4||m->array_stride!=512||
       m->input_arrays!=10+4*m->varying_slots+3*(m->abi_version==7)||
       m->prefix_bytes!=(m->input_arrays+4*m->color_slots+2)*512||
       m->requires_primitive_owner||m->requires_geometry_owner!=1||
       m->requires_sample_owner!=(uint32_t)(m->abi_version==7)||
       m->requires_sample_owner!=!!(m->static_inputs&(1u|2u|512u))||
       (m->static_inputs&~INPUT_BITS)||(m->used_sysvals&~INPUT_BITS)||
       ((m->used_sysvals&(1u|2u))&&m->abi_version!=7)||
       ((m->used_sysvals&~16u)&~m->static_inputs)||
       m->writes_depth>1||m->writes_sample_mask>1||(m->writes_sample_mask&&m->abi_version!=7&&m->abi_version!=10)||m->requires_uniform_owner>1||
       (m->output_color_mask>>m->color_slots))return 0;
    uint64_t available=m->varying_slots==16?UINT64_MAX:((UINT64_C(1)<<(4*m->varying_slots))-1);
    if((m->varying_mask|m->flat_mask|m->centroid_mask)&~available)return 0;
    if(m->flat_mask&m->centroid_mask)return 0;
    unsigned uniforms=0;
    for(unsigned i=0;i<13;i++){
        if((m->constant_bytes[i]&3)||m->constant_bytes[i]>(i?16384u:4096u))return 0;
        uniforms|=m->constant_bytes[i];
    }
    if(m->requires_uniform_owner!=!!uniforms)return 0;
    for(unsigned i=0;i<4;i++)if(m->color_types[i]>2||(i>=m->color_slots&&m->color_types[i]))return 0;
    return 1;
}
static int le(FILE *f,uint32_t w)
{ unsigned char b[4]={(unsigned char)w,(unsigned char)(w>>8),(unsigned char)(w>>16),(unsigned char)(w>>24)};return fwrite(b,1,4,f)==4?0:-1; }
int opengpu_native_fragment_write_v3(const char *path,const struct opengpu_native_fragment_program_v3 *p)
{
    if(!path||!p||!p->words||p->words>512||!opengpu_native_fragment_metadata_valid_v3(&p->metadata))return -1;
    const struct opengpu_native_fragment_metadata_v2 *m=&p->metadata;
    uint32_t h[42]={3,m->abi_version,m->input_arrays,m->array_stride,m->prefix_bytes,m->used_sysvals,
        m->output_color_mask,m->writes_depth,m->writes_sample_mask,m->requires_sample_owner,
        m->requires_geometry_owner,m->thread_capacity,m->varying_slots,m->color_slots,p->words,
        m->requires_primitive_owner,m->requires_uniform_owner,m->static_inputs,
        (uint32_t)m->varying_mask,(uint32_t)(m->varying_mask>>32),
        (uint32_t)m->flat_mask,(uint32_t)(m->flat_mask>>32),
        (uint32_t)m->centroid_mask,(uint32_t)(m->centroid_mask>>32)};
    memcpy(h+24,m->constant_bytes,sizeof(m->constant_bytes));memcpy(h+37,m->color_types,sizeof(m->color_types));
    FILE *f=fopen(path,"wx");if(!f)return -1;
    int error=fwrite("OGNSF003",1,8,f)!=8;
    for(unsigned i=0;i<42;i++)if(le(f,h[i]))error=1;
    for(unsigned i=0;i<p->words;i++)if(le(f,p->code[i]))error=1;
    if(fclose(f))error=1;
    if(error)remove(path);
    return error?-1:0;
}

static int readle(FILE *f,uint32_t *v)
{
    unsigned char b[4];if(fread(b,1,4,f)!=4)return -1;
    *v=(uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24;return 0;
}
int opengpu_native_fragment_read_v3(const char *path,struct opengpu_native_fragment_program_v3 *out)
{
    if(!path||!out)return -1;
    FILE *f=fopen(path,"rb");if(!f)return -1;
    struct opengpu_native_fragment_program_v3 p={0};char magic[8];uint32_t h[42];int result=-1;
    if(fread(magic,1,8,f)!=8||memcmp(magic,"OGNSF003",8))goto done;
    for(unsigned i=0;i<42;i++)if(readle(f,h+i))goto done;
    if(h[0]!=3||h[41]||!h[14]||h[14]>512)goto done;
    struct opengpu_native_fragment_metadata_v2 *m=&p.metadata;
    m->abi_version=h[1];m->input_arrays=h[2];m->array_stride=h[3];m->prefix_bytes=h[4];
    m->used_sysvals=h[5];m->output_color_mask=h[6];m->writes_depth=h[7];m->writes_sample_mask=h[8];
    m->requires_sample_owner=h[9];m->requires_geometry_owner=h[10];m->thread_capacity=h[11];
    m->varying_slots=h[12];m->color_slots=h[13];p.words=h[14];
    m->requires_primitive_owner=h[15];m->requires_uniform_owner=h[16];m->static_inputs=h[17];
    m->varying_mask=(uint64_t)h[18]|(uint64_t)h[19]<<32;
    m->flat_mask=(uint64_t)h[20]|(uint64_t)h[21]<<32;
    m->centroid_mask=(uint64_t)h[22]|(uint64_t)h[23]<<32;
    memcpy(m->constant_bytes,h+24,sizeof(m->constant_bytes));memcpy(m->color_types,h+37,sizeof(m->color_types));
    if(!opengpu_native_fragment_metadata_valid_v3(m))goto done;
    for(unsigned i=0;i<p.words;i++)if(readle(f,p.code+i))goto done;
    if(fgetc(f)!=EOF||ferror(f))goto done;
    result=0;
done:
    if(fclose(f))result=-1;
    if(!result)*out=p;
    return result;
}

#include "opengpu_native_fragment_emit_internal.h"
int opengpu_native_fragment_uniform_compile_v3(const void *tokens,unsigned words,
    const struct opengpu_native_fragment_reflection_v3 *r,const struct opengpu_native_fragment_config_v3 *c,
    struct opengpu_native_fragment_program_v3 *out)
{
    struct opengpu_native_fragment_program_v3 p={0};struct opengpu_native_uniform_program machine;
    if(!out||opengpu_native_fragment_metadata_init_v3(r,c,&p.metadata)||
       opengpu_native_fragment_emit_metadata(tokens,words,&p.metadata,&machine)||
       !opengpu_native_fragment_metadata_valid_v3(&p.metadata))return -1;
    p.words=machine.words;memcpy(p.code,machine.code,p.words*4);*out=p;return 0;
}
