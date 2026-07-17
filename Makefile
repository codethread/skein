.PHONY: build install dash api-docs docs-site docs-serve docs-check fmt fmt-check lint lint-go lint-clj lint-splint lint-conventions reflect-check deps-report security-report test-warm test-warm-stop spool-suite-gate

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
# BuildID falls back to the compiled-in "dev" when git is unavailable; it is
# informational (skew attribution), so unlike InstalledSource it may degrade.
BUILD_ID := $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
SOURCE_LDFLAGS := -X skein-strand-cli/internal/config.InstalledSource=$(CURDIR) -X skein-strand-cli/internal/config.BuildID=$(BUILD_ID)
GOFUMPT_VERSION := v0.8.0
GOLANGCI_LINT_VERSION := v2.1.6
GOVULNCHECK_VERSION := v1.1.4
QUICKDOC_DEPS := '{:deps {io.github.borkdude/quickdoc {:git/tag "v0.2.6" :git/sha "ce86780"}}}'
QUICKDOC_SCRIPT := scripts/generate_api_docs.clj

# repo-local build for agents/worktrees validating CLI changes without touching
# the user's global install; run the resulting ./bin/strand and ./bin/mill directly
build:
	mkdir -p ./bin
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/strand $(GO_CLI)
	go build -ldflags "$(SOURCE_LDFLAGS)" -o ./bin/mill $(MILL_CLI)

# stamp the user's global binaries with the CANONICAL repo checkout (the shared
# .git common dir's parent), not the invoking worktree, so an install run from a
# feature worktree does not repoint mill at ephemeral worktree state. Resolve in
# the recipe and fail loudly: a parse-time $(shell ...) here bakes an empty
# source silently when git rev-parse fails.
install:
	@gitdir="$$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)"; \
	if [ -z "$$gitdir" ]; then \
		echo "make install: not inside a Skein git checkout (git rev-parse --git-common-dir failed); cannot resolve the canonical repo path to stamp into the binaries" >&2; \
		exit 1; \
	fi; \
	src="$$(dirname "$$gitdir")"; \
	if [ -z "$$src" ] || [ ! -d "$$src" ]; then \
		echo "make install: resolved canonical Skein source '$$src' is empty or not a directory" >&2; \
		exit 1; \
	fi; \
	echo "make install: stamping InstalledSource=$$src BuildID=$(BUILD_ID)"; \
	go install -ldflags "-X skein-strand-cli/internal/config.InstalledSource=$$src -X skein-strand-cli/internal/config.BuildID=$(BUILD_ID)" $(GO_CLI) && \
	go install -ldflags "-X skein-strand-cli/internal/config.InstalledSource=$$src -X skein-strand-cli/internal/config.BuildID=$(BUILD_ID)" $(MILL_CLI)

# code-owner TUI over live agent runs; polls the strand CLI
dash:
	bun install --cwd scripts/agent-dash --silent
	bun scripts/agent-dash/index.tsx

api-docs:
	@if command -v bb >/dev/null 2>&1; then \
		bb -Sdeps $(QUICKDOC_DEPS) $(QUICKDOC_SCRIPT); \
	else \
		PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" clojure -Sdeps $(QUICKDOC_DEPS) -M $(QUICKDOC_SCRIPT); \
	fi

docs-site:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs build --strict

# Growth budget for AGENTS.md, which holds only what the live surface cannot
# tell an agent. Placement judgment lives with the docs-drift reviewer
# (guidance belongs to prime/about manuals, devflow/specs, or an automated
# check); this cap forces that conversation when the file grows.
AGENTS_MD_LINE_BUDGET := 70

docs-check:
	@lines=$$(awk 'END{print NR}' AGENTS.md) || { echo "docs-check: cannot read AGENTS.md" >&2; exit 1; }; \
	case "$$lines" in ''|*[!0-9]*) echo "docs-check: unexpected AGENTS.md line count '$$lines'" >&2; exit 1;; esac; \
	if [ "$$lines" -gt $(AGENTS_MD_LINE_BUDGET) ]; then \
		echo "AGENTS.md is $$lines lines, over the $(AGENTS_MD_LINE_BUDGET)-line budget."; \
		echo "Move guidance to the surface that owns it (prime/about manuals, devflow/specs, an automated check) instead of growing AGENTS.md."; \
		exit 1; \
	fi
	$(MAKE) api-docs
	git diff --exit-code -- 'spools/*.api.md' 'spools/executors/*.api.md' 'docs/api/*.api.md'
	$(MAKE) docs-site

docs-serve:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs serve --dev-addr 127.0.0.1:8000

fmt:
	clojure -M:format/fix
	cd cli && go run mvdan.cc/gofumpt@$(GOFUMPT_VERSION) -w .

fmt-check:
	clojure -M:format
	cd cli && test -z "$$(go run mvdan.cc/gofumpt@$(GOFUMPT_VERSION) -l .)"

lint: lint-clj lint-splint lint-conventions lint-go

lint-clj:
	clojure -M:lint/clj-kondo

lint-splint:
	clojure -M:lint/splint

# repo conventions that prose alone cannot hold: ns docstrings everywhere, no
# local bindings named after clojure.core macros, and requires embedded in
# quoted forms resolving to real namespaces (see scripts/quality)
lint-conventions:
	clojure -M:lint/conventions

lint-go:
	cd cli && go run github.com/golangci/golangci-lint/v2/cmd/golangci-lint@$(GOLANGCI_LINT_VERSION) run --config ../.golangci.yml ./...

reflect-check:
	clojure -M:reflect-check

deps-report:
	-clojure -M:deps/antq
	-cd cli && go run golang.org/x/vuln/cmd/govulncheck@$(GOVULNCHECK_VERSION) ./...
	# local-only deep NVD scan; needs CLJ_WATSON_NVD_API_KEY exported
	-clojure -M:security/clj-watson-nvd

security-report:
	-clojure -M:security/clj-watson
	-cd cli && go run golang.org/x/vuln/cmd/govulncheck@$(GOVULNCHECK_VERSION) ./...

# Per-worktree warm test loop: probe-or-boot the worktree's warm REPL and run the
# NS-named namespaces through it. Iteration only — never a Done-when gate; the
# cold `clojure -M:test <ns...>` run is the slice gate (PLAN-Ttv-001.TC1).
test-warm:
	NS="$(NS)" bash scripts/test-warm

# Single local-reproduction surface for the spool-suite gate (PLAN-ssc-001.A1):
# run the pinned external spool suites (codethread/devflow.spool, kanban.spool)
# against this checkout's HEAD, closing the untested skein-src->spool direction.
# The two shas are read from deps.edn as EDN (never restated here, never line-
# grepped) and the target aborts loudly rather than run against an empty sha.
# Each spool source is materialized at its pin into a mktemp scratch root beside a
# `skein-src` link to the invoking checkout ($(CURDIR)), matching the spools'
# committed `:local/root "../skein-src"`. devflow.spool needs the moved
# workflow-spool root injected at job time via -Sdeps (NG2-safe; never an edit to
# the spool's own deps.edn); kanban.spool carries its own workflow-spool root and
# runs plain. On a red suite the target names the spool, its resolved sha, and the
# one command that reproduces it locally. Requires OpenJDK on PATH, e.g.
# PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" make spool-suite-gate.
spool-suite-gate:
	@set -e; \
	src="$(CURDIR)"; \
	coords="$$(clojure -M -e '(let [deps (clojure.edn/read-string (slurp "deps.edn")) ed (get-in deps [:aliases :test :extra-deps]) g (fn [c] (let [m (get ed c) sha (:git/sha m) url (:git/url m)] (when-not (and (string? sha) (string? url)) (binding [*out* *err*] (println (str "spool-suite-gate: deps.edn :aliases :test :extra-deps is missing :git/url or :git/sha for " c "; refusing to run against HEAD/an empty sha"))) (System/exit 1)) [url sha]))] (let [[du ds] (g (quote io.github.codethread/devflow.spool)) [ku ks] (g (quote io.github.codethread/kanban.spool))] (println du ds ku ks) (flush) (System/exit 0)))')" || coords=""; \
	if [ -z "$$coords" ]; then \
		echo "spool-suite-gate: could not extract spool coordinates from deps.edn (unparseable, or missing the :aliases :test :extra-deps spool pins); refusing to run against HEAD/an empty sha" >&2; \
		exit 1; \
	fi; \
	set -- $$coords; \
	durl="$$1"; dsha="$$2"; kurl="$$3"; ksha="$$4"; \
	if [ -z "$$durl" ] || [ -z "$$dsha" ] || [ -z "$$kurl" ] || [ -z "$$ksha" ]; then \
		echo "spool-suite-gate: incomplete spool coordinates extracted from deps.edn" >&2; \
		exit 1; \
	fi; \
	root="$$(mktemp -d)"; \
	trap 'rm -rf "$$root"' EXIT; \
	ln -s "$$src" "$$root/skein-src"; \
	materialize() { \
		name="$$1"; url="$$2"; sha="$$3"; lib="$$4"; dir="$$root/$$name"; \
		gl="$${GITLIBS:-$$HOME/.gitlibs}/libs/$$lib/$$sha"; \
		if [ -d "$$gl" ]; then \
			cp -R "$$gl" "$$dir"; \
			chmod -R u+w "$$dir"; \
		else \
			mkdir -p "$$dir"; \
			git -C "$$dir" init -q; \
			git -C "$$dir" remote add origin "$$url"; \
			if ! git -C "$$dir" fetch -q --depth 1 origin "$$sha"; then \
				git -C "$$dir" fetch -q origin; \
			fi; \
			git -C "$$dir" checkout -q "$$sha"; \
		fi; \
	}; \
	materialize devflow.spool "$$durl" "$$dsha" io.github.codethread/devflow.spool; \
	materialize kanban.spool "$$kurl" "$$ksha" io.github.codethread/kanban.spool; \
	dcmd="clojure -Sdeps '{:deps {io.skein/workflow-spool {:local/root \"../skein-src/spools/workflow\"}}}' -M:test"; \
	kcmd="clojure -M:test"; \
	echo "==> spool-suite-gate: devflow.spool@$$dsha (with workflow-spool injection)"; \
	if ! ( cd "$$root/devflow.spool" && eval "$$dcmd" ); then \
		printf '\n%s\n' "spool-suite-gate: devflow.spool@$$dsha FAILED against skein-src HEAD ($$src)" >&2; \
		echo "  reproduce: make spool-suite-gate" >&2; \
		echo "  or, in a sibling layout with ./skein-src beside ./devflow.spool, from devflow.spool/: $$dcmd" >&2; \
		exit 1; \
	fi; \
	echo "==> spool-suite-gate: kanban.spool@$$ksha"; \
	if ! ( cd "$$root/kanban.spool" && eval "$$kcmd" ); then \
		printf '\n%s\n' "spool-suite-gate: kanban.spool@$$ksha FAILED against skein-src HEAD ($$src)" >&2; \
		echo "  reproduce: make spool-suite-gate" >&2; \
		echo "  or, in a sibling layout with ./skein-src beside ./kanban.spool, from kanban.spool/: $$kcmd" >&2; \
		exit 1; \
	fi; \
	echo "spool-suite-gate: OK (devflow.spool@$$dsha, kanban.spool@$$ksha) against skein-src HEAD"

# Reap the worktree's warm REPL by recorded PID (PID only, never `pkill -f`) and
# remove the runtime files (PLAN-Ttv-001.R1). The land cleanup step calls this
# before `wktree remove`.
test-warm-stop:
	@if [ -f .test-repl.pid ]; then \
		pid="$$(tr -d '[:space:]' <.test-repl.pid)"; \
		if [ -n "$$pid" ]; then \
			echo "test-warm-stop: killing recorded warm REPL pid $$pid"; \
			kill "$$pid" 2>/dev/null || true; \
		fi; \
	fi; \
	rm -f .test-repl-port .test-repl.pid
