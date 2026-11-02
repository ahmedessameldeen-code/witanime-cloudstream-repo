# My CloudStream Plugins

Generated with [site2cs](https://github.com/). One Kotlin engine, one config per site.

## Install in CloudStream

1. Open **CloudStream -> Settings -> Extensions -> Add repository**.
2. Paste this URL:

   ```
   https://raw.githubusercontent.com/ahmedessameldeen-code/witanime-cloudstream-repo/refs/heads/main/repo.json
   ```

## Sites in this repo

| Site | Module | Language | Types |
| --- | --- | --- | --- |
| اهواك تي في | `Yam` | en | TvSeries, Movie |

## Rebuild locally

```
./gradlew make makePluginsJson
```

Artifacts land in `build/` (`.cs3` files and `plugins.json`).

## Add another site

```
site2cs add https://example.com
```

## How it works

Each module is a CloudStream plugin. `engine/SiteEngine.kt` is identical in every
module and contains all scraping logic; `<Site>Spec.kt` only holds selectors and
URLs. Fixing a broken site means editing that spec, not the engine.
