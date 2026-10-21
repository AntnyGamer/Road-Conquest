import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const baseline = '889ba9cd61baf64f61ffe51105ca113e9df247ba';
const root = 'app/src/main/java/com/roadconquest/app/';
const account = root + 'account/AccountClient.kt';
const config = root + 'services/ServiceConfig.kt';
const bounded = root + 'services/BoundedInput.kt';
const geoapify = root + 'matching/GeoapifyMatcher.kt';
const locationiq = root + 'matching/LocationIqMatcher.kt';
const boundaries = root + 'map/GeoBoundariesClient.kt';
const normal = value => value.replaceAll('\r\n', '\n');
const before = path => normal(execFileSync('git', ['show', `${baseline}:${path}`], {encoding:'utf8'}));
const current = path => normal(readFileSync(path, 'utf8'));
assert.equal(current(account)
  .replace('val responseText = stream?.use { input ->', 'val bytes = stream?.use { input ->')
  .replace('output.toString(Charsets.UTF_8.name())\n            } ?: ""\n            val json = runCatching { JSONObject(responseText) }',
    'output.toByteArray()\n            } ?: ByteArray(0)\n            val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }'),
  before(account));
assert.equal(current(config)
  .replace('val raw = connection.inputStream.use { input ->', 'val bytes = connection.inputStream.use { input ->')
  .replace('check(output.size() <= 4096)\n                        output.toString(Charsets.UTF_8.name())\n                    }',
    'output.toByteArray()\n                    }\n                    check(bytes.size <= 4096)\n                    val raw = bytes.toString(Charsets.UTF_8)'),
  before(config));
assert.equal(current(bounded)
  .replace('readBoundedUtf8(input: InputStream, limit: Int): String', 'readBounded(input: InputStream, limit: Int): ByteArray')
  .replace('output.toString(Charsets.UTF_8.name())', 'output.toByteArray()'), before(bounded));
const oldImport = text => text.replace('import com.roadconquest.app.services.readBoundedUtf8', 'import com.roadconquest.app.services.readBounded');
assert.equal(oldImport(current(geoapify)).replace(
  'val body = connection.inputStream.use { readBoundedUtf8(it, 4 * 1024 * 1024) }',
  'val bytes = connection.inputStream.use { readBounded(it, 4 * 1024 * 1024) }\n            val body = bytes.toString(Charsets.UTF_8)'), before(geoapify));
assert.equal(oldImport(current(locationiq)).replace(
  'readBoundedUtf8(it, 4 * 1024 * 1024) }', 'readBounded(it, 4 * 1024 * 1024) }.toString(Charsets.UTF_8)'), before(locationiq));
assert.equal(oldImport(current(boundaries)).replace(
  'val body = connection.inputStream.use { readBoundedUtf8(it, limit) }\n                        return JSONObject(body)',
  'val bytes = connection.inputStream.use { readBounded(it, limit) }\n                        return JSONObject(bytes.toString(Charsets.UTF_8))'), before(boundaries));
const changed = execFileSync('git', ['diff','--name-only',baseline,'--','app/src/main'], {encoding:'utf8'})
  .trim().split(/\r?\n/).filter(Boolean);
assert.deepEqual(changed, [account, boundaries, geoapify, locationiq, bounded, config]);
assert.equal(execFileSync('git', ['ls-files','--others','--exclude-standard','--','app/src/main'], {encoding:'utf8'}), '');
const checks = [
  {path:account, scope:'Complete request, authentication, limits, errors and endpoint behavior outside direct response decoding', identical:true},
  {path:config, scope:'Complete provider switches, freshness, retry, persistence and notifications outside direct bounded decoding', identical:true},
  {path:bounded, scope:'Complete byte read loop, buffer size, byte limit, exceptions and stream ownership outside final UTF-8 decoding', identical:true},
  ...[geoapify,locationiq,boundaries].map(path => ({path, scope:'Complete requests, parser, validation, cache, health, redirects and matching/geometry decisions outside direct response decoding', identical:true})),
  {path:'app/src/main', scope:'Every other source, asset and manifest, including GPS recording, geometry validation, town borders and prior optimizations', identical:true}
];
for (const folder of ['server/src', 'server/maps', '.github', 'app/build.gradle.kts']) {
  assert.equal(execFileSync('git', ['diff',baseline,'--',folder], {encoding:'utf8'}), '');
  checks.push({path:folder, scope:'Complete implementation/configuration', identical:true});
}
if (process.argv[2]) writeFileSync(process.argv[2], JSON.stringify({baseline,checks},null,2)+'\n');
console.log(JSON.stringify({baseline,checks:checks.length,identical:true}));
