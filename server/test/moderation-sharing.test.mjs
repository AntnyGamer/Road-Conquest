import test from 'node:test';
import assert from 'node:assert/strict';
import { GifWriter } from 'omggif';
import { createForumModerator } from '../src/forum-moderation.mjs';

const categories = Object.fromEntries(['harassment', 'harassment/threatening', 'hate', 'hate/threatening',
  'sexual', 'sexual/minors', 'violence/graphic', 'illicit/violent', 'self-harm/instructions'].map(key => [key, false]));
const verdict = () => Response.json({ model: 'fixture', results: [{ flagged: false, categories }] });

test('identical overlapping moderation shares one GIF download and one verdict, with no completed cache', async () => {
  const bytes = Buffer.alloc(1024);
  const writer = new GifWriter(bytes, 1, 1, { palette: [0, 0xffffff] });
  writer.addFrame(0, 0, 1, 1, new Uint8Array([1]));
  const gif = bytes.subarray(0, writer.end());
  let downloads = 0, checks = 0, release, started;
  const gate = new Promise(resolve => { release = resolve; });
  const checking = new Promise(resolve => { started = resolve; });
  const moderate = createForumModerator('fixture', async url => {
    if (url.startsWith('https://media.giphy.com/')) {
      downloads++;
      return new Response(gif, { headers: { 'Content-Type': 'image/gif' } });
    }
    checks++;
    started();
    await gate;
    return verdict();
  });
  const input = { text: 'Same exact content', gifUrl: 'https://media.giphy.com/shared.gif' };
  const pending = Array.from({ length: 100 }, () => moderate(input));
  await checking;
  assert.equal(downloads, 1);
  assert.equal(checks, 1);
  release();
  const results = await Promise.all(pending);
  results.forEach(result => assert.deepEqual(result, results[0]));
  assert.match(results[0].gifHash, /^[a-f0-9]{64}$/);
  await moderate(input);
  assert.equal(downloads, 2, 'Later checks re-download mutable GIF URLs');
  assert.equal(checks, 2, 'Later posts receive a fresh moderation verdict');
});

test('moderation sharing isolates content and instances, stays bounded, and recovers after failure', async () => {
  let calls = 0, release;
  const gate = new Promise(resolve => { release = resolve; });
  const request = async () => { calls++; await gate; return verdict(); };
  const moderate = createForumModerator('fixture', request);
  const jobs = Array.from({ length: 40 }, (_, index) => moderate({ text: `Post ${index}` }));
  jobs.push(moderate({ text: 'Post 0' }));
  jobs.push(moderate({ text: 'Post 39' }));
  jobs.push(createForumModerator('other-instance', request)({ text: 'Post 0' }));
  assert.equal(calls, 42, 'Retained duplicates share, overflow and other instances continue normally');
  release();
  await Promise.all(jobs);
  let attempts = 0;
  const recover = createForumModerator('fixture', async () => {
    if (++attempts === 1) throw Error('temporary outage');
    return verdict();
  });
  const failed = await Promise.allSettled([recover({ text: 'Retry' }), recover({ text: 'Retry' })]);
  assert.ok(failed.every(result => result.status === 'rejected'));
  assert.equal(attempts, 1);
  assert.equal((await recover({ text: 'Retry' })).flagged, false);
  assert.equal(attempts, 2);
});
