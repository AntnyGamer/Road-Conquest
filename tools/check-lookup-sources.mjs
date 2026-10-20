import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const baseline = 'd34f7c65519c588ff4ee95ef891a54d720a6d206';
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
check(app + 'util/Prefs.kt', (next, previous) => {
  for (const [start, end] of [['    fun uiTheme(', '    fun setUiTheme('],
    ['    fun mapMode(', '    fun setMapMode('], ['    fun placeOverlayMode(', '    fun setPlaceOverlayMode(']]) {
    next = next.replace(section(next, start, end), section(previous, start, end));
  }
  return next;
}, 'All preferences, consent, durable writes and setters outside three current-value enum lookups');
check(app + 'progression/Cosmetics.kt', (next, previous) =>
  next.replace(section(next, '    fun carStyle(', '    fun isEquipped('),
    section(previous, '    fun carStyle(', '    fun isEquipped(')),
'All catalog, purchase/entitlement, equip and drawing behavior outside three enum lookups');
check(app + 'data/ProgressionRepository.kt', (next, previous) => {
  next = next.replace('                val completeBeforeRetry = baseline && baselineComplete(db)\n                if (baseline && !completeBeforeRetry) {',
    '                if (baseline && !baselineComplete(db)) {');
  next = next.replace('                // A complete baseline has no intervening writes to invalidate the\n                // check. Incomplete paths still recheck after retry/marker writes.\n                if (!baseline || completeBeforeRetry || baselineComplete(db)) {',
    '                if (!baseline || baselineComplete(db)) {');
  for (const [start, end] of [['    private fun municipalityIdentity(', '    private fun insertVisitedPlace('],
    ['    private fun baselineComplete(', '    private fun markMissingBaselineKinds(']]) {
    next = next.replace(section(next, start, end), section(previous, start, end));
  }
  return next.replace(section(next, '        // CASE preserves the original kind order', '        // 150 m real-fix cells'), '');
}, 'All history locks, transactions, candidates, rewards, aliases, geometry gates and retry writes outside the three stated changes');
for (const path of [app + 'TrackingService.kt', app + 'data/TrackingRepository.kt', app + 'data/AppDatabase.kt',
  app + 'data/TravelDistance.kt', app + 'matching/KnownRoadReuse.kt', app + 'map/MapRenderer.kt',
  app + 'progression/PlaceResolver.kt', app + 'map/PlaceOverlay.kt', app + 'map/DistinctGeoJson.kt',
  app + 'map/FogGrid.kt', app + 'achievements/Achievements.kt']) {
  check(path, next => next, 'Complete file');
}
for (const path of ['server/src', 'server/maps']) {
  assert.equal(execFileSync('git', ['diff', baseline, '--', path], {encoding: 'utf8'}), '');
  checks.push({path, scope: 'Complete server implementation/configuration', identical: true});
}
const result = {baseline, checks};
if (process.argv[2]) writeFileSync(process.argv[2], JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({baseline, checks: checks.length, identical: true}));
