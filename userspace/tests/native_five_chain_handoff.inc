#include "tgsi/tgsi_parse.h"
static void fc_unsigned(FILE *f,const unsigned *v,unsigned n){
    fputc('[',f);
    for(unsigned i=0;i<n;i++)fprintf(f,"%s%u",i?",":"",v[i]);
    fputc(']',f);
}
static void fc_map(FILE *f,const struct opengpu_native_tess_input_map *m){
    fprintf(f,"{\"version\":%u,\"count\":%u,\"spans\":[",m->version,m->count);
    assert(m->count<=64);
    for(unsigned i=0;i<64;i++){
        const struct opengpu_native_tess_input_span *s=&m->spans[i];
        const unsigned v[]={s->first,s->slots,s->per_vertex,s->semantic,s->index,s->mask,s->compact};
        if(i)fputc(',',f);
        fc_unsigned(f,v,7);
    }
    fputs("]}",f);
}
static void fc_layout(FILE *f,const struct opengpu_native_tess_program *p){
    const struct opengpu_native_tess_layout *l=&p->layout;
    const unsigned v[]={l->version,l->stage,l->capacity,l->input_vertices,l->output_vertices,
        l->input_components,l->output_components,l->patch_components,l->domain,l->spacing,
        l->clockwise,l->point_mode,l->input_position,l->output_position,l->tes_patch_layout.input_vertices,
        l->tes_patch_layout.input_components,l->tes_patch_layout.output_vertices,
        l->tes_patch_layout.output_components,l->tes_patch_layout.patch_components,l->owned_patch_reads,l->owned_tessellation_info};
    fputs("{\"layout\":",f);fc_unsigned(f,v,sizeof(v)/sizeof(v[0]));
    fprintf(f,",\"selectionCount\":%u,\"selections\":[",p->selection_count);
    for(unsigned i=0;i<61;i++){
        const struct opengpu_native_tess_selection *s=&p->selections[i];
        const unsigned a[]={s->per_patch,s->tess_level,s->vertex,s->component};
        if(i)fputc(',',f);
        fc_unsigned(f,a,4);
    }
    fprintf(f,"],\"inputMask\":\"%016llx\",\"outputMask\":\"%016llx\",\"barriers\":%u,\"inputMap\":",
        (unsigned long long)p->input_mask,(unsigned long long)p->output_mask,p->barriers);
    fc_map(f,&p->input_map);fputs(",\"outputMap\":",f);fc_map(f,&p->output_map);fputc('}',f);
}
static void five_chain_handoff(struct opengpu_native_five_link *link,const char *prefix,const struct opengpu_native_five_description *d,
    const struct opengpu_native_five_input *input){
    assert(d->context_id==1&&d->fragment.metadata.abi_version==5);
    assert(d->fragment.metadata.requires_geometry_owner&&!d->fragment.metadata.requires_sample_owner&&
        !d->fragment.metadata.requires_primitive_owner&&!d->fragment.metadata.writes_depth&&
        !d->fragment.metadata.writes_sample_mask);
    const char *stem=strrchr(prefix,'/');stem=stem?stem+1:prefix;
    char path[4096];int length=snprintf(path,sizeof(path),"%s.five.json",prefix);assert(length>0&&(size_t)length<sizeof(path));
    FILE *f=fopen(path,"w");assert(f);
    const unsigned vl[]={d->vertex_layout.stage,d->vertex_layout.capacity,d->vertex_layout.attributes,
        d->vertex_layout.varyings,d->vertex_layout.colors};
    fprintf(f,"{\"schema\":\"opengpu-private-original-linked-five-v1\",\"contextId\":%u,\"vertexLayout\":",d->context_id);
    fc_unsigned(f,vl,5);
    fprintf(f,",\"vertexVaryingMask\":\"%016llx\",\"vertexColorMask\":%u,\"tess\":[",
        (unsigned long long)d->vertex.varying_mask,d->vertex.color_mask);
    fc_layout(f,&d->tess[0]);fputc(',',f);fc_layout(f,&d->tess[1]);fputs("],\"vertexComponents\":[",f);
    for(unsigned i=0;i<64;i++)fprintf(f,"%s[%u,%u]",i?",":"",d->vertex_components[i].kind,d->vertex_components[i].component);
    fputs("],\"fragmentComponents\":[",f);
    for(unsigned i=0;i<68;i++)fprintf(f,"%s[%u,%u]",i?",":"",d->fragment_components[i].valid,d->fragment_components[i].component);
    fputs("],\"linkedMaps\":[",f);
    for(unsigned i=0;i<8;i++){if(i)fputc(',',f);fc_map(f,&input->maps[i]);}
    fputs("],\"tgsiProperties\":[",f);
    for(unsigned s=0;s<5;s++){
        if(s)fputc(',',f);
        fputc('[',f);
        unsigned count=0;struct tgsi_parse_context p;
        assert(tgsi_parse_init(&p,input->tokens[s])==TGSI_PARSE_OK);
        while(!tgsi_parse_end_of_tokens(&p)){
            tgsi_parse_token(&p);
            if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_PROPERTY){
                const struct tgsi_full_property *q=&p.FullToken.FullProperty;
                assert(q->Property.NrTokens>=1&&q->Property.NrTokens<=9);
                fprintf(f,"%s[%u",count++?",":"",q->Property.PropertyName);
                for(unsigned i=1;i<q->Property.NrTokens;i++)fprintf(f,",%u",q->u[i-1].Data);
                fputc(']',f);
            }
        }
        tgsi_parse_free(&p);fputc(']',f);
    }
    fputs("],\"stages\":[",f);
    const char *names[]={"vs","tcs","tes","gs","fs"};const unsigned stages[]={0,4,5,3,1};
    for(unsigned s=0;s<5;s++){
        const struct drm_opengpu_native_uniform_stage *u=&d->uniforms[s];
        const struct drm_opengpu_native_program_view *c=&d->code[s];
        const unsigned *constants=s==0?d->vertex.constant_bytes:s==4?d->fragment.metadata.constant_bytes:s==3?d->geometry.constant_bytes:d->tess[s-1].constant_bytes;
        assert(!u->pad&&!c->pad&&c->handle&&c->handle<N&&c->bytes&&c->bytes<=bos[c->handle].size&&
            c->offset<=bos[c->handle].size-c->bytes);
        fprintf(f,"%s{\"name\":\"%s\",\"stage\":%u,\"code\":{\"handle\":%u,\"offset\":\"%llu\",\"bytes\":%u,\"entryOffset\":%u},\"constantBytes\":",
            s?",":"",names[s],stages[s],c->handle,(unsigned long long)c->offset,c->bytes,c->entry_offset);
        fc_unsigned(f,constants,13);fprintf(f,",\"activeMask\":%u,\"bindings\":[",u->active_mask);
        for(unsigned i=0;i<12;i++)fprintf(f,"%s%u",i?",":"",opengpu_native_five_binding(link,stages[s],i));
        fputs("],\"uniforms\":[",f);
        for(unsigned i=0;i<13;i++){
            const struct drm_opengpu_compute_uniform_view *v=i?&u->blocks[i-1]:&u->defaults;
            assert(v->handle&&v->handle<N&&bos[v->handle].handle&&v->offset<=bos[v->handle].size&&v->bytes<=bos[v->handle].size-v->offset);
            length=snprintf(path,sizeof(path),"%s.%s-uniform-%u.bin",prefix,names[s],i);assert(length>0&&(size_t)length<sizeof(path));
            FILE *data=fopen(path,"wb");assert(data);
            assert(fwrite(bos[v->handle].data+v->offset,1,v->bytes,data)==v->bytes&&!fclose(data));
            fprintf(f,"%s{\"handle\":%u,\"offset\":\"%llu\",\"bytes\":%u,\"file\":\"%s.%s-uniform-%u.bin\"}",
                i?",":"",v->handle,(unsigned long long)v->offset,v->bytes,stem,names[s],i);
        }
        fputs("]}",f);
    }
    assert(fputs("]}\n",f)>=0&&!fclose(f));
}
