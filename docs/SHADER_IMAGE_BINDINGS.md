# Shader image binding and address resolution

`ShaderImageBindingAddress` captures one immutable image-unit snapshot and
resolves its typed texel address. It supports four or more image units and the
thirteen GLES 3.1 image formats. Array, cube and volume bindings use either a
layered shader coordinate or a selected layer exposed as a 2D image. Coordinates
and selected layers are checked at full width before narrowing.

The parent must obtain the snapshot from checked GPU descriptors and retain its
translation and coherence ownership through the eventual memory result. This
component does not fetch descriptors or execute shader memory instructions.

Identity, stage, read/write permissions, shader type and format qualifiers are
checked before publishing an address. Storage/image compatibility follows the
size or class policy. Unbound, incomplete and incompatible images, invalid
levels and nonexistent texels produce zero/no-op results. Malformed views and
backing spans produce errors. Cancellation drains the address calculation;
primary bounds errors survive cancellation and held results remain immutable.

Four tests pass without failures or skips: 32 target/format operations, 28
invalid zero/no-op operations, 18 rejected bindings, cancellation, held
snapshots/results and healthy reuse. Reproduce with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.ShaderImageBindingAddressSpec'
```

The binding-to-memory composition is separately checked by `ShaderImageAccess`
and documented in [Shader image memory transfers](SHADER_IMAGE_MEMORY.md).
Checked GPU descriptor fetch, actual compute/graphics execution, atomics and
cross-domain ordering remain required for H7 closure.
