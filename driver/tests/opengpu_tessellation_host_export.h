/* Test-only serialization of retained C-prepared jobs for a real RTL witness. */
/* This trusted witness explicitly installs its own mappings. Its serialized
 * software/RTL fixture VAs are stable independently of resource_bind's private
 * window layout, which is exercised by storage_binding and real MMU tests. */
#define TESS_HOST_FRAME_VA 0xd0000000u
#define TESS_HOST_ROOT_VA 0xd8000000u
#define TESS_HOST_ROOT_DMA 0x35000000u
#define TESS_HOST_CODE_VA 0x1000u
#define TESS_HOST_CODE_DMA 0x36000000u
#define TESS_HOST_CODE_BYTES 0x3000u

static void fixture_path(char *path, size_t capacity, const char *directory, const char *name)
{
    int n = snprintf(path, capacity, "%s/%s", directory, name);
    assert(n > 0 && (size_t)n < capacity);
}

static void fixture_write(const char *directory, const char *name, const void *data, size_t bytes)
{
    char path[4096];
    fixture_path(path, sizeof(path), directory, name);
    FILE *f = fopen(path, "wb");
    assert(f && fwrite(data, 1, bytes, f) == bytes && !fclose(f));
}

static void fixture_read(const char *directory, const char *name, void *data, size_t bytes)
{
    char path[4096];
    fixture_path(path, sizeof(path), directory, name);
    read_record(path, data, bytes);
}

static void fixture_region(FILE *manifest, const char *directory, const char *name,
    u64 va, u64 dma, const void *data, size_t bytes, bool first)
{
    char filename[128];
    int n = snprintf(filename, sizeof(filename), "%s.bin", name);
    assert(n > 0 && (size_t)n < sizeof(filename));
    fixture_write(directory, filename, data, bytes);
    assert(fprintf(manifest, "%s{\"name\":\"%s\",\"va\":%llu,\"dma\":%llu,"
        "\"bytes\":%zu,\"file\":\"%s\"}", first ? "" : ",\n", name,
        (unsigned long long)va, (unsigned long long)dma, bytes, filename) > 0);
}

static void fixture_export(const char *directory, struct opengpu_render_context *context,
    struct opengpu_sched_job *job, const unsigned char *patch, const unsigned char *frame,
    const unsigned char *roots, const unsigned char *code,
    const struct opengpu_resource_binding *patch_binding,
    const struct opengpu_resource_binding *frame_binding, bool uniform_owned, bool texture_owned, bool texture_host)
{
    char path[4096], name[32];
    fixture_path(path, sizeof(path), directory, "prepared.json");
    FILE *f = fopen(path, "w");
    assert(f && context->refs == 2 && job->object_count == 3 && busy);
    assert(satp_write_count == 2 &&
        satp_writes[0].address == GPU_REG_UCMD_VECTOR_SATP &&
        satp_writes[1].address == GPU_REG_UCMD_INSTRUCTION_SATP &&
        satp_writes[0].value == satp_writes[1].value);
    assert(tlb_write_count && snapshot_invalidation_count == (texture_owned ? 5u : 4u));
    assert(!texture_owned || job->tess_fragment_code.cpu);
    if (texture_owned) assert(fputs("{\"fragmentCodeOwned\":true,", f) >= 0);
    if (texture_host) assert(fputs("\"textureHost\":true,\"textureOwned\":true,", f) >= 0);
    else if (texture_owned) assert(fputs("\"textureOwned\":true,", f) >= 0);
    else assert(fputc('{', f) != EOF);
    assert(fprintf(f, "\"version\":1,\"uniformOwned\":%s,\"context\":%u,\"rootDma\":%llu,\"asid\":%u,"
        "\"satpWrites\":[[%u,%u],[%u,%u]],\n\"commandArguments\":{"
        "\"opcode\":%u,\"source\":%u,\"destination\":%u,\"bytes\":%u,"
        "\"pattern\":%u,\"sampleMode\":%u,\"width\":%u,\"height\":%u,"
        "\"sourceStride\":%u,\"destinationStride\":%u,\"flags\":%u},\n\"tlbWrites\":[",
        uniform_owned ? "true" : "false", context->id, (unsigned long long)context->vm.root.dma, context->vm.asid,
        satp_writes[0].address, satp_writes[0].value, satp_writes[1].address, satp_writes[1].value,
        submitted_command.opcode, submitted_command.source, submitted_command.destination,
        submitted_command.bytes, submitted_command.pattern, submitted_command.sample_mode,
        submitted_command.width, submitted_command.height, submitted_command.source_stride,
        submitted_command.destination_stride, submitted_command.flags) > 0);
    for (u32 i = 0; i < tlb_write_count; ++i)
        assert(fprintf(f, "%s[%u,%u]", i ? "," : "", tlb_writes[i].address, tlb_writes[i].value) > 0);
    assert(fputs("],\n\"snapshotInvalidations\":[", f) >= 0);
    for (u32 i = 0; i < snapshot_invalidation_count; ++i)
        assert(fprintf(f, "%s[%llu,%llu]", i ? "," : "",
            (unsigned long long)snapshot_invalidations[i].dma,
            (unsigned long long)snapshot_invalidations[i].size) > 0);
    assert(fputs("],\n\"regions\":[\n", f) >= 0);
    fixture_region(f, directory, "vm-root", 0, context->vm.root.dma,
        context->vm.root.cpu, context->vm.root.size, true);
    for (u32 i = 0; i < context->vm.l1_count; ++i) {
        assert(snprintf(name, sizeof(name), "vm-l1-%u", i) > 0);
        fixture_region(f, directory, name, 0, context->vm.l1[i].dma,
            context->vm.l1[i].cpu, context->vm.l1[i].size, false);
    }
    fixture_region(f, directory, "job", job->tess_job_va, job->tess_job.dma,
        job->tess_job.cpu, job->tess_job.size, false);
    fixture_region(f, directory, "frame", job->tess_frame_va, job->tess_frame.dma,
        job->tess_frame.cpu, job->tess_frame.size, false);
    fixture_region(f, directory, "draws", job->tess_draws_va, job->tess_draws.dma,
        job->tess_draws.cpu, job->tess_draws.size, false);
    fixture_region(f, directory, "states", job->tess_states_va, job->tess_states.dma,
        job->tess_states.cpu, job->tess_states.size, false);
    fixture_region(f, directory, "patch-data", patch_binding->va, patch_binding->dma,
        patch, patch_binding->size, false);
    fixture_region(f, directory, "frame-data", frame_binding->va, frame_binding->dma,
        frame, frame_binding->size, false);
    fixture_region(f, directory, "root-data", TESS_HOST_ROOT_VA, TESS_HOST_ROOT_DMA, roots, 4096, false);
    fixture_region(f, directory, "code", TESS_HOST_CODE_VA, TESS_HOST_CODE_DMA,
        code, TESS_HOST_CODE_BYTES, false);
    if (texture_owned)
        fixture_region(f, directory, "fragment-code", job->tess_fragment_code_va,
            job->tess_fragment_code.dma, job->tess_fragment_code.cpu,
            job->tess_fragment_code.size, false);
    assert(fputs("]}\n", f) >= 0 && !fclose(f));
}

/* Canonical synthetic records test serialization/MMU plumbing without any RTL.
 * Actual execution uses the existing generated three-domain host fixtures. */
static void fixture_seed(const char *directory, u32 domain)
{
    unsigned char job[512], frame[2048], draw[2048], states[1024] = {0};
    unsigned char code[TESS_HOST_CODE_BYTES] = {0}, patch[4096] = {0};
    make_records(job, frame, draw, 7, domain, 1, 2048);
    make_bound_frame(frame, 0x100000, 0x600000, 2);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, tess_word(frame, OPENGPU_TESS_FRAME_STATE_BASE) + sizeof(states));
    make_patch_input(draw, domain, 0x4f7000);
    make_fragment_state(states, frame, 2, 0);
    for (u32 i = 1; i < 4; ++i)
        put(frame, OPENGPU_TESS_FRAME_COLOR_ENABLED + i * OPENGPU_TESS_FRAME_COLOR_STRIDE, 0);
    put(frame, OPENGPU_TESS_FRAME_DEPTH_FORMAT, 0);
    put(frame, OPENGPU_TESS_FRAME_STENCIL_FORMAT, 0);
#define CLEAR_EXPORT_RESOLVE(i, b, l, e) put(frame, e, 0);
    OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(CLEAR_EXPORT_RESOLVE)
#undef CLEAR_EXPORT_RESOLVE
    fixture_write(directory, "job.bin", job, sizeof(job));
    fixture_write(directory, "frame.bin", frame, sizeof(frame));
    fixture_write(directory, "draws.bin", draw, sizeof(draw));
    fixture_write(directory, "states.bin", states, sizeof(states));
    fixture_write(directory, "code.bin", code, sizeof(code));
    fixture_write(directory, "patch.bin", patch, sizeof(patch));
}
