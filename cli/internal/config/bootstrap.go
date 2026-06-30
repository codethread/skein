package config

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const DefaultInitCLJ = "(require '[skein.libs.alpha :as libs])\n\n(libs/sync!)\n"
const DefaultSkeinGitignore = "config.json\ninit.local.clj\nlibs.local.edn\nstate/\ndata/\nweaver.*\n*.sqlite\n*.sqlite-*\n"

// InstalledSource is optionally set at build time by the repo Makefile so
// mill-routed `strand init` can write config.json without users passing
// --source or exporting SKEIN_SOURCE after installing from a checkout.
var InstalledSource string

func BootstrapWorld(cwd, configDir, source string) (World, error) {
	world, err := BootstrapTargetWorld(cwd, configDir)
	if err != nil {
		return World{}, err
	}
	if err := os.MkdirAll(filepath.Join(world.ConfigDir, "libs"), 0o755); err != nil {
		return World{}, err
	}
	if _, err := os.Stat(world.ConfigFile); os.IsNotExist(err) {
		absSource, err := InitSource(cwd, source)
		if err != nil {
			return World{}, err
		}
		data, err := json.Marshal(Config{ConfigFormat: "alpha", Source: absSource})
		if err != nil {
			return World{}, err
		}
		if err := os.WriteFile(world.ConfigFile, append(data, '\n'), 0o644); err != nil {
			return World{}, err
		}
	} else if err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, "libs.edn"), "{:libs {}}\n"); err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, "init.clj"), DefaultInitCLJ); err != nil {
		return World{}, err
	}
	if err := writeMissing(filepath.Join(world.ConfigDir, ".gitignore"), DefaultSkeinGitignore); err != nil {
		return World{}, err
	}
	if _, _, err := Load(world.ConfigDir); err != nil {
		return World{}, err
	}
	return world, nil
}

func BootstrapTargetWorld(cwd, configDir string) (World, error) {
	if configDir != "" {
		if !filepath.IsAbs(configDir) {
			if cwd == "" {
				var err error
				cwd, err = os.Getwd()
				if err != nil {
					return World{}, err
				}
			}
			if realCWD, err := filepath.EvalSymlinks(cwd); err == nil {
				cwd = realCWD
			}
			configDir = filepath.Join(cwd, configDir)
		}
		return isolatedWorld(configDir)
	}
	root, err := GitRoot(cwd)
	if err != nil {
		return World{}, err
	}
	return isolatedWorld(filepath.Join(root, ".skein"))
}

func GitRoot(cwd string) (string, error) {
	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	cmd := exec.Command("git", "rev-parse", "--show-toplevel")
	cmd.Dir = cwd
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("strand init requires cwd inside a Git worktree; run `git init` or pass --config-dir: %w", err)
	}
	return strings.TrimSpace(string(out)), nil
}

func writeMissing(path, content string) error {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return os.WriteFile(path, []byte(content), 0o644)
	} else {
		return err
	}
}

func InitSource(cwd, source string) (string, error) {
	if source != "" {
		return ResolveSource(source)
	}
	if env := os.Getenv("SKEIN_SOURCE"); env != "" {
		return ResolveSource(env)
	}
	if InstalledSource != "" {
		return ResolveSource(InstalledSource)
	}
	if cwd == "" {
		var err error
		cwd, err = os.Getwd()
		if err != nil {
			return "", err
		}
	}
	if source, err := ResolveSource(cwd); err == nil {
		return source, nil
	}
	return "", fmt.Errorf("could not resolve Skein source for config.json; install with `make install`, run `strand init --source <skein-source>`, or set SKEIN_SOURCE")
}
