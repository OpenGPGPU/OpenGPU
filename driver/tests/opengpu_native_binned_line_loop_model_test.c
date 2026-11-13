/* Export/check software inputs for a separately approved native host model.
 * No DUT, compiler invocation, cache lookup or model fallback lives here. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include "opengpu_native_binned_topology_fixture.h"

static int file_write(const char *directory,const char *name,const void *data,size_t bytes)
{
    char path[4096];int n=snprintf(path,sizeof(path),"%s/%s",directory,name);
    if(n<0 || (size_t)n>=sizeof(path))return -1;
    FILE *out=fopen(path,"wbx");if(!out)return -1;
    int ok=fwrite(data,1,bytes,out)==bytes;return fclose(out)==0 && ok?0:-1;
}
static int cpu_check(const struct opengpu_ll_fixture *f,unsigned index)
{
    const uint8_t *draw=f->memory+OPENGPU_LL_DRAW;
    assert(opengpu_ll_get(f->memory,0)==0x474e4a01u);
    assert(opengpu_ll_get(draw,34)==2 && opengpu_ll_get(draw,317)==2);
    assert(opengpu_ll_get(draw,311)==7 && opengpu_ll_get(draw,326)==1);
    assert(opengpu_ll_get(draw,323)-opengpu_ll_get(draw,322)==(index==1?13u:14u));
    assert(f->vertex_words<256 && f->fragment_words<256);
    assert(opengpu_ll_get(f->memory+OPENGPU_LL_VS,f->vertex_words-1)==0x30500073u);
    assert(opengpu_ll_get(f->memory+OPENGPU_LL_FS,f->fragment_words-1)==0x30500073u);
    const size_t count=sizeof(opengpu_ll_regions)/sizeof(opengpu_ll_regions[0]);
    uint32_t end=0;
    for(size_t i=0;i<count;i++) {
        const struct opengpu_ll_region *r=&opengpu_ll_regions[i];
        assert(r->offset>=end && !(r->offset&63) && !(r->bytes&63));
        assert(r->offset<=OPENGPU_LL_MEMORY_BYTES-r->bytes);end=r->offset+r->bytes;
    }
    for(unsigned i=0;i<128;i++)assert(f->memory[OPENGPU_LL_IMAGE+i]==0xa7);
    return 0;
}
static int export_fixtures(const char *directory,int topologies)
{
    char path[4096];int n=snprintf(path,sizeof(path),"%s/fixture.json",directory);
    if(n<0 || (size_t)n>=sizeof(path))return -1;
    FILE *json=fopen(path,"wx");if(!json)return -1;
    fprintf(json,"{\"schema\":\"%s\",\"cpuPreparedOnly\":true,"
          "\"width\":4,\"height\":4,\"samples\":1,\"lanes\":16,\"warps\":8,"
          "\"computeUnits\":2,\"nativeEngines\":2,\"attributes\":16,\"bindings\":16,"
          "\"varyings\":16,\"colors\":4,\"maxServiceCycles\":600000,"
          "\"submissions\":[",topologies?"opengpu-native-topology-v1":"opengpu-native-line-loop-v1");
    for(unsigned index=0;index<(topologies?OPENGPU_TOPOLOGY_SUBMISSIONS:3u);index++) {
        struct opengpu_ll_fixture f;
        if(topologies?opengpu_topology_build(&f,index):opengpu_ll_build(&f,index)) {fclose(json);return -1;}
        if(!topologies)cpu_check(&f,index);
        char memory_name[32],image_name[32];
        static const char *const topology_names[]={"points","lines","line-loop","line-strip",
            "triangles","triangle-strip","triangle-fan","index-bound","strip-reuse"};
        snprintf(memory_name,sizeof(memory_name),"memory-%u.bin",index);
        snprintf(image_name,sizeof(image_name),"expected-image-%u.bin",index);
        if(file_write(directory,memory_name,f.memory,OPENGPU_LL_MEMORY_BYTES) ||
           file_write(directory,image_name,f.expected_image,sizeof(f.expected_image))) {
            opengpu_ll_destroy(&f);fclose(json);return -1;
        }
        fprintf(json,"%s{\"index\":%u,\"name\":\"%s\",\"base\":%u,\"memoryFile\":\"%s\","
            "\"memoryBytes\":%u,\"generation\":%u,\"context\":7,\"pass\":8,\"commandId\":%u,"
            "\"opcode\":7,\"jobBase\":%u,\"jobLimit\":%u,\"expectedStatus\":%u,"
            "\"expectedFaultStage\":%u,\"expectedFaultAddress\":%u,"
            "\"imageOffset\":%u,\"expectedImageFile\":\"%s\",\"imageBytes\":128,\"redQuantum\":%u,"
            "\"vertexWords\":%u,\"fragmentWords\":%u,\"topology\":%u,\"indexFormat\":%u,\"regions\":[",
            index?",":"",index,topologies?topology_names[index]:(index==0?"healthy":(index==1?"index-bound":"healthy-reuse")),
            f.base,memory_name,OPENGPU_LL_MEMORY_BYTES,f.generation,f.command_id,
            f.base,f.base+512,f.expected_status,f.expected_status==2?3:0,f.expected_status==2?f.base+OPENGPU_LL_INDICES:0,
            OPENGPU_LL_IMAGE,image_name,f.red_quantum,f.vertex_words,f.fragment_words,
            opengpu_ll_get(f.memory+OPENGPU_LL_DRAW,OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_TOPOLOGY_WORD),
            opengpu_ll_get(f.memory+OPENGPU_LL_DRAW,OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_INDEXFORMAT_WORD));
        const size_t count=sizeof(opengpu_ll_regions)/sizeof(opengpu_ll_regions[0]);
        for(size_t i=0;i<count;i++) {
            const struct opengpu_ll_region *r=&opengpu_ll_regions[i];
            fprintf(json,"%s{\"name\":\"%s\",\"address\":%u,\"offset\":%u,\"bytes\":%u,\"writable\":%s}",
                i?",":"",r->name,f.base+r->offset,r->offset,r->bytes,r->writable?"true":"false");
        }
        fputs("]}",json);opengpu_ll_destroy(&f);
    }
    fputs("]}\n",json);return fclose(json)==0?0:-1;
}
static int verify_memory(unsigned index,const char *path,int topologies)
{
    struct opengpu_ll_fixture f;
    if(topologies?opengpu_topology_build(&f,index):opengpu_ll_build(&f,index))return -1;
    FILE *in=fopen(path,"rb");if(!in){opengpu_ll_destroy(&f);return -1;}
    uint8_t *actual=(uint8_t *)malloc(OPENGPU_LL_MEMORY_BYTES);int ok=actual!=NULL;
    if(ok)ok=fread(actual,1,OPENGPU_LL_MEMORY_BYTES,in)==OPENGPU_LL_MEMORY_BYTES && fgetc(in)==EOF;
    if(fclose(in))ok=0;
    const size_t count=sizeof(opengpu_ll_regions)/sizeof(opengpu_ll_regions[0]);
    for(size_t i=0;ok && i<count;i++) {
        const struct opengpu_ll_region *r=&opengpu_ll_regions[i];
        if(!r->writable)ok=memcmp(actual+r->offset,f.memory+r->offset,r->bytes)==0;
    }
    if(ok)ok=memcmp(actual+OPENGPU_LL_IMAGE,f.expected_image,OPENGPU_LL_IMAGE_BYTES)==0;
    free(actual);opengpu_ll_destroy(&f);return ok?0:-1;
}
int main(int argc,char **argv)
{
    if(argc==3 && (!strcmp(argv[1],"--export") || !strcmp(argv[1],"--export-topologies"))) {
        if(export_fixtures(argv[2],!strcmp(argv[1],"--export-topologies"))) {perror("CPU fixture export");return 1;}
        puts("CPU native fixture export PASS; no GPU model executed");return 0;
    }
    if(argc==4 && (!strcmp(argv[1],"--verify-memory") || !strcmp(argv[1],"--verify-topology-memory"))) {
        const int topologies=!strcmp(argv[1],"--verify-topology-memory");
        char *end=NULL;errno=0;unsigned long index=strtoul(argv[2],&end,10);
        if(errno || !end || *end || index>=(topologies?OPENGPU_TOPOLOGY_SUBMISSIONS:3u) || verify_memory((unsigned)index,argv[3],topologies))return 1;
        puts("Returned bytes match the CPU oracle; this command does not verify DUT provenance or completion");return 0;
    }
    if(argc!=1)return 2;
    for(unsigned index=0;index<3;index++) {
        struct opengpu_ll_fixture f;assert(!opengpu_ll_build(&f,index));cpu_check(&f,index);opengpu_ll_destroy(&f);
    }
    puts("CPU LINE_LOOP records/ranges/shader bounds PASS; no GPU model executed");return 0;
}
