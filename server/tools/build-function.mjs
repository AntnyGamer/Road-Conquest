import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { sourceRevision } from './source-revision.mjs';

const root = fileURLToPath(new URL("../", import.meta.url));
const revision = sourceRevision();
await build({
  absWorkingDir: root,
  entryPoints: ["src/function.mjs"],
  outfile: "dist/index.mjs",
  bundle: true,
  platform: "node",
  target: "node24",
  format: "esm",
  define: { __ROADCONQUEST_SERVER_REVISION__: JSON.stringify(revision) },
  external: ["pg-native"],
  // pg has CommonJS dependencies. Neon requires these helpers in an ESM bundle.
  banner: { js: "import{createRequire as ___cr}from'node:module';import{fileURLToPath as ___f}from'node:url';import{dirname as ___d}from'node:path';const require=___cr(import.meta.url);const __filename=___f(import.meta.url);const __dirname=___d(__filename);" }
});
