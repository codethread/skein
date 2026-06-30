package command

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

type App struct {
	Stdout, Stderr io.Writer
	Stdin          io.Reader
}
type Options struct {
	ConfigDir, StateDir, DataDir, Source string
	ConfigDirExplicit                    bool
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

type millForwardingClient struct{ world client.MillWorldRequest }

func (c millForwardingClient) Call(operation string, payload map[string]any) (any, error) {
	return client.MillCallPayload(operation, c.world, payload)
}

var newClient = func(o Options) Caller {
	cwd, err := os.Getwd()
	if err != nil {
		return errorCaller{err: err}
	}
	return millForwardingClient{world: client.MillWorldRequest{CWD: cwd, ConfigDir: o.ConfigDir}}
}

type errorCaller struct{ err error }

func (c errorCaller) Call(string, map[string]any) (any, error) { return nil, c.err }

func New(out, err io.Writer) *App { return &App{Stdout: out, Stderr: err} }

func (a *App) Run(args []string) error {
	if a.Stdout == nil {
		a.Stdout = os.Stdout
	}
	if a.Stderr == nil {
		a.Stderr = os.Stderr
	}
	if a.Stdin == nil {
		a.Stdin = os.Stdin
	}
	if handled, err := a.runOpPassThrough(args); handled {
		return err
	}
	cmd := a.rootCommand()
	cmd.SetArgs(args)
	cmd.SetOut(a.Stdout)
	cmd.SetErr(a.Stderr)
	return cmd.Execute()
}

func (a *App) runOpPassThrough(args []string) (bool, error) {
	o := Options{}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		switch {
		case arg == "--config-dir":
			if i+1 >= len(args) {
				return true, errors.New("flag needs an argument: --config-dir")
			}
			o.ConfigDir = args[i+1]
			o.ConfigDirExplicit = true
			i++
		case strings.HasPrefix(arg, "--config-dir="):
			o.ConfigDir = strings.TrimPrefix(arg, "--config-dir=")
			o.ConfigDirExplicit = true
		case arg == "op":
			opArgs := args[i+1:]
			if len(opArgs) == 0 || opArgs[0] == "--help" || opArgs[0] == "-h" {
				return false, nil
			}
			if strings.TrimSpace(opArgs[0]) == "" {
				return true, errors.New("op requires a non-empty name")
			}
			return true, a.withConfig(o, func(r Options) error {
				return a.call(r, "op", map[string]any{"name": opArgs[0], "args": opArgs[1:]})
			})
		default:
			return false, nil
		}
	}
	return false, nil
}

func (a *App) rootCommand() *cobra.Command {
	o := Options{}
	root := &cobra.Command{
		Use:           "strand",
		Short:         "Manage Skein strands through the local weaver",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	root.CompletionOptions.DisableDefaultCmd = true
	root.PersistentFlags().StringVar(&o.ConfigDir, "config-dir", "", "weaver world config directory")
	root.PersistentPreRun = func(cmd *cobra.Command, args []string) {
		o.ConfigDirExplicit = cmd.Flags().Changed("config-dir")
	}
	initCmd := &cobra.Command{Use: "init", Short: "Bootstrap missing selected config workspace files through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.initCommand(o)
	}}
	root.AddCommand(initCmd)

	add := &cobra.Command{Use: "add <title>", Short: "Create a strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		state, err := stateFlag(cmd, false)
		if err != nil {
			return err
		}
		am, err := a.addAttributes(cmd)
		if err != nil {
			return err
		}
		edgeRows, err := parseEdges(cmd)
		if err != nil {
			return err
		}
		payload := map[string]any{"title": args[0], "attributes": am}
		if cmd.Flags().Changed("state") {
			payload["state"] = state
		}
		if len(edgeRows) > 0 {
			payload["edges"] = edgeRows
		}
		return a.withConfig(o, func(r Options) error {
			return a.call(r, "add", payload)
		})
	}}
	add.Flags().String("state", "active", "strand lifecycle state: active or closed")
	add.Flags().StringArray("attr", nil, "string attribute key=value (repeatable; highest priority)")
	add.Flags().StringArray("attr-file", nil, "string attribute key=path read from file contents (repeatable)")
	add.Flags().StringArray("attr-stdin", nil, "attribute key whose string value is read from stdin (max once)")
	add.Flags().Bool("attributes-stdin", false, "read one JSON object from stdin as bulk attributes")
	add.Flags().StringArray("edge", nil, "outgoing edge edge-type:to-id (repeatable)")
	root.AddCommand(add)

	update := &cobra.Command{Use: "update <id>", Short: "Update a strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		state, err := stateFlag(cmd, false)
		if err != nil {
			return err
		}
		title, _ := cmd.Flags().GetString("title")
		attrs, _ := cmd.Flags().GetStringArray("attr")
		am, err := parseKV(attrs, "--attr")
		if err != nil {
			return err
		}
		edgeRows, err := parseEdges(cmd)
		if err != nil {
			return err
		}
		var titleArg any
		if cmd.Flags().Changed("title") {
			titleArg = title
		}
		var stateArg any
		if cmd.Flags().Changed("state") {
			stateArg = state
		}
		var attrArg any
		if len(attrs) > 0 {
			attrArg = am
		}
		return a.withConfig(o, func(r Options) error {
			return a.call(r, "update", map[string]any{"id": args[0], "title": titleArg, "state": stateArg, "attributes": attrArg, "edges": edgeRows})
		})
	}}
	update.Flags().String("title", "", "new strand title")
	update.Flags().String("state", "active", "strand lifecycle state: active or closed")
	update.Flags().StringArray("attr", nil, "string attribute key=value (repeatable)")
	update.Flags().StringArray("edge", nil, "outgoing edge edge-type:to-id (repeatable)")
	root.AddCommand(update)

	root.AddCommand(&cobra.Command{Use: "show <id>", Short: "Show one strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "show", map[string]any{"id": args[0]}) })
	}})
	root.AddCommand(&cobra.Command{
		Use:   "supersede <old-id> <replacement-id>",
		Short: "Replace one strand with another through the weaver supersession transaction",
		Long:  "Replace one strand with another through the weaver supersession transaction. Stores replacement --supersedes--> old, marks old replaced, and rewires dependents; use update --edge only for raw relation writes.",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return a.withConfig(o, func(r Options) error {
				return a.call(r, "supersede", map[string]any{"old_id": args[0], "replacement_id": args[1]})
			})
		},
	})
	root.AddCommand(&cobra.Command{Use: "burn <id>", Short: "Burn one strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "burn", map[string]any{"id": args[0]}) })
	}})
	root.AddCommand(a.queryCommand(&o, "list", "List strands"))
	root.AddCommand(a.queryCommand(&o, "ready", "List ready strands"))

	graph := &cobra.Command{Use: "graph", Short: "Inspect strand graph topology"}
	graph.AddCommand(a.graphSubgraphCommand(&o))
	root.AddCommand(graph)

	weave := &cobra.Command{Use: "weave --pattern <name>", Short: "Create strands through a weaver-registered pattern", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		pattern, _ := cmd.Flags().GetString("pattern")
		if strings.TrimSpace(pattern) == "" {
			return errors.New("weave requires --pattern <name>")
		}
		input, err := readOneJSONValue(a.Stdin)
		if err != nil {
			return err
		}
		return a.withConfig(o, func(r Options) error { return a.call(r, "weave", map[string]any{"pattern": pattern, "input": input}) })
	}}
	weave.Flags().String("pattern", "", "weaver-registered pattern name")
	root.AddCommand(weave)

	pattern := &cobra.Command{Use: "pattern", Short: "Inspect weaver-registered patterns"}
	pattern.AddCommand(&cobra.Command{Use: "list", Short: "List registered patterns", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.withConfig(o, func(r Options) error { return a.call(r, "pattern-list", map[string]any{}) })
	}})
	pattern.AddCommand(&cobra.Command{Use: "explain <name>", Short: "Explain a pattern input contract", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		if strings.TrimSpace(args[0]) == "" {
			return errors.New("pattern explain requires a non-empty name")
		}
		return a.withConfig(o, func(r Options) error { return a.call(r, "pattern-explain", map[string]any{"pattern": args[0]}) })
	}})
	root.AddCommand(pattern)

	op := &cobra.Command{
		Use:   "op <name> [args...]",
		Short: "Invoke a weaver-registered operation",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(args[0]) == "" {
				return errors.New("op requires a non-empty name")
			}
			return a.withConfig(o, func(r Options) error {
				return a.call(r, "op", map[string]any{"name": args[0], "args": args[1:]})
			})
		},
	}
	root.AddCommand(op)

	weaver := &cobra.Command{Use: "weaver", Short: "Manage the local weaver"}
	start := &cobra.Command{Use: "start", Short: "Start the selected world's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.millWeaverCommand(o, "weaver-start")
	}}
	weaver.AddCommand(start)
	weaver.AddCommand(&cobra.Command{Use: "status", Short: "Show selected-world weaver status through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.millWeaverCommand(o, "weaver-status")
	}})
	weaver.AddCommand(&cobra.Command{Use: "stop", Short: "Stop the selected world's weaver through the local mill", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		return a.millWeaverCommand(o, "weaver-stop")
	}})
	repl := &cobra.Command{Use: "repl", Short: "Start a connected Clojure helper REPL", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, args []string) error {
		stdin, _ := cmd.Flags().GetBool("stdin")
		return a.withResolvedConfig(o, func(r Options) error { return a.launchRepl(r, stdin) })
	}}
	repl.Flags().Bool("stdin", false, "read Clojure forms from stdin, print one result per top-level form, then exit")
	weaver.AddCommand(repl)
	root.AddCommand(weaver)
	return root
}

func (a *App) graphSubgraphCommand(o *Options) *cobra.Command {
	cmd := &cobra.Command{Use: "subgraph <root-id>", Short: "Return a relation-scoped subgraph for a root strand", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
		relation, _ := cmd.Flags().GetString("relation")
		payload := map[string]any{"root_ids": []string{args[0]}}
		if cmd.Flags().Changed("relation") {
			payload["type"] = relation
		}
		return a.withConfig(*o, func(r Options) error { return a.call(r, "subgraph", payload) })
	}}
	cmd.Flags().String("relation", "", "declared acyclic relation type (defaults to parent-of)")
	return cmd
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
		payload := map[string]any{}
		if name == "list" && cmd.Flags().Changed("state") {
			state, err := stateFlag(cmd, true)
			if err != nil {
				return err
			}
			payload["state"] = state
		}
		if query == "" {
			if cmd.Flags().Changed("query") {
				return errors.New("--query requires a non-empty name")
			}
			if len(params) > 0 {
				return errors.New("--param requires --query")
			}
			return a.withConfig(*o, func(r Options) error { return a.call(r, name, payload) })
		}
		payload["query"] = query
		payload["params"] = pm
		return a.withConfig(*o, func(r Options) error { return a.call(r, name+"-query", payload) })
	}}
	cmd.Flags().String("query", "", "weaver-registered named query")
	cmd.Flags().StringArray("param", nil, "query parameter key=value (repeatable)")
	cmd.Flags().String("where", "", "unsupported; use --query")
	if name == "list" {
		cmd.Flags().String("state", "", "filter strands by lifecycle state: active, closed, or replaced")
	}
	_ = cmd.Flags().MarkHidden("where")
	return cmd
}

func (a *App) withConfig(o Options, f func(Options) error) error {
	return f(o)
}

func (a *App) withResolvedConfig(o Options, f func(Options) error) error {
	opts, err := resolveOptions(o)
	if err != nil {
		return err
	}
	return f(opts)
}

func resolveOptions(o Options) (Options, error) {
	_, world, err := config.Load(o.ConfigDir)
	if err != nil {
		return o, err
	}
	o.ConfigDir = world.ConfigDir
	o.StateDir = world.StateDir
	o.DataDir = world.DataDir
	return o, nil
}

func (a *App) call(o Options, op string, args map[string]any) error {
	result, err := newClient(o).Call(op, args)
	if err != nil {
		return err
	}
	b, err := json.Marshal(result)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(a.Stdout, string(b))
	return err
}
func weaverArgs(o Options) []string {
	args := []string{"-M:skein", "-m", "skein.weaver.runtime"}
	if o.ConfigDir != "" {
		args = append(args, "--config-dir", o.ConfigDir)
	}
	if o.StateDir != "" {
		args = append(args, "--state-dir", o.StateDir)
	}
	if o.DataDir != "" {
		args = append(args, "--data-dir", o.DataDir)
	}
	return args
}

func replArgs(o Options, stdin bool) []string {
	args := []string{"-M", "-m", "skein.repl"}
	if stdin {
		args = append(args, "--stdin")
	}
	if o.ConfigDir != "" {
		args = append(args, o.ConfigDir)
	}
	if o.StateDir != "" {
		args = append(args, o.StateDir)
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

var runWeaverProcess = func(o Options, out, errOut io.Writer) error {
	return runProcess(o.Source, weaverArgs(o), os.Stdin, out, errOut)
}

var runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
	return runProcess(o.Source, replArgs(o, stdin), in, out, errOut)
}

var millCall = client.MillCall

func (a *App) initCommand(o Options) error {
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	_, err = millCall("init", client.MillWorldRequest{CWD: cwd, ConfigDir: o.ConfigDir})
	return err
}

func (a *App) millWeaverCommand(o Options, operation string) error {
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	result, err := millCall(operation, client.MillWorldRequest{CWD: cwd, ConfigDir: o.ConfigDir})
	if err != nil {
		return err
	}
	b, err := json.Marshal(result)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(a.Stdout, string(b))
	return err
}

func incompleteDiscoveredWorldError(o Options) error {
	return fmt.Errorf("selected config-dir %s is missing local source config; source is resolved by mill from SKEIN_SOURCE, installed build source, or canonical Skein checkout cwd", o.ConfigDir)
}

func (a *App) launchWeaver(o Options) error {
	source, err := config.ResolveSource(o.Source)
	if err != nil {
		if o.Source == "" {
			return incompleteDiscoveredWorldError(o)
		}
		return err
	}
	o.Source = source
	return runWeaverProcess(o, a.Stdout, a.Stderr)
}

func (a *App) launchRepl(o Options, stdin bool) error {
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	result, err := millCall("weaver-repl-context", client.MillWorldRequest{CWD: cwd, ConfigDir: o.ConfigDir})
	if err != nil {
		return err
	}
	status, ok := result.(map[string]any)
	if !ok {
		return errors.New("malformed mill weaver-repl-context response: expected object")
	}
	state, ok := status["state"].(string)
	if !ok || state == "" {
		return errors.New("malformed mill weaver-repl-context response: missing state")
	}
	if state != "running" {
		return fmt.Errorf("no running weaver for selected world (state: %s); start one with: strand weaver start", state)
	}
	stateDir, ok := status["state_dir"].(string)
	if !ok || stateDir == "" {
		return errors.New("malformed mill weaver-repl-context response: missing state_dir")
	}
	source, ok := status["source"].(string)
	if !ok || source == "" {
		return errors.New("malformed mill weaver-repl-context response: missing source")
	}
	o.Source = source
	o.StateDir = stateDir
	return runReplProcess(o, stdin, a.Stdin, a.Stdout, a.Stderr)
}

func stateFlag(cmd *cobra.Command, allowReplaced bool) (string, error) {
	value, err := cmd.Flags().GetString("state")
	if err != nil {
		return "", err
	}
	switch value {
	case "active", "closed":
		return value, nil
	case "replaced":
		if allowReplaced {
			return value, nil
		}
	}
	allowed := "active, closed"
	if allowReplaced {
		allowed = "active, closed, replaced"
	}
	return "", fmt.Errorf("invalid --state: must be one of %s", allowed)
}

func readOneJSONValue(r io.Reader) (any, error) {
	return readOneJSONValueNamed(r, "weave requires one JSON value on stdin")
}

func readOneJSONValueNamed(r io.Reader, emptyMessage string) (any, error) {
	dec := json.NewDecoder(r)
	var value any
	if err := dec.Decode(&value); err != nil {
		if errors.Is(err, io.EOF) {
			return nil, errors.New(emptyMessage)
		}
		return nil, fmt.Errorf("malformed JSON stdin: %w", err)
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		if err == nil {
			return nil, errors.New("stdin must contain exactly one JSON value")
		}
		return nil, fmt.Errorf("malformed trailing JSON stdin: %w", err)
	}
	return value, nil
}

func (a *App) addAttributes(cmd *cobra.Command) (map[string]any, error) {
	attrs, _ := cmd.Flags().GetStringArray("attr")
	attrFiles, _ := cmd.Flags().GetStringArray("attr-file")
	attrStdinVals, _ := cmd.Flags().GetStringArray("attr-stdin")
	attributesStdin, _ := cmd.Flags().GetBool("attributes-stdin")
	if len(attrStdinVals) > 1 {
		return nil, errors.New("--attr-stdin may appear at most once")
	}
	attrStdinChanged := cmd.Flags().Changed("attr-stdin")
	attrStdin := ""
	if len(attrStdinVals) == 1 {
		attrStdin = attrStdinVals[0]
	}
	if attrStdinChanged && attrStdin == "" {
		return nil, errors.New("--attr-stdin requires a non-empty key")
	}
	if attrStdinChanged && attributesStdin {
		return nil, errors.New("--attr-stdin and --attributes-stdin cannot be used together")
	}

	merged := map[string]any{}
	if attributesStdin {
		value, err := readOneJSONValueNamed(a.Stdin, "--attributes-stdin requires one JSON object on stdin")
		if err != nil {
			return nil, err
		}
		obj, ok := value.(map[string]any)
		if !ok {
			return nil, errors.New("--attributes-stdin requires a JSON object")
		}
		for k, v := range obj {
			if k == "" {
				return nil, errors.New("--attributes-stdin contains a blank attribute key")
			}
			merged[k] = v
		}
	}

	fileAttrs, err := parseKVNoDuplicates(attrFiles, "--attr-file")
	if err != nil {
		return nil, err
	}
	for k, path := range fileAttrs {
		b, err := os.ReadFile(path.(string))
		if err != nil {
			return nil, fmt.Errorf("failed to read --attr-file %s: %w", k, err)
		}
		merged[k] = string(b)
	}
	if attrStdinChanged {
		if _, exists := fileAttrs[attrStdin]; exists {
			return nil, fmt.Errorf("duplicate attribute key in file/stdin sources: %s", attrStdin)
		}
		b, err := io.ReadAll(a.Stdin)
		if err != nil {
			return nil, fmt.Errorf("failed to read --attr-stdin: %w", err)
		}
		merged[attrStdin] = string(b)
	}

	flagAttrs, err := parseKVNoDuplicates(attrs, "--attr")
	if err != nil {
		return nil, err
	}
	for k, v := range flagAttrs {
		merged[k] = v
	}
	return merged, nil
}

func parseEdges(cmd *cobra.Command) ([]map[string]any, error) {
	edges, _ := cmd.Flags().GetStringArray("edge")
	edgeRows := make([]map[string]any, 0, len(edges))
	for _, v := range edges {
		left, right, ok := strings.Cut(v, ":")
		if !ok || left == "" || right == "" {
			return nil, fmt.Errorf("malformed --edge: %s", v)
		}
		edgeRows = append(edgeRows, map[string]any{"type": left, "to": right})
	}
	return edgeRows, nil
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

func parseKVNoDuplicates(vals []string, name string) (map[string]any, error) {
	m := map[string]any{}
	for _, s := range vals {
		k, v, ok := strings.Cut(s, "=")
		if !ok || k == "" {
			return nil, fmt.Errorf("malformed %s: %s", name, s)
		}
		if _, exists := m[k]; exists {
			return nil, fmt.Errorf("duplicate attribute key in %s: %s", name, k)
		}
		m[k] = v
	}
	return m, nil
}
