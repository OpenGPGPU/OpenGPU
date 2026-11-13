/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_NATIVE_GRAPH_ALIAS_H
#define OPENGPU_NATIVE_GRAPH_ALIAS_H
#include "opengpu_native_job_snapshot.h"

/* Check physical cache-line aliases across typed GND and GNF components before
 * copying or appending the graph. Resolve only retained, producer-complete GEMs.
 * Read-only inputs may share. Sequential draws may reuse vertex scratch;
 * inputs/vertex scratch may reuse later fragment scratch, and inputs may reuse
 * later images. Vertex scratch may not alias images (the existing GNC2 rule),
 * any draw's inputs/control, or concurrent bin arena/pool/directory resources.
 * GNF-internal alias/role validation remains in frame_snapshot_append_complete.
 * No owner state changes and no arbitrary draw-count limit are introduced. */
int opengpu_native_graph_alias_check(const struct opengpu_native_job_source *,
    opengpu_native_frame_resolve,void *);
#endif
