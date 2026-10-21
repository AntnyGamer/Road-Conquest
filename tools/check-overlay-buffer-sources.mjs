import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const baseline = 'e1a718cfd4d3e1248773daf39cca54b524eb7c3e';
const path = 'app/src/main/java/com/roadconquest/app/map/FogBitmapRenderer.kt';
const normal = value => value.replaceAll('\r\n', '\n');
const previous = normal(execFileSync('git', ['show', `${baseline}:${path}`], {encoding:'utf8'}));
const current = normal(readFileSync(path,'utf8'));
const result = current.replace(
  '            return OverlayRoads(\n                output.finish(),\n                if (chainCount + 1 == starts.size) starts else starts.copyOf(chainCount + 1)\n            )',
  '            return OverlayRoads(output.toArray(), starts.copyOf(chainCount + 1))'
).replace(
  '\n            // Only use when this buffer is finished: the caller may own its\n            // backing array. Reused split-road buffers must keep using toArray().\n            fun finish() = if (size == values.size) values else values.copyOf(size)\n', ''
);
assert.equal(result,previous,'Only the final ownership/trim decisions may change');
const changed = execFileSync('git',['diff','--name-only',baseline,'--','app/src/main'],{encoding:'utf8'}).trim().split(/\r?\n/).filter(Boolean);
assert.deepEqual(changed,[path]);
assert.equal(execFileSync('git',['ls-files','--others','--exclude-standard','--','app/src/main'],{encoding:'utf8'}),'');
const checks = [
  {path,scope:'Entire renderer, graph, parsing, time/distance/visit gates, coordinate math and split-buffer copies outside the two final array ownership decisions',identical:true},
  {path:'app/src/main',scope:'Every other source file, asset and manifest',identical:true}
];
for(const folder of ['server/src','server/maps','.github','app/build.gradle.kts']){
  assert.equal(execFileSync('git',['diff',baseline,'--',folder],{encoding:'utf8'}),'');
  checks.push({path:folder,scope:'Complete implementation/configuration',identical:true});
}
if(process.argv[2])writeFileSync(process.argv[2],JSON.stringify({baseline,checks},null,2)+'\n');
console.log(JSON.stringify({baseline,checks:checks.length,identical:true}));
