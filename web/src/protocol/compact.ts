import type { Dst11Exception, Dst1Batch, Dst1Execution, Dst1Group, Dst1Recurrence, Dst1Step, Dst1Task } from "./types";

function compactStep(step: Dst1Step): Dst1Step {
  const result: Dst1Step = { n: step.n.normalize("NFC"), r: step.r };
  if (step.i !== undefined) result.i = step.i;
  if (step.u !== undefined) result.u = { ...step.u };
  return result;
}

function compactRecurrence(value: Dst1Recurrence): Dst1Recurrence {
  const result: Dst1Recurrence = { f: value.f };
  if (value.s !== undefined) result.s = value.s;
  if (value.e !== undefined) result.e = value.e;
  if (value.c !== undefined) result.c = value.c;
  if (value.w?.length) result.w = [...value.w].sort((a, b) => a - b);
  if (value.t !== undefined) result.t = value.t;
  return result;
}

function compactExecution(value: Dst1Execution): Dst1Execution {
  if (value.k === 1) return { k: 1, a: value.a, v: value.v };
  if (value.k === 2) return { k: 2, v: value.v };
  if (value.k === 4) return { k: 4 };
  if (value.k === 5) return { k: 5 };
  return { k: 3 };
}

export function compactTask(task: Dst1Task): Dst1Task {
  const result: Dst1Task = { i: task.i, n: task.n.normalize("NFC"), r: task.r };
  if (task.d !== undefined) result.d = task.d.normalize("NFC");
  if (task.y !== undefined) result.y = task.y;
  if (task.l !== undefined) result.l = task.l;
  if (task.p !== undefined) result.p = task.p;
  if (task.o !== undefined) result.o = task.o;
  if (task.s?.length) result.s = task.s.map(compactStep);
  if (task.x) result.x = compactRecurrence(task.x);
  if (task.m !== undefined) result.m = task.m.normalize("NFC");
  if (task.h?.length) result.h = [...task.h].sort((a, b) => b - a);
  if (task.u) result.u = compactExecution(task.u);
  return result;
}

function compactGroup(group: Dst1Group): Dst1Group {
  const result: Dst1Group = { i: group.i };
  if (group.n !== undefined) result.n = group.n.normalize("NFC");
  if (group.cm !== undefined) result.cm = group.cm.normalize("NFC");
  if (group.im !== undefined) result.im = group.im.normalize("NFC");
  if (group.t?.length) result.t = group.t.map(compactTask);
  return result;
}

export function compactException(value: Dst11Exception): Dst11Exception {
  const result: Dst11Exception = { i: value.i, y: value.y };
  if (value.c === 1) result.c = 1;
  if (value.n !== undefined) result.n = value.n.normalize("NFC");
  if (value.r !== undefined) result.r = value.r;
  if (value.d !== undefined) result.d = value.d.normalize("NFC");
  if (value.l !== undefined) result.l = value.l;
  if (value.p !== undefined) result.p = value.p;
  if (value.o !== undefined) result.o = value.o;
  if (value.s !== undefined) result.s = value.s.map(compactStep);
  if (value.m !== undefined) result.m = value.m?.normalize("NFC") ?? null;
  if (value.h !== undefined) result.h = [...value.h].sort((a, b) => b - a);
  if (value.u !== undefined) result.u = value.u ? compactExecution(value.u) : null;
  return result;
}

export function canonicalizeBatch(batch: Dst1Batch): Dst1Batch {
  const result: Dst1Batch = { v: 1, b: batch.b };
  if (batch.e?.length) result.sv = 1;
  if (batch.d !== undefined) result.d = batch.d.normalize("NFC");
  if (batch.m !== undefined && batch.m !== "") result.m = batch.m.normalize("NFC");
  if (batch.g?.length) result.g = batch.g.map(compactGroup);
  if (batch.t?.length) result.t = batch.t.map(compactTask);
  if (batch.z?.length) result.z = [...batch.z];
  if (batch.e?.length) result.e = batch.e.map(compactException);
  return result;
}
