package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const ConfigFileName = "config.json"
const DefaultDBFileName = "skein.sqlite"

var allowedKeys = map[string]bool{"configFormat": true, "source": true}

type World struct {
	ConfigDir  string
	StateDir   string
	DataDir    string
	ConfigFile string
	DBPath     string
}

type Config struct {
	ConfigFormat string `json:"configFormat"`
	Source       string `json:"source"`
}

func DefaultWorld() (World, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return World{}, err
	}
	configHome := os.Getenv("XDG_CONFIG_HOME")
	if configHome == "" {
		configHome = filepath.Join(home, ".config")
	}
	stateHome := os.Getenv("XDG_STATE_HOME")
	if stateHome == "" {
		stateHome = filepath.Join(home, ".local", "state")
	}
	dataHome := os.Getenv("XDG_DATA_HOME")
	if dataHome == "" {
		dataHome = filepath.Join(home, ".local", "share")
	}
	return world(filepath.Join(configHome, "skein"), filepath.Join(stateHome, "skein"), filepath.Join(dataHome, "skein")), nil
}

func ExplicitWorld(configDir string) (World, error) {
	if configDir == "" {
		return DefaultWorld()
	}
	abs, err := filepath.Abs(configDir)
	if err != nil {
		return World{}, err
	}
	if real, err := filepath.EvalSymlinks(abs); err == nil {
		abs = real
	}
	abs = filepath.Clean(abs)
	return world(abs, filepath.Join(abs, "state"), filepath.Join(abs, "data")), nil
}

func world(configDir, stateDir, dataDir string) World {
	return World{ConfigDir: configDir, StateDir: stateDir, DataDir: dataDir, ConfigFile: filepath.Join(configDir, ConfigFileName), DBPath: filepath.Join(dataDir, DefaultDBFileName)}
}

func Load(configDir string) (Config, World, error) {
	w, err := ExplicitWorld(configDir)
	if err != nil {
		return Config{}, World{}, err
	}
	b, err := os.ReadFile(w.ConfigFile)
	if os.IsNotExist(err) {
		return Config{}, w, nil
	}
	if err != nil {
		return Config{}, World{}, err
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return Config{}, World{}, fmt.Errorf("malformed client config: %w", err)
	}
	for k := range raw {
		if !allowedKeys[k] {
			return Config{}, World{}, fmt.Errorf("unsupported client config key: %s", k)
		}
	}
	var c Config
	if v, ok := raw["configFormat"]; ok {
		if err := json.Unmarshal(v, &c.ConfigFormat); err != nil {
			return Config{}, World{}, fmt.Errorf("client config configFormat must be a string")
		}
	} else {
		return Config{}, World{}, fmt.Errorf("client config configFormat is required")
	}
	if c.ConfigFormat != "alpha" {
		return Config{}, World{}, fmt.Errorf("unsupported client config configFormat: %s", c.ConfigFormat)
	}
	if v, ok := raw["source"]; ok {
		if err := json.Unmarshal(v, &c.Source); err != nil {
			return Config{}, World{}, fmt.Errorf("client config source must be a string")
		}
	}
	return c, w, nil
}

func ResolveSource(source string) (string, error) {
	if source == "" {
		return "", fmt.Errorf("client config source is required for weaver lifecycle commands; set source in %s", ConfigFileName)
	}
	resolvedSource := source
	if strings.HasPrefix(source, "~") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if source == "~" {
			resolvedSource = home
		} else if strings.HasPrefix(source, "~/") {
			resolvedSource = filepath.Join(home, source[2:])
		} else if strings.HasPrefix(source, `~\\`) {
			resolvedSource = filepath.Join(home, source[2:])
		}
	}
	if !filepath.IsAbs(resolvedSource) {
		return "", fmt.Errorf("client config source must be an absolute path: %s", resolvedSource)
	}
	st, err := os.Stat(resolvedSource)
	if err != nil {
		return "", fmt.Errorf("client config source must be an existing directory: %s", resolvedSource)
	}
	if !st.IsDir() {
		return "", fmt.Errorf("client config source must be an existing directory: %s", resolvedSource)
	}
	if st, err := os.Stat(filepath.Join(resolvedSource, "deps.edn")); err != nil || st.IsDir() {
		return "", fmt.Errorf("client config source must contain deps.edn: %s", resolvedSource)
	}
	return resolvedSource, nil
}

func ValidateSource(source string) error {
	_, err := ResolveSource(source)
	return err
}
