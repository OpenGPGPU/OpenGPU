#include "../opengpu_tessellation_job_validate.h"
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv)
{
    unsigned char bytes[OPENGPU_NATIVE_TESSELLATION_JOB_BYTES];
    unsigned char frame[OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES];
    unsigned char draw[OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES];
    struct opengpu_tessellation_job_view job, checked;
    FILE *file;
    unsigned long domain;
    char *end;
    if (argc != 5) return 2;
    domain = strtoul(argv[2], &end, 10);
    if (*end || domain > OPENGPU_TESSELLATION_QUAD) return 2;
    file = fopen(argv[1], "rb");
    if (!file) return 2;
    if (fread(bytes, 1, sizeof(bytes), file) != sizeof(bytes) || fgetc(file) != EOF) {
        fclose(file); return 2;
    }
    fclose(file);
    if (!opengpu_tessellation_job_decode(bytes, sizeof(bytes), 0x4d0000, 0x4d0200, &job)) return 1;
    if (job.domain != domain || job.context != 7 || job.pass != 8 || job.generation != 9 ||
        job.frame_base != 0x4d1000 || job.frame_limit != 0x4d1800 ||
        job.draw_base != 0x4e0000 || job.draw_limit != 0x4e0800 ||
        job.draw_count != 1 || job.draw_stride != 2048) return 1;
    file = fopen(argv[3], "rb");
    if (!file) return 2;
    if (fread(frame, 1, sizeof(frame), file) != sizeof(frame) || fgetc(file) != EOF) {
        fclose(file); return 2;
    }
    fclose(file);
    if (!opengpu_tessellation_frame_snapshot_precheck(frame, sizeof(frame), &job)) return 1;
    if (!opengpu_tessellation_frame_canonical_precheck(frame, sizeof(frame), &job)) return 1;
    if (!opengpu_tessellation_frame_descriptor_alias_precheck(frame, sizeof(frame), &job, 2)) return 1;
    file = fopen(argv[4], "rb");
    if (!file) return 2;
    if (fread(draw, 1, sizeof(draw), file) != sizeof(draw) || fgetc(file) != EOF) {
        fclose(file); return 2;
    }
    fclose(file);
    if (!opengpu_tessellation_draw_snapshot_precheck(draw, sizeof(draw), &job, 0)) return 1;
    if (!opengpu_tessellation_draw_canonical_precheck(draw, sizeof(draw), &job, 0)) return 1;
    if (!opengpu_tessellation_draw_input_span_precheck(draw, sizeof(draw), &job, 0)) return 1;
    if (!opengpu_tessellation_snapshots_precheck(bytes, sizeof(bytes), 0x4d0000, 0x4d0200,
            frame, sizeof(frame), draw, sizeof(draw), 2, &checked)) return 1;
    if (checked.domain != domain || checked.context != job.context || checked.draw_count != 1) return 1;
    return 0;
}
