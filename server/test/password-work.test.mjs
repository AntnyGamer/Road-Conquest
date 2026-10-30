import test from 'node:test';
import assert from 'node:assert/strict';
import { createPasswordWork } from '../src/password-work.mjs';

function gate() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

test('password work bounds active memory and its FIFO queue, including arriving during a handoff', async () => {
  const gates = Array.from({ length: 11 }, gate);
  const started = [];
  const derive = createPasswordWork(index => { started.push(index); return gates[index].promise; });
  const work = Array.from({ length: 10 }, (_, index) => derive(index));
  const settled = Promise.allSettled(work);
  let later;
  try {
    assert.deepEqual(started, [0, 1], 'Only two memory-heavy derivations start');
    await assert.rejects(derive(10), error => error.status === 503);
    // This callback runs after the active derive's continuation releases its
    // slot, but before the released waiter gets its continuation. It must wait.
    later = gates[0].promise.then(() => derive(10));
    gates[0].resolve('first');
    await work[0];
    assert.deepEqual(started, [0, 1, 2], 'A late arrival cannot steal the transferred slot');
    for (let index = 1; index < 10; index++) {
      gates[index].resolve(index);
      await work[index];
    }
    assert.deepEqual(started, Array.from({ length: 11 }, (_, index) => index));
    gates[10].resolve('last');
    assert.equal(await later, 'last');
    assert.ok((await settled).every(result => result.status === 'fulfilled'));
  } finally {
    gates.forEach(item => item.resolve());
    await settled;
    await later?.catch(() => {});
  }
});

test('failed password work releases capacity and preserves rejection/result identity', async () => {
  const first = gate(), second = gate();
  const synchronous = new Error('synchronous derivation failure');
  const asynchronous = new Error('asynchronous derivation failure');
  const result = Buffer.alloc(64, 7);
  const started = [];
  const derive = createPasswordWork(index => {
    started.push(index);
    if (index === 0) return first.promise;
    if (index === 1) return second.promise;
    if (index === 2) throw synchronous;
    return result;
  });
  const work = [derive(0), derive(1), derive(2), derive(3)];
  const settled = Promise.allSettled(work);
  first.reject(asynchronous);
  second.resolve(result);
  const replies = await settled;
  assert.deepEqual(started, [0, 1, 2, 3]);
  assert.equal(replies[0].reason, asynchronous);
  assert.equal(replies[1].value, result);
  assert.equal(replies[2].reason, synchronous);
  assert.equal(replies[3].value, result);
  assert.equal(await derive(4), result, 'Later work recovers all capacity');
});
