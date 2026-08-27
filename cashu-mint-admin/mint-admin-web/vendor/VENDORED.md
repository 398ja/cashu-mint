# Vendored NAP packages

The `@imani/*` NAP client packages are not published to any npm registry. They
are copied in here as npm workspaces so the admin web app can import them
without a private registry.

| | |
|---|---|
| Upstream | https://github.com/tcheeric/nap |
| Version | 0.10.1 |
| Source commit | `000f16b32e367a821a7594ef2aa9501cbf465d46` (2026-08-22) |
| Packages | `nap-core`, `nap-client-http`, `nap-client-web`, `nap-client-nip46`, `nap-react` |

## How they got here

Each `packages/<name>/` directory upstream was copied to `vendor/<name>/`
verbatim, minus `test/`, `test-vectors/` and `scripts/` — the packages' own
suites belong to the upstream repo and are not re-run here. Nothing else was
edited, so the inter-package `@imani/*` imports resolve to the vendored
siblings exactly as they do upstream.

## Syncing

There is no automation. To take a new upstream version, replace the
`vendor/<name>/` directories with the new ones, update the table above, and run
`npm install` to refresh the lockfile. The app depends on them as `"*"` so a
version bump needs no edit outside `vendor/`. Keep it a directory swap:
patching vendored sources turns every future sync into a merge.

## Typechecking

The packages ship raw TypeScript (`exports` points at `src/index.ts`), so a
consumer compiles them with its own compiler options. This app's options are
stricter than upstream's — `noUncheckedIndexedAccess` alone flags four spots in
`nap-core/src/codec.ts`. `tsconfig.json` therefore excludes
`src/vendorResolution.test.ts`, the only file in `src` that imports them, which
keeps the vendored sources out of the app's program. Vitest ignores that
exclude, so the test still runs.

That holds only while nothing else in `src` imports them. The first real consumer
(#375) pulls the whole tree into the app's typecheck and has to choose: build
the packages to `.d.ts` (which `skipLibCheck` then covers), or relax the app's
options. Patching the vendored sources is not one of the choices.
