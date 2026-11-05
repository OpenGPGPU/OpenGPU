// SPDX-License-Identifier: GPL-2.0
/* Exercise the compute-kernarg VA planner.
 * cc -std=c11 -Wall -Wextra -Werror -I. tests/opengpu_kernarg_va_test.c \
 *    -o /tmp/opengpu_kernarg_va_test
 */
#include <assert.h>
#include <stdio.h>

#include "opengpu_kernarg_va.h"
#include "opengpu_compute_counter_source.h"

#define PAGE 4096u

int main(void)
{
	struct opengpu_kernarg_va_plan plan, other;

	/* A page-aligned binding maps exactly its page at the slot window. */
	assert(opengpu_kernarg_va_plan(0x12345000, 64, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_KERNARG_VA_BASE + OPENGPU_KERNARG_VA_STRIDE);
	assert(plan.va == plan.va_page);
	assert(plan.pa_page == 0x12345000);
	assert(plan.span == PAGE);

	/* An unaligned binding keeps its in-page offset on the VA side and maps
	 * the enclosing page. */
	assert(opengpu_kernarg_va_plan(0x22222040, 128, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.pa_page == 0x22222000);
	assert(plan.va_page == OPENGPU_KERNARG_VA_BASE + OPENGPU_KERNARG_VA_STRIDE);
	assert(plan.va == plan.va_page + 0x40);
	assert(plan.span == PAGE);

	/* A binding that crosses a page boundary maps both pages. */
	assert(opengpu_kernarg_va_plan(0x33333f00, 0x200, 2, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.pa_page == 0x33333000);
	assert(plan.span == 2 * PAGE);

	/* Different slots get disjoint windows, so two contexts can share a VA. */
	assert(opengpu_kernarg_va_plan(0x1000, 64, 3, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(opengpu_kernarg_va_plan(0x9000, 64, 4, PAGE, &other) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(other.va_page == plan.va_page + OPENGPU_KERNARG_VA_STRIDE);

	/* A range larger than the window is rejected (the caller falls back). */
	assert(opengpu_kernarg_va_plan(0, OPENGPU_KERNARG_VA_STRIDE + 1, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0xf100, OPENGPU_KERNARG_VA_STRIDE, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	/* A full window with no in-page offset is the largest accepted range. */
	assert(opengpu_kernarg_va_plan(0, OPENGPU_KERNARG_VA_STRIDE, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_OK);
	assert(plan.span == OPENGPU_KERNARG_VA_STRIDE);

	/* Degenerate inputs are rejected. */
	assert(opengpu_kernarg_va_plan(0x1000, 0, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, 0, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, 1000, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, PAGE, NULL) ==
	       OPENGPU_KERNARG_VA_E_RANGE);

	/* The texture planner uses its own window, disjoint from the kernarg
	 * window, and shares the same arithmetic. */
	assert(opengpu_texture_va_plan(0x55555000, 0x3000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_TEXTURE_VA_BASE + OPENGPU_TEXTURE_VA_STRIDE);
	assert(plan.pa_page == 0x55555000);
	assert(plan.va == plan.va_page);
	assert(plan.span == 0x3000);
	assert(plan.va_page >= OPENGPU_KERNARG_VA_BASE +
	       OPENGPU_KERNARG_VA_STRIDE);
	assert(opengpu_texture_va_plan(0, OPENGPU_TEXTURE_VA_STRIDE + 1, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_texture_va_plan(0, OPENGPU_TEXTURE_VA_STRIDE, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_OK);
	/* Slot 34 is still strictly below the command window. */
	assert(opengpu_texture_va_plan(0x55555000, PAGE, 34, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == 0xa8800000ull &&
	       plan.va_page + plan.span <= OPENGPU_COMMAND_VA_BASE);

	/* Four full-size storage windows remain disjoint and below 4 GiB. */
	for (opengpu_kernarg_u32 i = 0; i < 4; ++i) {
		assert(opengpu_storage_va_plan(0x10000000, 128ull * 1024 * 1024,
		                               i, PAGE, &plan) == OPENGPU_KERNARG_VA_OK);
		assert(plan.va == OPENGPU_STORAGE_VA_BASE + i * OPENGPU_STORAGE_VA_STRIDE);
		assert(plan.va + plan.span <= (1ull << 32));
	}
	assert(opengpu_storage_va_plan(0x10000000, 64, 4, PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_storage_va_plan(0x10000000, OPENGPU_STORAGE_VA_STRIDE + 1,
	                               0, PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	/* Every legal 256-byte in-page offset keeps the full view, including its
	 * enclosing pages, inside one window. The fourth ends before states. */
	for (opengpu_kernarg_u32 i = 0; i < 4; ++i) {
		assert(opengpu_storage_va_plan(0x10000f00, 128ull * 1024 * 1024,
		                               i, PAGE, &plan) == OPENGPU_KERNARG_VA_OK);
		assert(plan.pa_page == 0x10000000 && plan.span == 128ull * 1024 * 1024 + PAGE);
		assert(plan.va == plan.va_page + 0xf00 &&
		       plan.va_page + plan.span == OPENGPU_STORAGE_VA_BASE + (i + 1) * OPENGPU_STORAGE_VA_STRIDE);
	}
	assert(plan.va_page + plan.span == OPENGPU_TESS_STATES_VA_BASE);
	assert(opengpu_storage_va_plan(0x10000000, 128ull * 1024 * 1024 + 1,
	                               0, PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_storage_va_plan(0x10000000, 64, 0, 8192, &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* Larger draw arrays share neither a 4 MiB command slot nor kernarg VA. */
	assert(opengpu_tessellation_draw_va_plan(0x50000040, 0x400040, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va == OPENGPU_TESS_DRAWS_VA_BASE + 0x40 &&
	       plan.pa_page == 0x50000000 && plan.span == 0x401000);
	assert(opengpu_tessellation_draw_va_plan(0x50000040,
	       OPENGPU_TESS_DRAWS_VA_SIZE - 64, PAGE, &plan) == OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page + plan.span == OPENGPU_KERNARG_VA_BASE);
	assert(opengpu_tessellation_draw_va_plan(0x50000040,
	       OPENGPU_TESS_DRAWS_VA_SIZE, PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_tessellation_draw_va_plan(0, ~(opengpu_kernarg_u64)0,
	       PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The state arena supports a full 128 MiB storage binding and remains
	 * disjoint from storage, even when its exclusive limit reaches 4 GiB. */
	assert(opengpu_tessellation_state_va_plan(0x50000000, 128ull * 1024 * 1024,
	       PAGE, &plan) == OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_TESS_STATES_VA_BASE &&
	       plan.va_page >= OPENGPU_STORAGE_VA_BASE + 4 * OPENGPU_STORAGE_VA_STRIDE);
	assert(opengpu_tessellation_state_va_plan(0x50000000, OPENGPU_TESS_STATES_VA_SIZE,
	       PAGE, &plan) == OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page + plan.span == (1ull << 32));
	assert(opengpu_tessellation_state_va_plan(0x50000040, OPENGPU_TESS_STATES_VA_SIZE,
	       PAGE, &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The command planner is its own window too, so a per-job command
	 * snapshot never aliases a texture or kernarg window. */
	assert(opengpu_command_va_plan(0x66666000, 0x4000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_COMMAND_VA_BASE + OPENGPU_COMMAND_VA_STRIDE);
	assert(plan.pa_page == 0x66666000);
	assert(plan.va == plan.va_page);
	assert(plan.va_page >= OPENGPU_TEXTURE_VA_BASE +
	       OPENGPU_TEXTURE_VA_STRIDE);
	assert(opengpu_command_va_plan(0, OPENGPU_COMMAND_VA_STRIDE + 1, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The render-descriptor window is disjoint from the command window. */
	assert(opengpu_render_va_plan(0x77777000, 64, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_RENDER_VA_BASE + OPENGPU_RENDER_VA_STRIDE);
	assert(plan.pa_page == 0x77777000);
	assert(plan.span == PAGE);
	assert(plan.va_page >= OPENGPU_COMMAND_VA_BASE +
	       OPENGPU_COMMAND_VA_STRIDE);
	assert(opengpu_render_va_plan(0, OPENGPU_RENDER_VA_STRIDE + 1, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The vertex-buffer window is disjoint from the render window. */
	assert(opengpu_vertex_va_plan(0x88888000, 0x600, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE);
	assert(plan.pa_page == 0x88888000);
	assert(plan.span == PAGE);
	assert(plan.va_page >= OPENGPU_RENDER_VA_BASE +
	       OPENGPU_RENDER_VA_STRIDE);

	/* The framebuffer window (colour slot then depth slot) is its own. */
	assert(opengpu_framebuffer_va_plan(0x99999000, 0x4000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page ==
	       OPENGPU_FRAMEBUFFER_VA_BASE + OPENGPU_FRAMEBUFFER_VA_STRIDE);
	assert(plan.pa_page == 0x99999000);
	assert(plan.va_page >= OPENGPU_VERTEX_VA_BASE +
	       OPENGPU_VERTEX_VA_STRIDE);
	assert(opengpu_framebuffer_va_plan(0, OPENGPU_FRAMEBUFFER_VA_STRIDE + 1,
					  1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);

	/* The code window is its own, above the framebuffer window, so snapshot
	 * code maps privately instead of running from the identity map. */
	assert(opengpu_code_va_plan(0xaaaaa000, 0x800, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_CODE_VA_BASE + OPENGPU_CODE_VA_STRIDE);
	assert(plan.pa_page == 0xaaaaa000);
	assert(plan.span == PAGE);
	assert(plan.va_page >= OPENGPU_FRAMEBUFFER_VA_BASE +
	       OPENGPU_FRAMEBUFFER_VA_STRIDE);
	assert(opengpu_code_va_plan(0, OPENGPU_CODE_VA_STRIDE + 1, 1, PAGE,
				    &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* DMA windows sit above code so fill/blit/strided targets map privately
	 * instead of relying on VA==PA through the shared identity map. */
	assert(opengpu_dma_va_plan(0xbbbbb000, 0x1000, 0, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_DMA_VA_BASE);
	assert(plan.pa_page == 0xbbbbb000);
	assert(opengpu_dma_va_plan(0xccccc000, 0x2000, 1, PAGE, &other) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(other.va_page == OPENGPU_DMA_VA_BASE + OPENGPU_DMA_VA_STRIDE);
	assert(other.va_page >= OPENGPU_CODE_VA_BASE + OPENGPU_CODE_VA_STRIDE);
	assert(opengpu_dma_va_plan(0, OPENGPU_DMA_VA_STRIDE + 1, 0, PAGE,
				   &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* An independent eight-counter view keeps complete original GEM pages and
	 * transport lines, including the last word of a line and a two-page view. */
	struct opengpu_counter_source_plan counter, saved;
	assert(!opengpu_compute_counter_source_plan(0x60000000, 8192, 60, 8, &counter));
	assert(counter.mapping.va == 0xb1c0003c && counter.mapping.span == PAGE);
	assert(counter.data_dma == 0x6000003c && counter.bytes == 32);
	assert(counter.allocation_base == 0xb1c00000 && counter.allocation_limit == 0xb1c00080);
	assert(!opengpu_compute_counter_source_plan(0x60000000, 8192, 4092, 8, &counter));
	assert(counter.mapping.va == 0xb1c00ffc && counter.mapping.span == 2 * PAGE);
	assert(counter.mapping.pa_page == 0x60000000);
	assert(counter.allocation_base == 0xb1c00fc0 && counter.allocation_limit == 0xb1c01040);
	assert(!opengpu_compute_counter_source_plan(0xffffe000, 8192, 8192 - 32, 8, &counter));
	assert(counter.data_dma == 0xffffffe0 && counter.mapping.span == PAGE);
	saved = counter;
	assert(opengpu_compute_counter_source_plan(0xfffff000, 8192, 0, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, 8192 - 4, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, ~0ull - 3, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000004, 8192, 0, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8191, 0, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, 2, 8, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, 0, 0, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, 0, 9, &counter));
	assert(opengpu_compute_counter_source_plan(0x60000000, 8192, 0, 8, NULL));
	assert(counter.data_dma == saved.data_dma && counter.mapping.va == saved.mapping.va);
	puts("kernarg VA planner tests passed");
	return 0;
}
