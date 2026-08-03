---
id: GE-20260803-b7f4a2
domain: tools
type: gotcha
tags: yaml, npm, CST, parseDocument, addIn, setIn
---

## yaml npm `doc.addIn()` fails with "Expected YAML collection" after `doc.setIn(path, [])`

### Symptom

`doc.addIn(['spec', 'goals'], newElement)` throws `Error: Expected YAML collection at goals. Remaining path:` immediately after creating the array with `doc.setIn(['spec', 'goals'], [])`.

### Context

Building a CST-preserving YAML editor that needs to append elements to arrays that may not exist yet. The natural pattern is: check if the array exists, create it if missing with `setIn(path, [])`, then append with `addIn(path, element)`.

### Root cause

`doc.setIn(path, [])` creates the array path, but the resulting YAML node is stored in a form that `addIn` does not recognize as a YAML collection (Seq). The `addIn` method expects to find an existing `YAMLSeq` node at the path, but after `setIn([], [])` the internal representation may not satisfy this check.

### Fix

When the array does not exist, use `setIn` with the first element already inside the array — skip `addIn` entirely:

```typescript
const seq = doc.getIn(specPath);
if (!seq) {
  doc.setIn(specPath, [newElement]);
} else {
  doc.addIn(specPath, newElement);
}
```

### Why this is non-obvious

The two-step pattern (`setIn` to create, `addIn` to append) is the natural approach. The `yaml` npm API docs describe both methods without documenting this interaction. The error message "Expected YAML collection" points at the path, not at the method ordering — developers check the path spelling before suspecting the creation method.

**See also:** [GE-20260803-a1674d](GE-20260803-a1674d.md) — yaml npm `deleteIn()` silently succeeds on non-existent paths
