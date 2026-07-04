.PHONY: install dash

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
SOURCE_LDFLAGS := -X skein-strand-cli/internal/config.InstalledSource=$(CURDIR)

install:
	go install -ldflags "$(SOURCE_LDFLAGS)" $(GO_CLI)
	go install -ldflags "$(SOURCE_LDFLAGS)" $(MILL_CLI)

# code-owner TUI over live shuttle agent runs; polls the strand CLI
dash:
	bun install --cwd scripts/shuttle-dash --silent
	bun scripts/shuttle-dash/index.tsx
