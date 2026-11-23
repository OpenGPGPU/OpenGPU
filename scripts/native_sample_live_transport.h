/* Single-record sample-host IPC using the actual Linux drain and cleanup. */
#ifndef OPENGPU_NATIVE_SAMPLE_LIVE_TRANSPORT_H
#define OPENGPU_NATIVE_SAMPLE_LIVE_TRANSPORT_H

static const char *live_directory;

static void live_require(bool ok, const char *message)
{
    if (!ok) {
        fprintf(stderr, "native sample live producer: %s\n", message);
        fflush(stderr);
        _Exit(2);
    }
}

static void live_set_directory(const char *directory)
{
    struct stat st;
    live_require(!lstat(directory, &st) && S_ISDIR(st.st_mode), "session directory");
    live_directory = directory;
}

static void live_path(char out[4096], const char *name)
{
    int n = snprintf(out, 4096, "%s/%s", live_directory, name);
    live_require(n > 0 && n < 4096, "path overflow");
}

static void live_end(FILE *f)
{
    live_require(fgetc(f) == EOF && !ferror(f), "noncanonical trailing receipt bytes");
    live_require(!fclose(f), "receipt close");
}

static u32 live_u32(FILE *f)
{
    unsigned char b[4];
    live_require(fread(b, 1, sizeof(b), f) == sizeof(b), "truncated wire word");
    return (u32)b[0] | (u32)b[1] << 8 | (u32)b[2] << 16 | (u32)b[3] << 24;
}

static void live_magic(FILE *f, const char *magic)
{
    char b[8];
    live_require(fread(b, 1, sizeof(b), f) == sizeof(b) && !memcmp(b, magic, sizeof(b)),
        "wrong private wire magic");
}

static void live_profile_words(const char *path,
    const struct native_kernel_profile_word expected[117], struct native_kernel_profile_word words[117])
{
    FILE *f = fopen(path, "rb");
    live_require(f != NULL, "profile open");
    live_magic(f, "OGNP0002");
    live_require(live_u32(f) == 112, "profile count");
    for (unsigned i = 0; i < 112; i++) {
        words[i].address = live_u32(f);
        words[i].value = live_u32(f);
        live_require(words[i].address == expected[i].address && words[i].value == expected[i].value,
            "sample profile identity");
    }
    live_require(live_u32(f) == 5, "auxiliary count");
    for (unsigned i = 112; i < 117; i++) {
        words[i].address = live_u32(f);
        words[i].value = live_u32(f);
        live_require(words[i].address == expected[i].address, "auxiliary order");
    }
    live_end(f);
}

static FILE *live_wait(const char *name)
{
    char path[4096], error[4096];
    live_path(path, name);
    live_path(error, "error");
    struct timespec start, now, pause = {.tv_nsec = 10000000};
    live_require(!clock_gettime(CLOCK_MONOTONIC, &start), "clock");
    unsigned timeout;
    live_require(!native_mmio_timeout_seconds(&timeout), "timeout configuration");
    for (;;) {
        FILE *f = fopen(path, "rb");
        if (f)
            return f;
        live_require(errno == ENOENT, "receipt open");
        struct stat st;
        live_require(lstat(error, &st) && errno == ENOENT, "controller error");
        live_require(!clock_gettime(CLOCK_MONOTONIC, &now) &&
            now.tv_sec - start.tv_sec < (time_t)timeout, "controller receipt timeout");
        live_require(!nanosleep(&pause, NULL) || errno == EINTR, "receipt wait");
    }
}

struct live_pte_output {
    FILE *file;
    unsigned count;
};

static int live_pte_range(void *opaque, dma_addr_t physical, const void *data, size_t bytes)
{
    struct live_pte_output *out = opaque;
    (void)data;
    return fprintf(out->file, "%s{\"first\":%llu,\"end\":%llu}", out->count++ ? "," : "",
        (unsigned long long)physical, (unsigned long long)(physical + bytes)) < 0 ? -EIO : 0;
}

static void live_maps_json(FILE *f, struct native_kernel_session *s,
    const struct opengpu_private_snapshot *owner)
{
    fputs(",\"privateSegments\":[", f);
    unsigned segments = 0;
    for (const struct snapshot_segment *segment = owner->segments; segment; segment = segment->next)
        fprintf(f, "%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,"
            "\"allocationBytes\":%llu,\"executable\":%s}", segments++ ? "," : "",
            (unsigned long long)segment->va, (unsigned long long)segment->backing.dma,
            (unsigned long long)segment->bytes, (unsigned long long)segment->backing.size,
            segment->executable ? "true" : "false");
    fputs("],\"resourceMaps\":[", f);
    unsigned maps = 0;
    for (const struct snapshot_resource *resource = owner->resources; resource; resource = resource->next)
        for (const struct snapshot_resource_map *map = resource->maps; map; map = map->next)
            fprintf(f, "%s{\"va\":%llu,\"physical\":%llu,\"bytes\":%llu,\"writable\":%s}",
                maps++ ? "," : "", (unsigned long long)(resource->reservation.va + map->offset),
                (unsigned long long)map->physical, (unsigned long long)map->bytes,
                map->writable ? "true" : "false");
    fputs("],\"pteRanges\":[", f);
    struct live_pte_output ptes = {.file = f};
    live_require(!fixture_mmu_visit_pte_pages(&s->gpu, live_pte_range, &ptes), "PTE range enumeration");
    fputc(']', f);
}

static struct native_kernel_held_receipt live_held(FILE *f)
{
    struct native_kernel_held_receipt h = {0};
    h.record_index = live_u32(f);
    h.command_id = live_u32(f);
    h.completion = live_u32(f);
    for (unsigned i = 0; i < 12; i++)
        h.gnx[i] = live_u32(f);
    h.append_read_bytes = live_u32(f);
    h.append_read_bytes |= (u64)live_u32(f) << 32;
    h.held_legacy_status = live_u32(f);
    h.held_status = live_u32(f);
    h.held_irq = live_u32(f);
    h.held_irq_asserted = live_u32(f);
    return h;
}

static void live_run_record(struct native_kernel_session *s)
{
    char path[4096], *temporary;
    live_path(path, "union.memory");
    live_require(!native_kernel_export_memory(s, path), "union export");
    live_path(path, "record-0.capture");
    live_require(!native_kernel_capture(s, 0, path, 1000000000, 37), "capture enqueue");
    live_path(path, "READY");
    FILE *f = native_mmio_file(path, &temporary);
    live_require(f != NULL, "marker exists");
    fputs("native sample live owner waiting for DUT receipts\n", f);
    live_require(!native_mmio_close(f, path, temporary), "marker publication");

    f = live_wait("record-0.held.response");
    live_magic(f, "OGNSH001");
    struct native_kernel_held_receipt held = live_held(f);
    live_end(f);
    live_path(path, "record-0.retire");
    live_require(!native_kernel_drain_held(s, 0, &held, path), "actual held drain");
    f = live_wait("record-0.retired.response");
    live_magic(f, "OGNSR001");
    struct native_kernel_observed_receipt observed = {.held = live_held(f)};
    observed.retired_status = live_u32(f);
    observed.retired_irq = live_u32(f);
    observed.retired_irq_asserted = live_u32(f);
    observed.external_drained = live_u32(f);
    live_end(f);
    live_require(!native_kernel_observe_retired(s, 0, &observed), "actual retired observation");
    f = live_wait("oracle-authorized.response");
    live_magic(f, "OGNSBA01");
    live_require(live_u32(f) == 1, "oracle authorization");
    live_end(f);
    live_require(!native_kernel_begin_cleanup(s, live_directory) &&
        !native_kernel_finish_observed(s, 0) && !native_kernel_end_cleanup(s),
        "ACK-gated owner cleanup");
    f = live_wait("final-authorized.response");
    live_magic(f, "OGNSOK01");
    live_require(live_u32(f) == 1, "final oracle authorization");
    live_end(f);
}
#endif
