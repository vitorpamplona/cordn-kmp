// Lets Node load cordn-web's TypeScript modules as they are, with no bundler.
//
// cordn-web (MIT, github.com/Cordn-msg/cordn-web) is a SvelteKit app: its modules import
// `$lib/...` without file extensions and pull a few browser-only helpers through
// `$lib/utils`. This resolve hook maps `$lib/` onto the checkout's `src/lib/`, adds the `.ts`
// extension, resolves third-party packages from this generator's own node_modules (the
// checkout needs no `pnpm install`), and swaps the three browser-only modules the
// interpretation code never calls (SvelteKit's `$app/environment`, the toast library, the
// Capacitor shims) for empty stand-ins. Nothing of cordn-web is copied: it is read from the
// checkout at generation time, by Node's own type stripping (`--experimental-strip-types`).
//
// Registered by app-events.mjs with `module.register`, given `data.webRoot`.
import { existsSync, statSync } from "node:fs";
import { pathToFileURL, fileURLToPath } from "node:url";

let webRoot;
let webLib;
const self = new URL("./", import.meta.url);

const STUBS = {
  "$app/environment": "export const browser = false; export const dev = false; export const building = false;",
  "svelte-sonner": "export const toast = Object.assign(() => {}, { success() {}, error() {}, info() {}, warning() {} });",
  "$lib/services/nativeShims": "export async function copyText() {} export async function saveBlob() {}",
};

export function initialize(data) {
  webRoot = data.webRoot;
  webLib = pathToFileURL(`${webRoot}/src/lib/`).href;
}

function withTsExtension(url) {
  const path = fileURLToPath(url);
  for (const candidate of [path, `${path}.ts`, `${path}.js`, `${path}/index.ts`]) {
    if (existsSync(candidate) && statSync(candidate).isFile()) return pathToFileURL(candidate).href;
  }
  return null;
}

export async function resolve(specifier, context, next) {
  if (STUBS[specifier] !== undefined) {
    return { url: `data:text/javascript,${encodeURIComponent(STUBS[specifier])}`, shortCircuit: true };
  }
  const fromWeb = context.parentURL?.startsWith(pathToFileURL(webRoot).href);
  if (specifier.startsWith("$lib/")) {
    const url = withTsExtension(new URL(specifier.slice(5), webLib));
    if (!url) throw new Error(`cordn-web module not found: ${specifier}`);
    return { url, shortCircuit: true };
  }
  if (fromWeb && (specifier.startsWith("./") || specifier.startsWith("../"))) {
    const url = withTsExtension(new URL(specifier, context.parentURL));
    if (url) return { url, shortCircuit: true };
  }
  if (fromWeb && !specifier.startsWith(".") && !specifier.startsWith("node:") && !specifier.startsWith("file:")) {
    return next(specifier, { ...context, parentURL: self.href });
  }
  return next(specifier, context);
}
