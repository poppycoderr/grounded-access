# Release checklist

What has to be true before a version is tagged, and how each item is checked. Items marked **CI** are enforced on every pull request by the `build` check, so a green `main` means they hold. The others are done by hand at release time.

## Enforced by CI

| Item | Check | Job |
|---|---|---|
| The control plane builds and its tests pass on Java 21 and 25, including the authorization property tests | `mvn -B -ntp verify` | `control-plane` |
| The model service and the evaluation CLI pass lint and tests; the dataset is valid | `ruff`, `pytest`, `ga-eval validate` | `model-service`, `evaluation` |
| The README quick start works as printed, in both languages | `scripts/readme-commands.py` | `smoke` |
| No unauthorized result in any strategy, for any demo principal, also under concurrent load | `./scripts/benchmark`, `./scripts/load-smoke` | `smoke` |
| Relative links and anchors in the docs resolve | `scripts/check-links.py` | `docs` |
| No secret in the git history | gitleaks, with the public demo key as the only exception (`.gitleaks.toml`) | `supply-chain` |
| No high or critical vulnerability with an available fix in either image | Trivy, exceptions with reasons in `ops/supply-chain/trivyignore.yaml` | `supply-chain` |
| Every Maven and PyPI dependency is under an allowed license | `scripts/check-licenses.py` with `ops/supply-chain/licenses.json` | `supply-chain` |
| An SBOM (CycloneDX) exists for each image | artifact `sbom` of the run | `supply-chain` |

The scans can be run locally with the commands in `.github/workflows/build.yml`; they need only Docker and Maven.

## By hand, for each release

1. `main` is green on the commit to be tagged.
2. The changelog has an entry for the version: what changed for users, what the measurements say, what is not included.
3. The [known limitations](./known-limitations.md) are still true. Remove what was fixed, add what was found.
4. Published numbers in the READMEs and docs match the committed result files under `benchmarks/reports/`. Regenerate the diagrams (`scripts/generate-diagrams.py`) and the terminal recording (`scripts/generate-demo-animation.py`) if the numbers changed.
5. Every entry in `ops/supply-chain/trivyignore.yaml` and every override in `ops/supply-chain/licenses.json` is still needed and its reason still holds.
6. Version overrides in the root `pom.xml` that exist only to fix a vulnerability are removed if the Spring Boot version in use now manages a fixed version.
7. Tag the commit (`git tag -a vX.Y.Z`), push the tag, and create the GitHub release with the changelog entry as its notes.
8. Download the `sbom` artifact of the tagged commit's CI run and attach both files to the release (`gh release upload vX.Y.Z sbom/*.cdx.json`).

## Not done for v0.1

Images are not published to a registry and not signed, base images are referenced by tag and not by digest, model weights are not pinned by revision, and external links in the docs are not checked. These are listed in the [known limitations](./known-limitations.md).
