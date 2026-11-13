#!/usr/bin/env python3
"""Actual full-shared Mesa producer and kernel-publication capture."""


def write_sources(out):
    from test_gallium_compute_shared import write_sources as shared_sources
    from test_gallium_image_ordering import write_sources as consumer_sources
    shared_sources(out)
    # Consumer layout matches the original image ordering contract, with the
    # actual first result at texel zero and a mutable repeated coordinate.
    consumer_sources(out)
    for kind in ("image", "texture"):
        p = out / (kind + "-consumer.comp")
        p.write_text(p.read_text().replace("(image0,1)", "(image0,0)").replace("(texture0,1)", "(texture0,0)"))
    # Reuse all 16 KiB / 128 threads; append a converged output barrier and flag.
    for epoch, name in ((1, "full"), (2, "epoch-b")):
        source = (out / (name + ".comp")).read_text()
        (out / ("producer-%d.comp" % epoch)).write_text(source.replace(
            "result.v[group*128u+id]=sum;}", "result.v[group*128u+id]=sum;barrier();"
            "memoryBarrierBuffer();result.v[128u+id]=%du;}" % epoch))


if __name__ == "__main__":
    from test_gallium_compute_uniforms import main
    main(buffer_resources=True, shared_publication=True)
