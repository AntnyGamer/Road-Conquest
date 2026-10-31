import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createForum } from '../src/forum.mjs';

const input = () => ({ text: 'A discovery', gif_url: null, request_id: randomUUID(), rules_version: 1 });
const contentHash = createHash('sha256').update(JSON.stringify(['A discovery', null, 1])).digest('hex');
const state = { violations: 0, banned: false, muted_until: null, next_post_at: null, plus_active: false };
const options = { pepper: 'local-preflight-test-pepper' };
function gate() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
function receipt(id) { return { id, state: 'visible', content_hash: contentHash, ...state }; }

test('post preflight shares identical committed retry reads and never caches a completed or failed lookup', async () => {
  const read = gate(), body = input();
  let queries = 0;
  const failure = new Error('temporary database failure');
  const forum = createForum({ async query() {
    queries++;
    await read.promise;
    if (queries === 2) throw failure;
    return { rows: [receipt(body.request_id)] };
  }, connect() { assert.fail('A committed retry must not start a transaction'); } }, options);
  const requests = Promise.all(Array.from({ length: 12 }, () => forum.post({ id: 1 }, body)));
  try {
    assert.equal(queries, 1, 'Twelve overlapping retries need one receipt query');
    read.resolve();
    const replies = await requests;
    assert.ok(replies.every(reply => reply.id === body.request_id && !reply.removed));
    await assert.rejects(forum.post({ id: 1 }, body), error => error === failure);
    assert.equal(queries, 2, 'A completed lookup must be reread');
    assert.equal((await forum.post({ id: 1 }, body)).id, body.request_id);
    assert.equal(queries, 3, 'A failed lookup must remain retryable');
  } finally { read.resolve(); await requests.catch(() => {}); }
});

test('post preflight shares the receipt and profile reads before moderation and releases failed work', async () => {
  const read = gate(), moderationStarted = gate(), finishModeration = gate(), body = input();
  let queries = 0, moderated = 0;
  const failure = new Error('moderation unavailable');
  const forum = createForum({ async query(sql) {
    queries++;
    await read.promise;
    return { rows: sql.includes('FROM forum_posts') ? [] : [{ ...state }] };
  }, connect() { assert.fail('A failed moderation check must not write'); } }, {
    ...options, moderate: async () => {
      moderated++;
      moderationStarted.resolve();
      await finishModeration.promise;
      throw failure;
    }
  });
  const requests = Promise.allSettled(Array.from({ length: 8 }, () => forum.post({ id: 1 }, body)));
  let later;
  try {
    read.resolve();
    await moderationStarted.promise;
    assert.equal(queries, 2, 'Eight new submissions share one receipt read and one profile read');
    assert.equal(moderated, 1);
    later = Promise.allSettled([forum.post({ id: 1 }, body)]);
    assert.equal(queries, 2, 'An already moderating submission needs no more reads');
    finishModeration.resolve();
    assert.ok((await requests).every(result => result.status === 'rejected' && result.reason === failure));
    await later;
    await assert.rejects(forum.post({ id: 1 }, body), error => error === failure);
    assert.equal(queries, 4);
    assert.equal(moderated, 2);
  } finally { read.resolve(); finishModeration.resolve(); await requests; await later; }
});

test('post preflight sharing is bounded and separates accounts and changed payloads', async () => {
  const read = gate(), bodies = Array.from({ length: 34 }, input);
  let queries = 0;
  const forum = createForum({ async query(sql, args) {
    queries++;
    await read.promise;
    return { rows: [receipt(args[1])] };
  }, connect() { assert.fail('Committed retries must not write'); } }, options);
  const calls = bodies.map(body => forum.post({ id: 1 }, body));
  calls.push(forum.post({ id: 1 }, bodies[0]));
  calls.push(forum.post({ id: 1 }, bodies[33]));
  calls.push(forum.post({ id: 2 }, bodies[0]));
  calls.push(forum.post({ id: 1 }, { ...bodies[0], text: 'Different content' }));
  const results = Promise.allSettled(calls);
  try {
    assert.equal(queries, 37, 'Keep only 32 pending lookups; overflow still follows the ordinary path');
    read.resolve();
    const settled = await results;
    assert.ok(settled.slice(0, -1).every(result => result.status === 'fulfilled'));
    assert.equal(settled.at(-1).status, 'rejected');
    assert.equal(settled.at(-1).reason.status, 409, 'Changed content must not borrow another request’s receipt');
  } finally { read.resolve(); await results; }
});
