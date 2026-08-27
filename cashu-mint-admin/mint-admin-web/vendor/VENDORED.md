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
`nap-core/src/codec.ts`.

Since #375 the login flow imports `nap-client-web` and `nap-react`, so the
vendored sources are part of the app's program and that choice had to be made.
The app relaxed `noUncheckedIndexedAccess` (see the comment in
`tsconfig.json`). Building the packages to `.d.ts` was the alternative; it was
turned down because it puts generated artifacts in a tree whose entire sync
story is a directory swap. Patching the vendored sources was never one of the
choices.
