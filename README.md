# Leinpad

A launchpad-inspired dev process launcher for Leiningen projects.

> **⚠️ Development use only.** Leinpad is a local development tool — it
> injects dev dependencies, starts nREPL, and connects your editor. It is not
> intended as a process launcher for production, staging, or CI environments.

Leinpad is to Leiningen what [lambdaisland/launchpad](https://github.com/lambdaisland/launchpad) is to deps.edn. It is a dev process launcher that orchestrates everything needed to get a local development environment running: starting services, configuring nREPL middleware, injecting dev dependencies, connecting your editor, and calling your system's `go` function. One command, consistent setup across the whole team.

It starts from these observations:

- Clojure development is done interactively
- This requires a nREPL connection between a Clojure process and an editor
- How Clojure/nREPL gets started varies by
  - editor (which middleware to include?)
  - project (how to start the system, cljs config)
  - individual (preferences in tooling, local roots)
- We mainly rely on our editors to launch Clojure/nREPL because it is tedious
- Other tools could benefit from participating in the startup sequence (e.g. lambdaisland/classpath)
- Automating startup is done in an editor-specific way (.dir-locals.el, calva.replConnectSequences)
- And requires copying boilerplate around (user.clj)

And these preferences:

- We want project setup to be self-contained, so starting a process "just works"
- This should work for everyone on the team, no matter what editor they use
- We prefer running the process in a terminal for cleaner separation and control

## How it works

Leinpad is a babashka-compatible library. Through a configurable pipeline of steps it orchestrates your entire dev startup: running custom setup (Docker services, migrations, environment checks), building a `lein` command with injected nREPL/CIDER/refactor-nrepl/shadow-cljs dependencies via `lein update-in`, starting the REPL, and performing post-startup tasks (connecting your editor, starting shadow-cljs builds, calling `(user/go)`) -- all without modifying your `project.clj`.

It takes information from `leinpad.edn` (checked in) and `leinpad.local.edn` (not checked in) and arguments passed on the command line to determine which profiles to activate, which middleware to add to nREPL, which shadow-cljs builds to start, and extra dependencies to inject.

## Project setup

See `template-*` for an example setup. You need a few pieces:

### `bb.edn`

```clojure
{:deps {io.github.ovistoica/leinpad {:git/sha "..."}}

 :tasks
 {leinpad {:doc "Start development REPL"
           :requires ([leinpad.core :as leinpad])
           :task (leinpad/main {})}}}
```

### `bin/leinpad`

A recognizable entry point for your project. This is a simple babashka script invoking leinpad, with room to customize startup (check Java version, launch docker-compose, etc).

```clojure
#!/usr/bin/env bb

(require '[leinpad.core :as leinpad])

(leinpad/main {})

;; Customize with pre/post steps and options:
;;
;; (leinpad/main
;;   {:profiles [:dev :test]
;;    :nrepl-port 7888
;;    :go true
;;    :pre-steps [my-docker-step]
;;    :post-steps [my-post-step]})
```

### `leinpad.edn`

Project-level configuration, checked into version control.

```clojure
{:leinpad/options {:clean true}
 :leinpad/profiles [:dev]}
```

### `leinpad.local.edn`

Personal overrides, add to `.gitignore`. Same format as `leinpad.edn`. Collection keys (`:leinpad/profiles`, `:leinpad/extra-deps`) are merged with `distinct` across both files.

```clojure
{:leinpad/options {:emacs true :verbose true}
 :leinpad/profiles [:test]
 :leinpad/main-opts ["--go"]
 :leinpad/extra-deps [[hashp/hashp "0.2.2"]
                       [djblue/portal "0.58.2"]]}
```

### `.gitignore`

Make sure to `.gitignore` the local config file:

```shell
echo leinpad.local.edn >> .gitignore
# Optional if you plan to use .env
echo .env.local >> .gitignore
```

## Configuration

Both `leinpad.edn` and `leinpad.local.edn` use the same format with these keys:

| Key                   | Type   | Description                                                                          |
|-----------------------|--------|--------------------------------------------------------------------------------------|
| `:leinpad/options`    | map    | CLJ-style options (`:emacs`, `:verbose`, `:clean`, `:go`, `:jvm-opts`, etc.)         |
| `:leinpad/profiles`   | vector | Lein profiles to activate                                                            |
| `:leinpad/extra-deps` | vector | Extra dependencies injected via `lein update-in`                                     |
| `:leinpad/main-opts`  | vector | Default CLI args (cli version of `:leinpad/options`) (e.g. `["--emacs" "--go"]`)     |
| `:leinpad/env`        | map    | Environment variables (string keys/values) for the REPL process               |

### Merge strategy

Configs merge in order: `defaults < .env < .env.local < leinpad.edn < leinpad.local.edn < CLI args`

- `:leinpad/options` -- deep merge (later values win per key)
- `:leinpad/env` -- deep merge (later values win per key); `.env` files are lowest priority
- `:leinpad/profiles` -- combined with `distinct`
- `:leinpad/extra-deps` -- combined with `distinct`
- `:leinpad/main-opts` -- last non-nil wins

## Environment Variables

Leinpad supports setting environment variables for the REPL process through two mechanisms:

### 1. `.env` files

Create `.env` and/or `.env.local` files in your project root using the standard dotenv format:

```shell
# .env - Team defaults (checked into git)
DATABASE_URL=jdbc:postgresql://localhost/myapp_dev
LOG_LEVEL=info
APP_ENV=development
```

```shell
# .env.local - Personal overrides (add to .gitignore)
DATABASE_URL=jdbc:postgresql://localhost/my_custom_db
LOG_LEVEL=debug
AWS_PROFILE=personal-dev
```

This format is standard across ecosystems (Node, Rails, Docker, etc.) and works with tools like `direnv` and `docker-compose`.

### 2. `:leinpad/env` in config files

Specify environment variables directly in `leinpad.edn` or `leinpad.local.edn`:

```clojure
;; leinpad.edn
{:leinpad/options {:clean true}
 :leinpad/env {"DATABASE_URL" "jdbc:postgresql://localhost/myapp_dev"
               "LOG_LEVEL" "info"}}

;; leinpad.local.edn
{:leinpad/env {"DATABASE_URL" "jdbc:postgresql://localhost/custom_db"
               "AWS_PROFILE" "personal"}}
```

### Merge precedence

When the same variable is defined in multiple places, later sources win:

1. Parent shell environment (inherited, lowest priority)
2. `.env`
3. `.env.local`
4. `:leinpad/env` in `leinpad.edn`
5. `:leinpad/env` in `leinpad.local.edn` (highest priority)

### Security notes

- **Never commit secrets** in files tracked by git
- Use `.env.local` or `leinpad.local.edn` (both gitignored) for sensitive values
- Run with `--verbose` to see which env var keys are set (values are never logged)

### Checking env vars in the REPL

```clojure
(System/getenv "DATABASE_URL")
;; => "jdbc:postgresql://localhost/my_custom_db"

(System/getenv "PATH")
;; => "/usr/bin:/bin:..." (inherited from parent shell)
```

## Using Leinpad

Start your REPL with either:

```
bb leinpad
```

or:

```
bin/leinpad
```

Pass flags on the command line to override configuration:

```
bb leinpad --help
leinpad - A launchpad-inspired dev process launcher for Leiningen projects

Options:
  --emacs              Connect Emacs CIDER after REPL starts
  --no-emacs           Don't connect Emacs (default)
  -v, --verbose        Show debug output
  --go                 Call (user/go) after REPL starts
  --no-go              Don't call (user/go) (default)
  --clean              Run lein clean before starting (default)
  --no-clean           Skip lein clean
  --no-jvm-opts        Skip default JVM opts injection
  -p, --port PORT      nREPL port (default: random)
  -b, --bind ADDR      nREPL bind address (default: 127.0.0.1)
  --profile PROFILE    Add lein profile (repeatable)

Middleware:
  --cider-nrepl        Include CIDER nREPL middleware
  --no-cider-nrepl     Exclude CIDER middleware (default)
  --refactor-nrepl     Include refactor-nrepl middleware
  --no-refactor-nrepl  Exclude refactor-nrepl (default)
  --vs-code            Alias for --cider-nrepl

Shadow-cljs:
  --shadow-cljs        Enable shadow-cljs integration
  --no-shadow-cljs     Disable shadow-cljs (default)
  --shadow-build ID    Shadow build to watch (repeatable)
  --shadow-connect ID  Shadow build to connect REPL (repeatable)

  --help               Show this help
```

Emacs is best supported -- leinpad queries Emacs to find the right versions of cider-nrepl and refactor-nrepl to inject, and instructs Emacs to connect to the REPL automatically.

For other editors, use `--cider-nrepl` (or `--vs-code`) and connect to the nREPL port manually.

## Writing custom steps

Leinpad performs a series of steps, threading a context map through each one. The default pipeline is:

**Before process starts:**
1. `read-lein-config` -- Read and merge config files
2. `get-nrepl-port` -- Assign a free port if not set
3. `inject-jvm-opts` -- Populate JVM opts (dev defaults + user-specified)
4. `inject-lein-middleware` -- Resolve middleware dependency versions
5. `maybe-lein-clean` -- Run `lein clean` if configured
6. `print-summary` -- Print startup overview

**After process starts:**
1. `wait-for-nrepl` -- Wait for nREPL to become reachable
2. `maybe-start-shadow` -- Start shadow-cljs builds via nREPL
3. `maybe-go` -- Evaluate `(user/go)` via nREPL
4. `maybe-connect-emacs` -- Connect Emacs CIDER to nREPL

You can add custom steps before or after `start-lein-process`:

```clj
(require '[leinpad.core :as leinpad]
         '[babashka.process :refer [process]])

(defn docker-up [ctx]
  @(process ["docker-compose" "up" "-d"] {:out :inherit :err :inherit})
  ctx)

(leinpad/main
 {:pre-steps [docker-up]})
```

Or fully override the step pipeline:

```clojure
(leinpad/main
 {:steps (concat leinpad/before-steps
                 [docker-up
                  leinpad/start-lein-process]
                 leinpad/after-steps)})
```

## Shadow-cljs support

For projects using shadow-cljs, see the `template-shadow-cljs` directory. Leinpad starts the Leiningen REPL with shadow-cljs middleware injected, then starts shadow-cljs builds via nREPL after the REPL is running.

```clojure
;; leinpad.edn
{:leinpad/options {:shadow-cljs true :clean true}
 :leinpad/profiles [:dev]}
```

With `--emacs`, leinpad connects both CLJ and CLJS sibling REPLs to Emacs CIDER.

## JVM Opts Injection

Leinpad automatically injects several JVM flags that significantly improve the Clojure development experience:

| Flag | Purpose |
|------|---------|
| `-XX:-OmitStackTraceInFastThrow` | Prevents HotSpot from eliding stack traces on frequently thrown exceptions |
| `-Dclojure.main.report=stderr` | Prints uncaught exceptions to stderr instead of a temp file |
| `-Djdk.attach.allowAttachSelf` | Enables nREPL interrupt (`C-c C-c`) on JDK 21+ |
| `-XX:+EnableDynamicAgentLoading` | Allows dynamic agent loading on JDK 21+ |

These flags are injected by default. To disable them:

```
bb leinpad --no-jvm-opts
```

Or in `leinpad.edn`:

```clojure
{:leinpad/options {:inject-jvm-opts false}}
```

You can also add your own JVM opts via `leinpad.edn`:

```clojure
{:leinpad/options {:jvm-opts ["-Xmx4g" "-Dmy.prop=value"]}}
```

User-specified JVM opts are merged with the defaults (unless defaults are disabled).

## Feature Comparison with Launchpad

Leinpad is the Leiningen equivalent of [lambdaisland/launchpad](https://github.com/lambdaisland/launchpad) (deps.edn). This table gives a complete comparison of the two.

### Core REPL Features

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| nREPL launch | ✅ | ✅ | |
| Random free port | ✅ | ✅ | |
| Custom nREPL port/bind | ✅ | ✅ | |
| `--go` / `(user/go)` | ✅ | ✅ | |
| Custom step pipeline | ✅ | ✅ | `pre-steps`/`post-steps`/`steps` |
| Local config file (not checked in) | ✅ | ✅ | `deps.local.edn` / `leinpad.local.edn` |

### Editor Integration

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| CIDER middleware injection | ✅ | ✅ | |
| refactor-nrepl injection | ✅ | ✅ | |
| Emacs auto-connect (CLJ) | ✅ | ✅ | |
| Emacs auto-connect (CLJS sibling) | ✅ | ✅ | |
| Emacs version detection | ✅ | ✅ | Queries running Emacs for middleware versions |
| `--vs-code` alias | ✅ | ✅ | |

### ClojureScript

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| Shadow-cljs integration | ✅ | ✅ | |
| Shadow-cljs build watch | ✅ | ✅ | |
| Shadow-cljs CLJS REPL connect | ✅ | ✅ | |

### JVM Configuration

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| `-XX:-OmitStackTraceInFastThrow` | ✅ | ✅ | Keeps full stack traces in dev |
| `-Dclojure.main.report=stderr` | ✅ | ✅ | Uncaught exceptions printed to terminal |
| `-Djdk.attach.allowAttachSelf` | ✅ | ✅ | nREPL interrupt on JDK 21+ |
| `-XX:+EnableDynamicAgentLoading` | ✅ | ✅ | Dynamic agent loading on JDK 21+ |
| Custom JVM opts via config | ✅ | ✅ | `:java-args` in launchpad / `:jvm-opts` in leinpad |

### Environment Variables

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| `.env` / `.env.local` loading | ✅ | ✅ | |
| Hot-reload `.env` files | ✅ | ❌ | Requires JVM hacks; leinpad loads once at startup |
| Config-file env vars | ❌ | ✅ | `:leinpad/env` in `leinpad.edn` |

### Dev Tool Integrations

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| Portal data inspector (`--portal`) | ✅ | ❌ | Planned |
| Sayid tracing debugger (`--sayid`) | ✅ | ❌ | Planned |
| debug-repl (`--debug-repl`) | ✅ | ❌ | Planned |
| `--no-namespace-maps` middleware | ✅ | ❌ | Planned |
| Java version check utility | ✅ | ❌ | Planned |

### Process Management

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| `run-process` helper | ✅ | ❌ | Planned; users can use `babashka.process` directly |
| Process output prefixing | ✅ | ❌ | Colored `[process]` prefix on stdout/stderr |
| `--no-prefix` to disable it | ✅ | ❌ | |

### Build Tool Specific

| Feature | Launchpad | Leinpad | Notes |
|---------|:---------:|:-------:|-------|
| `lein clean` before start | ❌ | ✅ | Leiningen-specific |
| Hot-reload deps.edn | ✅ | ❌ | Requires `lambdaisland/classpath` + tools.deps |
| File watcher infrastructure | ✅ | ❌ | Requires `beholder` (JVM library) |
| `--execute` (`:exec-fn`) | ✅ | ❌ | deps.edn feature, no Leiningen equivalent |
| Inject aliases as system property | ✅ | ❌ | Not directly applicable |
| Classpath cache writing | ✅ | ❌ | tools.deps specific (`.cpcache/launchpad.cp`) |

### Key Architectural Differences

- **Dependency injection** -- Launchpad uses tools.deps' `-Sdeps` flag. Leinpad uses `lein update-in` to achieve the same effect without modifying `project.clj`.
- **Hot-reloading** -- Launchpad can live-reload dependencies, `.env` files, and watch for file changes at runtime via JVM internals (`lambdaisland/classpath`, `jnr-posix`, `beholder`). Leinpad loads everything once at startup — changes require a restart.

## License

Copyright 2026 Ovi Stoica

Licensed under the MIT License.
