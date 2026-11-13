#include "../opengpu_native_completion.h"
#include <assert.h>
#include <string.h>

_Static_assert(sizeof(struct opengpu_native_completion) == 56, "copied twelve words and telemetry");
struct window {
    opengpu_native_host_word words[12];
    unsigned reads;
    int popped;
};
static opengpu_native_host_word read_window(void *opaque, opengpu_native_host_word address)
{
    struct window *window = opaque;
    assert(!window->popped && window->reads < 12);
    assert(address == 0x180u + window->reads * 4u);
    return window->words[window->reads++];
}
static int decode(const opengpu_native_host_word *words, unsigned count,
    unsigned status, unsigned long long bytes, struct opengpu_native_completion *out)
{
    return opengpu_native_completion_decode(words, count, status, bytes, 11, 12, 13, out);
}
static void rejected(const opengpu_native_host_word *words, unsigned count,
    unsigned status, unsigned long long bytes)
{
    struct opengpu_native_completion out, before;
    memset(&out, 0xa5, sizeof out); before = out;
    assert(!decode(words, count, status, bytes, &out));
    assert(!memcmp(&out, &before, sizeof out));
}
int main(void)
{
    /* A valid success may retain raw reader address/draw; neither means that a
     * fault occurred. Empty and preflight-scanned jobs have different counts. */
    const opengpu_native_host_word success[12] = {
        0x474e5801u, 0, 0x12345640u, 0x87654321u, 0, 0, 11, 12, 13, 0, 0, 0
    };
    opengpu_native_host_word words[12];
    struct opengpu_native_completion out;
    struct window window;
    unsigned bit, i;
    const unsigned long long counts[] = {0, 64, 2048, 4096, 1ULL << 32, 1ULL << 33};
    for (i = 0; i < sizeof counts / sizeof counts[0]; ++i) {
        assert(decode(success, 12, 0, counts[i], &out));
        assert(!memcmp(out.info.words, success, sizeof success));
        assert(out.append_read_bytes == counts[i]);
    }
    rejected(success, 11, 0, 0); rejected(success, 13, 0, 0);
    rejected(0, 12, 0, 0); rejected(success, 12, 8, 0);
    assert(!decode(success, 12, 0, 0, 0));
    for (i = 1; i < 64; ++i) rejected(success, 12, 0, i);
    rejected(success, 12, 0, (1ULL << 33) + 64);
    rejected(success, 12, 0, ~63ULL);
    for (bit = 0; bit < 32; ++bit) {
        memcpy(words, success, sizeof words); words[0] ^= 1u << bit;
        rejected(words, 12, 0, 64);
        memcpy(words, success, sizeof words); words[11] = 1u << bit;
        rejected(words, 12, 0, 64);
    }
    for (i = 6; i <= 8; ++i) for (bit = 0; bit < 32; ++bit) {
        memcpy(words, success, sizeof words); words[i] ^= 1u << bit;
        rejected(words, 12, 0, 64);
    }
    for (bit = 3; bit < 32; ++bit) {
        memcpy(words, success, sizeof words); words[1] = 1u << bit;
        rejected(words, 12, 0, 64);
    }
    for (bit = 0; bit < 32; ++bit) {
        memcpy(words, success, sizeof words); words[5] = 1u << bit;
        rejected(words, 12, 0, 64);
    }
    /* A real early GNJ fetch fault has no decoded identity. A later failure
     * may retain all nested stages; validity is separate from job success. */
    memcpy(words, success, sizeof words); words[1] = 3u | (2u << 3);
    words[6] = words[7] = words[8] = 0;
    assert(decode(words, 12, 3, 0, &out));
    assert(out.info.words[2] == 0x12345640u && out.info.words[6] == 0);
    rejected(words, 12, 4, 0);
    for (bit = 14; bit < 32; ++bit) {
        words[1] |= 1u << bit; rejected(words, 12, 3, 64); words[1] &= ~(1u << bit);
    }
    for (bit = 27; bit < 32; ++bit) {
        words[5] = 1u << bit; rejected(words, 12, 3, 64); words[5] = 0;
    }
    words[4] = 1; rejected(words, 12, 3, 64); words[4] = 0;
    words[9] = 1; rejected(words, 12, 3, 64); words[9] = 0;
    words[10] = 1; rejected(words, 12, 3, 64); words[10] = 0;
    for (bit = 15; bit < 27; ++bit) {
        words[5] = 1u << bit; rejected(words, 12, 3, 64); words[5] = 0;
    }
    for (bit = 12; bit <= 13; ++bit) {
        words[1] |= 1u << bit; rejected(words, 12, 3, 64); words[1] &= ~(1u << bit);
    }
    words[1] = 0x3fffu; words[4] = 0xabcdef01u; words[5] = 0x07ffffffu;
    words[9] = 0x87654321u; words[10] = 0x12345678u;
    assert(decode(words, 12, 7, 4096, &out));
    assert(out.info.words[4] == words[4] && out.info.words[9] == words[9] && out.info.words[10] == words[10]);
    /* Cancellation has no requirement to invent a fault address or a tile. */
    memcpy(words, success, sizeof words); words[1] = 4;
    words[6] = words[7] = words[8] = 0;
    assert(decode(words, 12, 4, 64, &out));

    memset(&window, 0, sizeof window); memcpy(window.words, success, sizeof success);
    {
        struct opengpu_native_completion sentinel;
        memset(&out, 0xa5, sizeof out); sentinel = out;
#define CAPTURE(status, bytes, aperture, callback, target) \
        opengpu_native_completion_capture(status, bytes, aperture, 11, 12, 13, &window, callback, target)
        assert(!CAPTURE(0, 0, 0x1af, read_window, &out));
        assert(!CAPTURE(8, 0, 0x1b0, read_window, &out));
        assert(!CAPTURE(0, 1, 0x1b0, read_window, &out));
        assert(!CAPTURE(0, (1ULL << 33) + 64, 0x1b0, read_window, &out));
        assert(!CAPTURE(0, 0, 0x1b0, 0, &out));
        assert(!CAPTURE(0, 0, 0x1b0, read_window, 0));
        assert(window.reads == 0 && !memcmp(&out, &sentinel, sizeof out));
        window.words[0] = 0;
        assert(!CAPTURE(0, 0, 0x1b0, read_window, &out));
        assert(window.reads == 1 && !memcmp(&out, &sentinel, sizeof out));
        window.reads = 0; window.words[0] = success[0]; window.words[1] = 3 | (2u << 3);
        assert(!CAPTURE(0, 64, 0x1b0, read_window, &out));
        assert(window.reads == 12 && !memcmp(&out, &sentinel, sizeof out));
        window.reads = 0; window.words[6] = window.words[7] = window.words[8] = 0;
        assert(CAPTURE(3, 64, 0x1b0, read_window, &out));
        assert(window.reads == 12 && out.info.words[1] == (3 | (2u << 3)));
        window.reads = 0; memcpy(window.words, success, sizeof success);
        assert(CAPTURE(0, 4096, 0x1b0, read_window, &out));
        assert(window.reads == 12 && !window.popped);
        window.popped = 1; memset(window.words, 0, sizeof window.words);
        assert(!memcmp(out.info.words, success, sizeof success) && out.append_read_bytes == 4096);
#undef CAPTURE
    }
    return 0;
}
