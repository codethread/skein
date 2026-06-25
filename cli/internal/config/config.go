package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

const DefaultFormat = "human"
const ConfigFileName = "config.json"
const DefaultDBFileName = "tasks.sqlite"

var allowedKeys = map[string]bool{"source": true, "format": true}

type World struct {
	ConfigDir  string
	StateDir   string
	DataDir    string
	ConfigFile string
	DBPath     string
}

type Config struct {
	Source string `json:"source"`
	Format string `json:"format"`
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
	return world(filepath.Join(configHome, "atom"), filepath.Join(stateHome, "atom"), filepath.Join(dataHome, "atom")), nil
}

func ExplicitWorld(configDir string) (World, error) {
	if configDir == "" {
		return DefaultWorld()
	}
	abs, err := filepath.Abs(configDir)
	if err != nil {
		return World{}, err
	}
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
	if v, ok := raw["source"]; ok {
		if err := json.Unmarshal(v, &c.Source); err != nil {
			return Config{}, World{}, fmt.Errorf("client config source must be a string")
		}
	}
	if v, ok := raw["format"]; ok {
		if err := json.Unmarshal(v, &c.Format); err != nil {
			return Config{}, World{}, fmt.Errorf("client config format must be a string")
		}
	}
	if c.Format != "" && c.Format != "human" && c.Format != "json" {
		return Config{}, World{}, fmt.Errorf("unsupported format: %s", c.Format)
	}
	return c, w, nil
}

func ValidateSource(source string) error {
	if source == "" {
		return fmt.Errorf("client config source is required for daemon lifecycle commands; set source in %s", ConfigFileName)
	}
	if !filepath.IsAbs(source) {
		return fmt.Errorf("client config source must be an absolute path: %s", source)
	}
	st, err := os.Stat(source)
	if err != nil {
		return fmt.Errorf("client config source must be an existing directory: %s", source)
	}
	if !st.IsDir() {
		return fmt.Errorf("client config source must be an existing directory: %s", source)
	}
	if st, err := os.Stat(filepath.Join(source, "deps.edn")); err != nil || st.IsDir() {
		return fmt.Errorf("client config source must contain deps.edn: %s", source)
	}
	return nil
}
