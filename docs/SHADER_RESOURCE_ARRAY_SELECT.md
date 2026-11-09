# Dynamic shader resource-array selection

`ShaderResourceArraySelectUnit` validates one dynamically selected entry of a
shader resource array and publishes its logical slot:

- the 32-bit index must be below the array length, the 33-bit `base + index`
  sum must stay below the stage descriptor limit, and the entry must be marked
  bound in a sixteen-entry bitmap;
- sampler arrays require a dynamically uniform index; uniform blocks, images
  and storage buffers may use divergent indices;
- zero lengths, lengths above sixteen, out-of-range indices and unbound
  entries are rejected without publishing a slot;
- the result distinguishes out-of-range, unbound and divergent-sampler faults.

300 random cases plus directed boundary cases cover the selector
(`RESOURCE_ARRAY_SELECT`). Compiler indexing rules, driver resource-table
composition and shader execution of the selected slot remain outside this
hardware validator.
