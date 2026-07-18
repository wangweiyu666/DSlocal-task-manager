# DST1 protocol test vectors

These fixtures are shared protocol inputs for the Android Sub implementation and the future Dom web implementation.

The `0.1.0-alpha` test phase recognizes DST1 v1 but deliberately rejects repeat (`x`), reminder (`h`), and specialized execution (`u`) fields until those capabilities are implemented. Unknown fields are rejected.

Temporary points rule: when a task moves groups, existing ledger entries retain their original `groupId`; future completions use the new group. The formal requirement to transfer historical points and recalculate historical results remains deferred.
