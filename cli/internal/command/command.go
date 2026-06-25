package command

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"

	"atom-todo-cli/internal/client"
	"atom-todo-cli/internal/config"
)

type App struct{ Stdout, Stderr io.Writer }
type Options struct{ DB, Format, ClientConfig string }
type Caller interface {
	Call(string, map[string]any) (any, error)
}

var newClient = func(o Options) Caller { return client.New(client.Config{DB: o.DB, Format: o.Format}) }

func New(out, err io.Writer) *App { return &App{Stdout: out, Stderr: err} }

func (a *App) Run(args []string) error {
	if a.Stdout == nil {
		a.Stdout = os.Stdout
	}
	if a.Stderr == nil {
		a.Stderr = os.Stderr
	}
	opts, rest, err := Resolve(args)
	if err != nil {
		return err
	}
	if len(rest) == 0 {
		usage(a.Stdout)
		return nil
	}
	return a.runCommand(opts, rest)
}

func Resolve(args []string) (Options, []string, error) {
	opts, rest, err := parseGlobal(args)
	if err != nil {
		return opts, rest, err
	}
	cfg, err := config.Load(opts.ClientConfig)
	if err != nil {
		return opts, rest, err
	}
	if opts.DB == "" {
		opts.DB = cfg.DB
	}
	if opts.DB == "" {
		opts.DB = config.DefaultDB
	}
	if opts.Format == "" {
		opts.Format = cfg.Format
	}
	if opts.Format == "" {
		opts.Format = config.DefaultFormat
	}
	if opts.Format != "human" && opts.Format != "json" {
		return opts, rest, fmt.Errorf("unsupported format: %s", opts.Format)
	}
	return opts, rest, nil
}

func parseGlobal(args []string) (Options, []string, error) {
	var o Options
	for i := 0; i < len(args); i++ {
		s := args[i]
		if !strings.HasPrefix(s, "-") {
			return o, args[i:], nil
		}
		switch s {
		case "--db":
			i++
			if i >= len(args) {
				return o, nil, errors.New("--db requires a value")
			}
			o.DB = args[i]
		case "--format":
			i++
			if i >= len(args) {
				return o, nil, errors.New("--format requires a value")
			}
			o.Format = args[i]
		case "--client-config":
			i++
			if i >= len(args) {
				return o, nil, errors.New("--client-config requires a value")
			}
			o.ClientConfig = args[i]
		case "--where":
			return o, nil, errors.New("--where is not supported by the Go CLI; use --query")
		case "-h", "--help":
			return o, []string{"help"}, nil
		default:
			return o, nil, fmt.Errorf("unknown global option: %s", s)
		}
	}
	return o, nil, nil
}

func (a *App) runCommand(o Options, args []string) error {
	switch args[0] {
	case "help":
		usage(a.Stdout)
		return nil
	case "init":
		if err := noArgs(args[1:]); err != nil {
			return err
		}
		return a.call(o, "init", map[string]any{})
	case "add":
		return a.parseAdd(o, args[1:])
	case "update":
		return a.parseUpdate(o, args[1:])
	case "show":
		if len(args) != 2 {
			return errors.New("show requires exactly one id")
		}
		return a.call(o, "show", map[string]any{"id": args[1]})
	case "list":
		return parseQueryish("list", args[1:])
	case "ready":
		return parseQueryish("ready", args[1:])
	case "daemon":
		return parseDaemon(args[1:])
	default:
		return fmt.Errorf("unknown command: %s", args[0])
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `Usage: todo [--db path] [--client-config path] [--format human|json] <command> [args]

Commands:
  init
  add <title> [--status todo|done|failed|cancelled] [--attr key=value ...]
  update <id> [--title title] [--status todo|done|failed|cancelled] [--attr key=value ...] [--edge edge-type:to-id ...]
  show <id>
  list [--query name] [--param key=value ...]
  ready [--query name] [--param key=value ...]
  daemon start [--config trusted.edn]
  daemon status
  daemon stop`)
}

func (a *App) parseAdd(o Options, args []string) error {
	if len(args) == 0 {
		return errors.New("add requires a title")
	}
	fs := flag.NewFlagSet("add", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	status := fs.String("status", "todo", "")
	attrs := multiFlag{}
	fs.Var(&attrs, "attr", "")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("add received unexpected arguments")
	}
	if err := validStatus(*status); err != nil {
		return err
	}
	am, err := parseKV(attrs, "--attr")
	if err != nil {
		return err
	}
	return a.call(o, "add", map[string]any{"title": args[0], "status": *status, "attributes": am})
}
func (a *App) parseUpdate(o Options, args []string) error {
	if len(args) == 0 {
		return errors.New("update requires an id")
	}
	fs := flag.NewFlagSet("update", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	status := fs.String("status", "", "")
	title := fs.String("title", "", "")
	attrs := multiFlag{}
	edges := multiFlag{}
	fs.Var(&attrs, "attr", "")
	fs.Var(&edges, "edge", "")
	if err := fs.Parse(args[1:]); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("update received unexpected arguments")
	}
	if *status != "" {
		if err := validStatus(*status); err != nil {
			return err
		}
	}
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
	titleSet := false
	fs.Visit(func(f *flag.Flag) {
		if f.Name == "title" {
			titleSet = true
		}
	})
	var titleArg any
	if titleSet {
		titleArg = *title
	}
	var statusArg any
	if *status != "" {
		statusArg = *status
	}
	var attrArg any
	if len(attrs) > 0 {
		attrArg = am
	}
	return a.call(o, "update", map[string]any{"id": args[0], "title": titleArg, "status": statusArg, "attributes": attrArg, "edges": edgeRows})
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
	case "add":
		if m, ok := result.(map[string]any); ok {
			if id, ok := m["id"]; ok {
				_, err := fmt.Fprintln(a.Stdout, id)
				return err
			}
		}
	case "show":
		if result != nil {
			b, err := json.Marshal(result)
			if err != nil {
				return err
			}
			_, err = fmt.Fprintln(a.Stdout, string(b))
			return err
		}
	}
	return nil
}

func parseQueryish(op string, args []string) error {
	return fmt.Errorf("%s is not wired to the daemon socket yet", op)
}
func parseDaemon(args []string) error {
	if len(args) == 0 {
		return errors.New("daemon requires start, status, or stop")
	}
	switch args[0] {
	case "start":
		fs := flag.NewFlagSet("daemon start", flag.ContinueOnError)
		fs.SetOutput(io.Discard)
		fs.String("config", "", "")
		if err := fs.Parse(args[1:]); err != nil {
			return err
		}
		if fs.NArg() != 0 {
			return errors.New("daemon start received unexpected arguments")
		}
		return errors.New("daemon start is not wired to the launcher yet")
	case "status", "stop":
		if len(args) != 1 {
			return fmt.Errorf("daemon %s received unexpected arguments", args[0])
		}
		return fmt.Errorf("daemon %s is not wired to the daemon socket yet", args[0])
	default:
		return fmt.Errorf("unknown daemon command: %s", args[0])
	}
}
func noArgs(args []string) error {
	if len(args) > 0 {
		return errors.New("unexpected arguments")
	}
	return nil
}
func validStatus(s string) error {
	switch s {
	case "todo", "done", "failed", "cancelled":
		return nil
	}
	return fmt.Errorf("invalid status: %s", s)
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

type multiFlag []string

func (m *multiFlag) String() string     { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error { *m = append(*m, v); return nil }
