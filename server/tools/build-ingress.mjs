import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { sourceRevision } from './source-revision.mjs';

await build({
  absWorkingDir: fileURLToPath(new URL('../', import.meta.url)),
  entryPoints: ['worker/ingress.mjs'],
  outfile: 'dist/ingress.mjs',
  bundle: true,
  platform: 'browser',
  target: 'es2022',
  format: 'esm',
  define: { __ROADCONQUEST_INGRESS_REVISION__: JSON.stringify(sourceRevision()) }
});
