import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { realpathSync } from "node:fs";

const root = fileURLToPath(new URL("../", import.meta.url));
let revision = process.env.ROADCONQUEST_SERVER_REVISION || null;
try {
  const [checkout, head] = execFileSync('git', ['rev-parse', '--show-toplevel', 'HEAD'],
    { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim().split(/\r?\n/);
  // Exported source may sit inside an unrelated repository. Only this checkout
  // can identify the bundle's source; otherwise keep the explicit value or null.
  if (realpathSync(checkout) === realpathSync(new URL('../../', import.meta.url))) revision = head;
} catch { /* Exported source archives can supply the revision explicitly. */ }
if (revision !== null && !/^[a-f0-9]{40}$/i.test(revision)) throw new Error('Invalid server source revision');
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
