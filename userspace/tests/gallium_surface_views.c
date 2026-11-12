/* Actual Gallium attachment-view callbacks with a mocked resource. */
#include <assert.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

static unsigned destroyed;

static void destroy_resource(struct pipe_screen *screen, struct pipe_resource *resource)
{
    (void)screen;
    ++destroyed;
    free(resource);
}

int main(void)
{
    struct pipe_screen screen = {.resource_destroy = destroy_resource}, other = {0};
    struct opengpu_context ctx = {0};
    struct opengpu_resource *resource = calloc(1, sizeof(*resource));
    struct pipe_resource *owner;
    struct pipe_surface templ = {0}, *surface;
    const enum pipe_format formats[] = {
        PIPE_FORMAT_R8G8B8A8_UNORM, PIPE_FORMAT_B8G8R8A8_UNORM,
        PIPE_FORMAT_R8G8B8X8_UNORM, PIPE_FORMAT_B8G8R8X8_UNORM,
        PIPE_FORMAT_Z24_UNORM_S8_UINT, PIPE_FORMAT_Z24X8_UNORM,
    };
    const enum pipe_texture_target targets[] = {PIPE_TEXTURE_2D, PIPE_TEXTURE_RECT};

    assert(resource);
    ctx.base.screen = &screen;
    ctx.base.surface_destroy = surface_destroy;
    resource->base.screen = &screen;
    resource->base.reference.count = 1;
    resource->base.target = PIPE_TEXTURE_2D;
    resource->base.width0 = 17;
    resource->base.height0 = 9;
    resource->base.depth0 = resource->base.array_size = 1;
    resource->base.format = templ.format = PIPE_FORMAT_R8G8B8A8_UNORM;

    assert(!create_surface(NULL, &resource->base, &templ));
    assert(!create_surface(&ctx.base, NULL, &templ));
    assert(!create_surface(&ctx.base, &resource->base, NULL));
    /* No attachment may silently address a different level or layer. */
    templ.u.tex.level = 1;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.u.tex.level = 0; templ.u.tex.first_layer = 1;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.u.tex.first_layer = 0; templ.u.tex.last_layer = 1;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.u.tex.last_layer = 0; templ.nr_samples = 2;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.nr_samples = 0;

    /* The renderer selects the backing format, not a separate view format. */
    templ.format = PIPE_FORMAT_B8G8R8A8_UNORM;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.format = resource->base.format = PIPE_FORMAT_R32_FLOAT;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    templ.format = resource->base.format = PIPE_FORMAT_R8G8B8A8_UNORM;
    resource->base.screen = &other;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.screen = &screen; resource->base.target = PIPE_BUFFER;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.target = PIPE_TEXTURE_2D; resource->base.width0 = 65536;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.width0 = 65535;
    surface = create_surface(&ctx.base, &resource->base, &templ);
    assert(surface && surface->width == 65535);
    pipe_surface_reference(&surface, NULL);
    resource->base.width0 = 17; resource->base.height0 = 0;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.height0 = 65535;
    surface = create_surface(&ctx.base, &resource->base, &templ);
    assert(surface && surface->height == 65535);
    pipe_surface_reference(&surface, NULL);
    resource->base.height0 = 9;
    resource->base.depth0 = 2;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.depth0 = 1; resource->base.array_size = 2;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.array_size = 1; resource->base.last_level = 1;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.last_level = 0; resource->base.nr_samples = 2;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.nr_samples = 0; resource->base.nr_storage_samples = 2;
    assert(!create_surface(&ctx.base, &resource->base, &templ));
    resource->base.nr_storage_samples = 0;
    assert(resource->base.reference.count == 1 && destroyed == 0);

    for (unsigned target = 0; target < sizeof(targets)/sizeof(targets[0]); ++target) {
        resource->base.target = targets[target];
        for (unsigned i = 0; i < sizeof(formats)/sizeof(formats[0]); ++i) {
            resource->base.format = templ.format = formats[i];
            surface = create_surface(&ctx.base, &resource->base, &templ);
            assert(surface && surface->texture == &resource->base);
            assert(surface->context == &ctx.base && surface->format == formats[i]);
            assert(surface->width == 17 && surface->height == 9);
            assert(resource->base.reference.count == 2);
            pipe_surface_reference(&surface, NULL);
            assert(resource->base.reference.count == 1 && destroyed == 0);
        }
    }

    templ.nr_samples = 1;
    surface = create_surface(&ctx.base, &resource->base, &templ);
    assert(surface);
    owner = &resource->base;
    pipe_resource_reference(&owner, NULL);
    assert(!owner && !destroyed);
    pipe_surface_reference(&surface, NULL);
    assert(!surface && destroyed == 1);
    puts("Gallium attachment views: PASS");
    return 0;
}
