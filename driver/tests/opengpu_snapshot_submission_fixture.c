/* Exercise the production admission gate; only progress/time/locks are mocks.
 * Owner retirement deliberately acquires submit_lock from the progress hook.
 * A wait while holding that lock would fail rather than fake forward progress. */
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <stdio.h>
#define OPENGPU_DRAW_WAIT_MS 10u
#define READ_ONCE(x) (x)
#define msecs_to_jiffies(x) (x)
#define time_after(a,b) ((long)((a)-(b)) > 0)
#define mutex_lock(p) do { assert(!*(p)); *(p)=1; } while (0)
#define mutex_unlock(p) do { assert(*(p)); *(p)=0; } while (0)
static unsigned long jiffies;
struct opengpu_device {
    struct { int submit_lock; bool wedged; } hw;
    struct { void *snapshot_leases; } mmu;
    unsigned ticks, retire_after, fault_after, reschedules;
};
static struct opengpu_device *active;
static void opengpu_hw_progress_tick(struct opengpu_device *g)
{
    assert(!g->hw.submit_lock);
    ++g->ticks; ++jiffies;
    if (g->ticks == g->retire_after) {
        mutex_lock(&g->hw.submit_lock);
        g->mmu.snapshot_leases = NULL;
        mutex_unlock(&g->hw.submit_lock);
    }
    if (g->ticks == g->fault_after)
        g->hw.wedged = true;
}
static void usleep_range(unsigned minimum, unsigned maximum)
{ assert(minimum==100 && maximum==200 && !active->hw.submit_lock); ++active->reschedules; }
#include "snapshot_submission_under_test.inc"
int main(void)
{
    struct opengpu_device g = {0}; active=&g;
    assert(!opengpu_hw_snapshot_submission_lock(&g));
    assert(g.hw.submit_lock && !g.ticks);
    mutex_unlock(&g.hw.submit_lock);
    /* A valid queued writer is admitted automatically after owner retirement;
     * it neither retains submit_lock nor requires application resubmission. */
    g.mmu.snapshot_leases=&g; g.retire_after=4;
    assert(!opengpu_hw_snapshot_submission_lock(&g));
    assert(g.ticks==4 && g.reschedules==4 && !g.mmu.snapshot_leases);
    assert(g.hw.submit_lock); mutex_unlock(&g.hw.submit_lock);
    /* A quarantined owner is never bypassed and has a bounded fault outcome. */
    g=(struct opengpu_device){0};jiffies=0;g.mmu.snapshot_leases=&g;
    assert(opengpu_hw_snapshot_submission_lock(&g)==-ETIMEDOUT);
    assert(g.ticks==111 && !g.hw.submit_lock && g.mmu.snapshot_leases==&g);
    g=(struct opengpu_device){0};jiffies=0;g.mmu.snapshot_leases=&g;g.fault_after=3;
    assert(opengpu_hw_snapshot_submission_lock(&g)==-EIO);
    assert(g.ticks==3 && !g.hw.submit_lock && g.mmu.snapshot_leases==&g);
    /* Fresh healthy reuse after the failed owner is explicitly recovered. */
    g=(struct opengpu_device){0};jiffies=0;
    assert(!opengpu_hw_snapshot_submission_lock(&g));
    assert(g.hw.submit_lock && !g.ticks);mutex_unlock(&g.hw.submit_lock);
    puts("snapshot admission: automatic deferred writer, unlocked owner retirement, fault/timeout/reuse PASS");
    return 0;
}
