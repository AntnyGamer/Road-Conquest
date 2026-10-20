import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const baseline = '93ff4e694b1c07883f2caf4795a83f9f2dad8397';
const normal = value => value.replaceAll('\r\n', '\n');
const old = path => normal(execFileSync('git', ['show', `${baseline}:${path}`], {encoding: 'utf8'}));
const current = path => normal(readFileSync(path, 'utf8'));
const checks = [];
const app = 'app/src/main/java/com/roadconquest/app/';
function section(value, start, end) {
  const a = value.indexOf(start), b = value.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, start);
  return value.slice(a, b);
}
function check(path, restore, scope) {
  assert.equal(restore(current(path), old(path)), old(path), path);
  checks.push({path, scope, identical: true});
}
check(app + 'account/AccountStore.kt', next => next
  .replace('            val cipher = Cipher.getInstance("AES/GCM/NoPadding")',
    '            val iv = bytes.copyOfRange(0, 12)\n            val ciphertext = bytes.copyOfRange(12, bytes.size)\n            val cipher = Cipher.getInstance("AES/GCM/NoPadding")')
  .replace('GCMParameterSpec(128, bytes, 0, 12)', 'GCMParameterSpec(128, iv)')
  .replace('cipher.doFinal(bytes, 12, bytes.size - 12)', 'cipher.doFinal(ciphertext)'),
'Entire session storage except replacing two explicit input slices with documented range APIs; save, key, authentication, errors and consent unchanged');
check(app + 'data/ProgressionRepository.kt', (next, previous) =>
  next.replace(section(next, '    private fun currentBalance(', '    private fun awardOnce('),
    section(previous, '    private fun currentBalance(', '    private fun awardOnce(')),
'Entire progression implementation outside the two-total balance cursor; purchases/locks, arithmetic, rewards, baseline and municipal matching retained');
check(app + 'data/TripHistoryRepository.kt', (next, previous) => next
  .replace('            val pointLine = StringBuilder(160)\n', '')
  .replace(section(next, '                    pointLine.setLength(0)', '                    previousTimestamp = timestamp'),
    section(previous, '                    writer.write("<trkpt', '                    previousTimestamp = timestamp')),
'Entire trip implementation except local GPX point formatting; exact SQL/privacy, entitlement, validation, breaks, history checks, buffering, flush and replay retained');
for (const path of [app + 'TrackingService.kt', app + 'data/TrackingRepository.kt', app + 'data/AppDatabase.kt',
  app + 'data/TravelDistance.kt', app + 'matching/KnownRoadReuse.kt', app + 'map/MapRenderer.kt',
  app + 'progression/PlaceResolver.kt', app + 'map/PlaceOverlay.kt', app + 'util/Prefs.kt',
  app + 'progression/Cosmetics.kt', app + 'data/WeeklyChallenges.kt', app + 'account/VerifiedDriving.kt']) {
  check(path, next => next, 'Complete file');
}
for (const path of ['server/src', 'server/maps']) {
  assert.equal(execFileSync('git', ['diff', baseline, '--', path], {encoding: 'utf8'}), '');
  checks.push({path, scope: 'Complete server implementation/configuration', identical: true});
}
if (process.argv[2]) writeFileSync(process.argv[2], JSON.stringify({baseline, checks}, null, 2) + '\n');
console.log(JSON.stringify({baseline, checks: checks.length, identical: true}));
