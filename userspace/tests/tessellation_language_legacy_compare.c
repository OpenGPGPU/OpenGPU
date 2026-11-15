/* SPDX-License-Identifier: MIT */
/* Optional original-source byte comparator. The baseline emitter is supplied
 * as an explicit immutable source input and compiled with renamed symbols. */
#include "opengpu_native_tess_uniform_emit.h"
#include <assert.h>
#include <string.h>
int baseline_opengpu_native_tess_uniform_emit(const void *,unsigned,const struct opengpu_native_tess_layout *,struct opengpu_native_tess_program *);
int baseline_opengpu_native_tess_control_emit(const void *,unsigned,const struct opengpu_native_tess_layout *,const struct opengpu_native_tess_input_map *,struct opengpu_native_tess_program *);
int baseline_opengpu_native_tess_dynamic_emit(const void *,unsigned,const struct opengpu_native_tess_layout *,const struct opengpu_native_tess_input_map *,struct opengpu_native_tess_program *);
void tessellation_old_bytes_equal(const void *tokens,unsigned n,const struct opengpu_native_tess_layout *l,
    const struct opengpu_native_tess_input_map *map,const struct opengpu_native_tess_program *current)
{
    struct opengpu_native_tess_program baseline;
    int status=l->version==2?baseline_opengpu_native_tess_dynamic_emit(tokens,n,l,map,&baseline):
        map?baseline_opengpu_native_tess_control_emit(tokens,n,l,map,&baseline):
        baseline_opengpu_native_tess_uniform_emit(tokens,n,l,&baseline);
    assert(!status&&baseline.words==current->words);
    assert(!memcmp(baseline.code,current->code,current->words*4));
    assert(baseline.selection_count==current->selection_count&&!memcmp(baseline.selections,current->selections,sizeof(baseline.selections)));
    assert(!memcmp(baseline.constant_bytes,current->constant_bytes,sizeof(baseline.constant_bytes)));
    assert(baseline.input_mask==current->input_mask&&baseline.output_mask==current->output_mask&&baseline.barriers==current->barriers);
}
