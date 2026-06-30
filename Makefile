.PHONY: install

GO_CLI := ./cli/cmd/strand
MILL_CLI := ./cli/cmd/mill
SOURCE_LDFLAGS := -X skein-strand-cli/internal/config.InstalledSource=$(CURDIR)

install:
	go install -ldflags "$(SOURCE_LDFLAGS)" $(GO_CLI)
	go install -ldflags "$(SOURCE_LDFLAGS)" $(MILL_CLI)
