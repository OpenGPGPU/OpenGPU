/* SPDX-License-Identifier: MIT */
/* Actual original Mesa ESSL -> TGSI evidence. CPU compiler only, no shader VM. */
#include "opengpu_tess_frontend.h"
#include "opengpu_native_tess_uniform_emit.h"
#ifdef OPENGPU_TESS_BYTE_BASELINE
extern "C" void tessellation_old_bytes_equal(const void *,unsigned,const opengpu_native_tess_layout *,
    const opengpu_native_tess_input_map *,const opengpu_native_tess_program *);
#endif
#include "tgsi/tgsi_parse.h"
#include <cassert>
#include <cstdio>
#include <string>
#include <cstring>
#include <vector>
static void save(const std::string &path,const opengpu_native_tess_program &p){
 FILE *f=fopen(path.c_str(),"wb");assert(f);
 for(unsigned i=0;i<p.words;i++)for(unsigned b=0;b<4;b++)assert(fputc((p.code[i]>>(8*b))&255,f)!=EOF);
 assert(!fclose(f));
}
static void run(char *const *argv,const char *name){
 const char *suffix[]={"vert","tesc","tese",nullptr,"frag"};
 std::string paths[5];const char *files[5]={};
 for(unsigned i=0;i<5;i++)if(suffix[i]){paths[i]=std::string(argv[1])+"/"+std::string(name)+"."+suffix[i];files[i]=paths[i].c_str();}
 std::string prefix=std::string(argv[2])+"/"+name+"/actual";
 void *p=opengpu_tess_frontend_compile(files,prefix.c_str());assert(p);
 for(unsigned stage:{4u,5u}){
  auto *t=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(p,stage));assert(t);
  printf("ACTUAL_DYNAMIC_TGSI stage=%u words=%u\n",stage,tgsi_num_tokens(t));
 }
 opengpu_native_tess_layout control={},evaluation={};opengpu_native_tess_program tcs={},tes={};
 auto *tc=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(p,4));
 auto *te=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(p,5));
 assert(!opengpu_native_tess_uniform_reflect(tc,tgsi_num_tokens(tc),4,128,(!strcmp(name,"capacity")||!strcmp(name,"edge"))?32:2,&control));
 assert(!opengpu_native_tess_dynamic_reflect(te,tgsi_num_tokens(te),128,&control,&evaluation));
 opengpu_native_tess_input_map outputs={};assert(!opengpu_tess_frontend_control_output_map(p,&outputs));
 for(unsigned i=0;i<outputs.count;i++){auto &s=outputs.spans[i];printf("ACTUAL_NIR_OUTPUT_SPAN first=%u slots=%u vertex=%u semantic=%u index=%u mask=%u compact=%u\n",s.first,s.slots,s.per_vertex,s.semantic,s.index,s.mask,s.compact);}
 assert(!opengpu_native_tess_control_emit(tc,tgsi_num_tokens(tc),&control,&outputs,&tcs));
 opengpu_native_tess_input_map map={};assert(!opengpu_tess_frontend_input_map(p,&map));
 for(unsigned i=0;i<map.count;i++){auto &s=map.spans[i];
  printf("ACTUAL_NIR_INPUT_SPAN first=%u slots=%u vertex=%u semantic=%u index=%u mask=%u\n",s.first,s.slots,s.per_vertex,s.semantic,s.index,s.mask);}
 assert(!opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&map,&tes));
#ifdef OPENGPU_TESS_BYTE_BASELINE
 tessellation_old_bytes_equal(tc,tgsi_num_tokens(tc),&control,&outputs,&tcs);
 tessellation_old_bytes_equal(te,tgsi_num_tokens(te),&evaluation,&map,&tes);
#endif
 assert(evaluation.version==2&&tes.selection_count==0);
 for(auto &s:tes.selections){opengpu_native_tess_selection zero={};assert(!memcmp(&s,&zero,sizeof(s)));}
 unsigned reads=0;for(unsigned i=0;i<tes.words;i++)if((tes.code[i]&127)==0x2b&&(tes.code[i]>>26)==33)reads++;
 assert(reads);auto saved=tes;
 assert(opengpu_native_tess_uniform_emit(te,tgsi_num_tokens(te),&evaluation,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 evaluation.version=1;assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&map,&tes));
 assert(!memcmp(&tes,&saved,sizeof(tes)));evaluation.version=2;
 auto bad=map;bad.spans[0].first=63;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&bad,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 bad=map;bad.spans[0].compact=1;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&bad,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 bad=map;bad.count=65;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&bad,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 bad=map;bad.version=2;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&bad,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 if(!strcmp(name,"adjacent")){
  bool tested=false;
  for(unsigned i=0;i<map.count;i++)for(unsigned j=0;j<map.count;j++)if(i!=j&&map.spans[i].semantic==map.spans[j].semantic&&
    map.spans[i].first+map.spans[i].slots==map.spans[j].first&&map.spans[i].per_vertex==map.spans[j].per_vertex){
   bad=map;bad.spans[i].slots++;assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&evaluation,&bad,&tes));tested=true;
  }assert(tested);
 }
 auto layout=evaluation;layout.owned_patch_reads=0;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&layout,&map,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 layout=evaluation;layout.tes_patch_layout.output_vertices=32;layout.tes_patch_layout.output_components=64;layout.tes_patch_layout.patch_components=120;
 assert(opengpu_native_tess_dynamic_emit(te,tgsi_num_tokens(te),&layout,&map,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 std::vector<tgsi_token> truncated(te,te+tgsi_num_tokens(te));
 for(unsigned n=3;n<tgsi_num_tokens(te);n++){
  auto *header=reinterpret_cast<tgsi_header *>(truncated.data());header->BodySize=n-2;
  assert(opengpu_native_tess_dynamic_emit(truncated.data(),n,&evaluation,&map,&tes));assert(!memcmp(&tes,&saved,sizeof(tes)));
 }
 auto controlSaved=tcs;bad=outputs;
 for(unsigned i=0;i<bad.count;i++)if(bad.spans[i].compact){bad.spans[i].compact=0;break;}
 assert(opengpu_native_tess_control_emit(tc,tgsi_num_tokens(tc),&control,&bad,&tcs));assert(!memcmp(&tcs,&controlSaved,sizeof(tcs)));
 for(unsigned mode=0;mode<4;mode++){
  bad=outputs;bool tested=false;
  for(unsigned i=0;i<bad.count;i++)if(bad.spans[i].compact){
   auto &s=bad.spans[i];
   if(mode==0)s.index=1;
   if(mode==1)s.slots=3;
   if(mode==2)s.mask=31;
   if(mode==3)s.semantic=TGSI_SEMANTIC_GENERIC;
   tested=true;break;
  }
  assert(tested);assert(opengpu_native_tess_control_emit(tc,tgsi_num_tokens(tc),&control,&bad,&tcs));assert(!memcmp(&tcs,&controlSaved,sizeof(tcs)));
 }
 printf("ACTUAL_DYNAMIC_NATIVE tcsWords=%u tesWords=%u reads=%u patch=%u/%u/%u/%u/%u exports=%u\n",tcs.words,tes.words,reads,
  control.input_vertices,control.input_components,control.output_vertices,control.output_components,control.patch_components,evaluation.output_components);
 save(std::string(argv[2])+"/"+std::string(name)+"/native-tcs.words",controlSaved);save(std::string(argv[2])+"/"+std::string(name)+"/native-tes.words",saved);
 opengpu_tess_frontend_destroy(p);
 puts("Actual Mesa TES v2 compiler CPU complete; no ISA execution or GPU result claimed");
}

int main(int argc,char **argv){setvbuf(stdout,nullptr,_IONBF,0);assert(argc==3);for(const char *name:{"dynamic","adjacent","capacity","edge","component"}){printf("DYNAMIC_CASE %s\n",name);run(argv,name);}}
