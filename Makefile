.PHONY: all build install bootstrap open-config

all: build install bootstrap

GO_CLI := ./cli/cmd/todo
BIN := ./cli/bin/todo
CONFIG_HOME ?= $(if $(XDG_CONFIG_HOME),$(XDG_CONFIG_HOME),$(HOME)/.config)
ATOM_CONFIG ?= $(CONFIG_HOME)/atom
CONFIG_DIR := $(ATOM_CONFIG)
CONFIG_FILE := $(CONFIG_DIR)/config.json

build:
	go build -o $(BIN) $(GO_CLI)

install:
	go install $(GO_CLI)

# Quick bootstrap matching README.md setup steps.
bootstrap:
	@if ! command -v jq >/dev/null 2>&1; then \
		echo "jq is required for bootstrap (required by README.md)" >&2; \
		exit 1; \
	fi
	go install $(GO_CLI)
	mkdir -p "$(CONFIG_DIR)"
	printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$(CURDIR)" | jq . > "$(CONFIG_FILE)"

open-config: bootstrap
	@if [ -z "$(EDITOR)" ]; then \
		echo "EDITOR is not set" >&2; \
		exit 1; \
	fi
	$(EDITOR) "$(CONFIG_FILE)"
