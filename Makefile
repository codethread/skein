.PHONY: build install dash api-docs docs-site docs-serve docs-check fmt fmt-check lint lint-go lint-clj lint-splint reflect-check deps-report security-report

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
SOURCE_LDFLAGS := -X skein-strand-cli/internal/config.InstalledSource=$(CURDIR)
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

install:
	go install -ldflags "$(SOURCE_LDFLAGS)" $(GO_CLI)
	go install -ldflags "$(SOURCE_LDFLAGS)" $(MILL_CLI)

# code-owner TUI over live shuttle agent runs; polls the strand CLI
dash:
	bun install --cwd scripts/shuttle-dash --silent
	bun scripts/shuttle-dash/index.tsx

api-docs:
	@if command -v bb >/dev/null 2>&1; then \
		bb -Sdeps $(QUICKDOC_DEPS) $(QUICKDOC_SCRIPT); \
	else \
		PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" clojure -Sdeps $(QUICKDOC_DEPS) -M $(QUICKDOC_SCRIPT); \
	fi

docs-site:
	uvx --from mkdocs --with mkdocs-material mkdocs build --strict

docs-check:
	$(MAKE) api-docs
	git diff --exit-code -- 'spools/*.api.md'
	$(MAKE) docs-site

docs-serve:
	uvx --from mkdocs --with mkdocs-material mkdocs serve --dev-addr 127.0.0.1:8000

fmt:
	clojure -M:format/fix
	cd cli && go run mvdan.cc/gofumpt@$(GOFUMPT_VERSION) -w .

fmt-check:
	clojure -M:format
	cd cli && test -z "$$(go run mvdan.cc/gofumpt@$(GOFUMPT_VERSION) -l .)"

lint: lint-clj lint-splint lint-go

lint-clj:
	clojure -M:lint/clj-kondo

lint-splint:
	clojure -M:lint/splint

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
