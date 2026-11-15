/* Test-only original five-stage lease export. Never a provider permission. */
static void geometry_mesa_handoff(const char *prefix,const struct opengpu_native_five_description *d){
    char path[4096];const char *stem=strrchr(prefix,'/');stem=stem?stem+1:prefix;
    assert(*stem&&d->geometry.words&&d->geometry.words<=512);
    assert(snprintf(path,sizeof(path),"%s.geometry.json",prefix)>0);
    FILE *f=fopen(path,"w");assert(f);
    const struct opengpu_native_geometry_layout *g=&d->geometry.layout;
    fprintf(f,"{\"schema\":\"opengpu-test-only-mesa-geometry-v1\",\"codeWords\":%u,\"tesOutputComponents\":%u,\"geometry\":[",
        d->geometry.words,d->tess[1].layout.output_components);
    const unsigned fields[]={g->version,g->input_primitive,g->input_vertices,g->invocations,
        g->output_topology,g->max_vertices,g->input_components,g->output_components,
        g->builtin_output_mask,g->input_varyings,g->output_varyings};
    for(unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++)fprintf(f,"%s%u",i?",":"",fields[i]);
    fputs("],\"inputSources\":[",f);
    for(unsigned i=0;i<64;i++)fprintf(f,"%s%u",i?",":"",g->input_sources[i]);
    fputs("],\"outputSources\":[",f);
    for(unsigned i=0;i<68;i++)fprintf(f,"%s[%u,%u]",i?",":"",g->output_sources[i].valid,g->output_sources[i].source);
    fputs("],\"evaluationComponents\":[",f);
    for(unsigned i=0;i<64;i++)fprintf(f,"%s[%u,%u]",i?",":"",d->evaluation_components[i].valid,d->evaluation_components[i].component);
    fputs("],\"constantBytes\":[",f);
    for(unsigned i=0;i<13;i++)fprintf(f,"%s%u",i?",":"",d->geometry.constant_bytes[i]);
    fprintf(f,"],\"activeMask\":%u,\"uniforms\":[",d->uniforms[3].active_mask);
    for(unsigned i=0;i<13;i++){
        const struct drm_opengpu_compute_uniform_view *v=i?&d->uniforms[3].blocks[i-1]:&d->uniforms[3].defaults;
        assert(v->handle<N&&v->handle&&bos[v->handle].handle&&v->bytes&&v->offset<=bos[v->handle].size&&v->bytes<=bos[v->handle].size-v->offset);
        assert(snprintf(path,sizeof(path),"%s.gs-uniform-%u.bin",prefix,i)>0);
        FILE *data=fopen(path,"wb");assert(data);
        assert(fwrite(bos[v->handle].data+v->offset,1,v->bytes,data)==v->bytes&&!fclose(data));
        fprintf(f,"%s{\"handle\":%u,\"offset\":\"%llu\",\"bytes\":%u,\"file\":\"%s.gs-uniform-%u.bin\"}",
            i?",":"",v->handle,(unsigned long long)v->offset,v->bytes,stem,i);
    }
    assert(fputs("]}\n",f)>=0&&!fclose(f));
}
