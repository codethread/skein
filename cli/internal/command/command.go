package command

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"atom-todo-cli/internal/client"
	"atom-todo-cli/internal/config"
	"github.com/spf13/cobra"
)

type App struct{ Stdout, Stderr io.Writer }
type Options struct {
	Format, ConfigDir, StateDir, Source string
	ConfigDirExplicit                   bool
}
type ExitError struct {
	Code int
	Err  error
}

func (e *ExitError) Error() string { return e.Err.Error() }
func (e *ExitError) ExitCode() int { return e.Code }

type Caller interface {
	Call(string, map[string]any) (any, error)
}

const defaultInitCLJ = "(require '[atom.libs.alpha :as libs]\n         '[atom.graph.alpha :as graph]\n         '[atom.views.alpha :as views])\n(libs/sync!)\n"

var newClient = func(o Options) Caller {
	return client.New(client.Config{ConfigDir: o.ConfigDir, StateDir: o.StateDir, Format: o.Format})
}

func New(out, err io.Writer) *App { return &App{Stdout: out, Stderr: err} }

func (a *App) Run(args []string) error {
	if a.Stdout == nil {
		a.Stdout = os.Stdout
	}
	if a.Stderr == nil {
		a.Stderr = os.Stderr
	}
	cmd := a.rootCommand()
	cmd.SetArgs(args)
	cmd.SetOut(a.Stdout)
	cmd.SetErr(a.Stderr)
	return cmd.Execute()
}

func (a *App) rootCommand() *cobra.Command {
	o := Options{}
	root := &cobra.Command{
		Use:           "todo",
		Short:         "Manage Atom tasks through the local daemon",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.CompletionOptions.DisableDefaultCmd = true
	root.PersistentFlags().StringVar(&o.ConfigDir, "config-dir", "", "daemon world config directory")
	root.PersistentPreRun = func(cmd *cobra.Command, args []string) {
		o.ConfigDirExplicit = cmd.Flags().Changed("config-dir")
	}
	root.PersistentFlags().StringVar(&o.Format, "format", "", "output format: human or json")
	root.AddCommand(&cobra.Command{Use: "init", Short: "Bootstrap missing config-dir files; initialize task storage via the running daemon", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.initCommand(o)
	}})

	add := &cobra.Command{Use: "add <title>", Short: "Create a strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		active, _ := cmd.Flags().GetBool("active")
		ephemeral, _ := cmd.Flags().GetBool("ephemeral")
		attrs, _ := cmd.Flags().GetStringArray("attr")
		am, err := parseKV(attrs, "--attr")
		if err != nil {
			return err
		}
		payload := map[string]any{"title": args[0], "attributes": am}
		if cmd.Flags().Changed("active") {
			payload["active"] = active
		}
		if cmd.Flags().Changed("ephemeral") {
			payload["ephemeral"] = ephemeral
		}
		return a.withConfig(o, func(r Options) error {
			return a.call(r, "add", payload)
		})
	}}
	add.Flags().Bool("active", true, "strand active state")
	add.Flags().Bool("ephemeral", false, "delete strand when deactivated")
	add.Flags().StringArray("attr", nil, "string attribute key=value (repeatable)")
	root.AddCommand(add)

	update := &cobra.Command{Use: "update <id>", Short: "Update a strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		active, _ := cmd.Flags().GetBool("active")
		ephemeral, _ := cmd.Flags().GetBool("ephemeral")
		title, _ := cmd.Flags().GetString("title")
		attrs, _ := cmd.Flags().GetStringArray("attr")
		edges, _ := cmd.Flags().GetStringArray("edge")
		am, err := parseKV(attrs, "--attr")
		if err != nil {
			return err
		}
		edgeRows := make([]map[string]any, 0, len(edges))
		for _, v := range edges {
			left, right, ok := strings.Cut(v, ":")
			if !ok || left == "" || right == "" {
				return fmt.Errorf("malformed --edge: %s", v)
			}
			edgeRows = append(edgeRows, map[string]any{"type": left, "to": right})
		}
		var titleArg any
		if cmd.Flags().Changed("title") {
			titleArg = title
		}
		var activeArg any
		if cmd.Flags().Changed("active") {
			activeArg = active
		}
		var ephemeralArg any
		if cmd.Flags().Changed("ephemeral") {
			ephemeralArg = ephemeral
		}
		var attrArg any
		if len(attrs) > 0 {
			attrArg = am
		}
		return a.withConfig(o, func(r Options) error {
			return a.call(r, "update", map[string]any{"id": args[0], "title": titleArg, "active": activeArg, "ephemeral": ephemeralArg, "attributes": attrArg, "edges": edgeRows})
		})
	}}
	update.Flags().String("title", "", "new strand title")
	update.Flags().Bool("active", true, "strand active state")
	update.Flags().Bool("ephemeral", false, "delete strand when deactivated")
	update.Flags().StringArray("attr", nil, "string attribute key=value (repeatable)")
	update.Flags().StringArray("edge", nil, "outgoing edge edge-type:to-id (repeatable)")
	root.AddCommand(update)

	root.AddCommand(&cobra.Command{Use: "show <id>", Short: "Show one task", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "show", map[string]any{"id": args[0]}) })
	}})
	root.AddCommand(a.queryCommand(&o, "list", "List tasks"))
	root.AddCommand(a.queryCommand(&o, "ready", "List ready tasks"))

	daemon := &cobra.Command{Use: "daemon", Short: "Manage the local daemon"}
	start := &cobra.Command{Use: "start", Short: "Start the daemon in the foreground for the selected config-dir world", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.launchDaemon(r) })
	}}
	daemon.AddCommand(start)
	daemon.AddCommand(&cobra.Command{Use: "status", Short: "Show daemon status", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "status", map[string]any{}) })
	}})
	daemon.AddCommand(&cobra.Command{Use: "stop", Short: "Stop the daemon", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "stop", map[string]any{}) })
	}})
	repl := &cobra.Command{Use: "repl", Short: "Start a connected Clojure helper REPL", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		stdin, _ := cmd.Flags().GetBool("stdin")
		return a.withConfig(o, func(r Options) error { return a.launchRepl(r, stdin) })
	}}
	repl.Flags().Bool("stdin", false, "read Clojure forms from stdin, print one result per top-level form, then exit")
	daemon.AddCommand(repl)
	root.AddCommand(daemon)
	return root
}

func (a *App) queryCommand(o *Options, name, short string) *cobra.Command {
	cmd := &cobra.Command{Use: name + " [--query name] [--param key=value ...]", Short: short, Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		where, _ := cmd.Flags().GetString("where")
		if cmd.Flags().Changed("where") || where != "" {
			return errors.New("--where is not supported by the Go CLI; use --query")
		}
		query, _ := cmd.Flags().GetString("query")
		params, _ := cmd.Flags().GetStringArray("param")
		pm, err := parseKV(params, "--param")
		if err != nil {
			return err
		}
		if query == "" {
			if cmd.Flags().Changed("query") {
				return errors.New("--query requires a non-empty name")
			}
			if len(params) > 0 {
				return errors.New("--param requires --query")
			}
			return a.withConfig(*o, func(r Options) error { return a.call(r, name, map[string]any{}) })
		}
		return a.withConfig(*o, func(r Options) error { return a.call(r, name+"-query", map[string]any{"query": query, "params": pm}) })
	}}
	cmd.Flags().String("query", "", "daemon-registered named query")
	cmd.Flags().StringArray("param", nil, "query parameter key=value (repeatable)")
	cmd.Flags().String("where", "", "unsupported; use --query")
	_ = cmd.Flags().MarkHidden("where")
	return cmd
}

func (a *App) withConfig(o Options, f func(Options) error) error {
	opts, err := resolveOptions(o)
	if err != nil {
		return err
	}
	return f(opts)
}

func resolveOptions(o Options) (Options, error) {
	cfg, world, err := config.Load(o.ConfigDir)
	if err != nil {
		return o, err
	}
	o.Source = cfg.Source
	o.ConfigDir = world.ConfigDir
	o.StateDir = world.StateDir
	if o.Format == "" {
		o.Format = cfg.Format
	}
	if o.Format == "" {
		o.Format = config.DefaultFormat
	}
	if o.Format != "human" && o.Format != "json" {
		return o, fmt.Errorf("unsupported format: %s", o.Format)
	}
	return o, nil
}

func (a *App) call(o Options, op string, args map[string]any) error {
	result, err := newClient(o).Call(op, args)
	if err != nil {
		return err
	}
	if o.Format == "json" {
		b, err := json.Marshal(result)
		if err != nil {
			return err
		}
		_, err = fmt.Fprintln(a.Stdout, string(b))
		return err
	}
	switch op {
	case "status", "stop", "show":
		return a.writeHumanJSON(result)
	case "add":
		if m, ok := result.(map[string]any); ok {
			if id, ok := m["id"]; ok {
				_, err := fmt.Fprintln(a.Stdout, id)
				return err
			}
		}
	case "list", "ready", "list-query", "ready-query":
		return a.writeHumanRows(result)
	}
	return nil
}
func (a *App) writeHumanJSON(result any) error {
	if result == nil {
		return nil
	}
	b, err := json.Marshal(result)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(a.Stdout, string(b))
	return err
}
func (a *App) writeHumanRows(result any) error {
	rows, ok := result.([]any)
	if !ok {
		return a.writeHumanJSON(result)
	}
	if len(rows) == 0 {
		_, err := fmt.Fprintln(a.Stdout, "(no rows)")
		return err
	}
	for _, row := range rows {
		if err := a.writeHumanJSON(row); err != nil {
			return err
		}
	}
	return nil
}

func daemonArgs(o Options) []string {
	args := []string{"-M:skein"}
	if o.ConfigDirExplicit {
		args = append(args, "--config-dir", o.ConfigDir)
	}
	return append(args, "daemon", "start")
}

func replArgs(o Options, stdin bool) []string {
	args := []string{"-M", "-m", "skein.repl"}
	if stdin {
		args = append(args, "--stdin")
	}
	if o.ConfigDirExplicit {
		args = append(args, o.ConfigDir)
	}
	return args
}

func runProcess(source string, args []string, in io.Reader, out, errOut io.Writer) error {
	cmd := exec.Command("clojure", args...)
	cmd.Dir = source
	cmd.Stdin = in
	cmd.Stdout = out
	cmd.Stderr = errOut
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return &ExitError{Code: exit.ExitCode(), Err: err}
		}
		return err
	}
	return nil
}

var runDaemonProcess = func(o Options, out, errOut io.Writer) error {
	return runProcess(o.Source, daemonArgs(o), os.Stdin, out, errOut)
}

var runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
	return runProcess(o.Source, replArgs(o, stdin), in, out, errOut)
}

var runGitInit = func(configDir string) error {
	cmd := exec.Command("git", "init")
	cmd.Dir = configDir
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return &ExitError{Code: exit.ExitCode(), Err: err}
		}
		return err
	}
	return nil
}

func (a *App) initCommand(o Options) error {
	if err := a.bootstrapConfigDir(o); err != nil {
		return err
	}
	if err := a.withConfig(o, func(r Options) error {
		return a.call(r, "init", map[string]any{})
	}); err != nil {
		return err
	}
	return nil
}

func (a *App) bootstrapConfigDir(o Options) error {
	world, err := config.ExplicitWorld(o.ConfigDir)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(world.ConfigDir, 0o755); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(world.ConfigDir, "libs"), 0o755); err != nil {
		return err
	}
	if _, err := os.Stat(world.ConfigFile); os.IsNotExist(err) {
		cwd, err := os.Getwd()
		if err != nil {
			return err
		}
		absSource, err := filepath.Abs(cwd)
		if err != nil {
			return err
		}
		if err := config.ValidateSource(absSource); err != nil {
			return err
		}
		cfg := config.Config{ConfigFormat: "alpha", Source: absSource, Format: config.DefaultFormat}
		data, err := json.Marshal(cfg)
		if err != nil {
			return err
		}
		if err := os.WriteFile(world.ConfigFile, append(data, '\n'), 0o644); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(world.ConfigDir, "libs.edn")); os.IsNotExist(err) {
		if err := os.WriteFile(filepath.Join(world.ConfigDir, "libs.edn"), []byte("{:libs {}}\n"), 0o644); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(world.ConfigDir, "init.clj")); os.IsNotExist(err) {
		if err := os.WriteFile(filepath.Join(world.ConfigDir, "init.clj"), []byte(defaultInitCLJ), 0o644); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(world.ConfigDir, ".git")); os.IsNotExist(err) {
		if err := runGitInit(world.ConfigDir); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	if _, _, err := config.Load(world.ConfigDir); err != nil {
		return err
	}
	return nil
}

func (a *App) launchDaemon(o Options) error {
	source, err := config.ResolveSource(o.Source)
	if err != nil {
		return err
	}
	o.Source = source
	return runDaemonProcess(o, a.Stdout, a.Stderr)
}

func (a *App) launchRepl(o Options, stdin bool) error {
	source, err := config.ResolveSource(o.Source)
	if err != nil {
		return err
	}
	o.Source = source
	if _, err := newClient(o).Call("status", map[string]any{}); err != nil {
		return err
	}
	return runReplProcess(o, stdin, os.Stdin, a.Stdout, a.Stderr)
}
func parseKV(vals []string, name string) (map[string]any, error) {
	m := map[string]any{}
	for _, s := range vals {
		k, v, ok := strings.Cut(s, "=")
		if !ok || k == "" {
			return nil, fmt.Errorf("malformed %s: %s", name, s)
		}
		m[k] = v
	}
	return m, nil
}
