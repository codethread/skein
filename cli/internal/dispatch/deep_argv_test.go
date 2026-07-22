package dispatch

import (
	"reflect"
	"testing"
)

func TestDeepVerbArgvShipsVerbatim(t *testing.T) {
	var c capture
	harness(t, &c)

	_, stderr, code := runDispatch("", "spool", "catalog", "status", "--help")
	if code != 0 {
		t.Fatalf("exit %d stderr=%q", code, stderr)
	}
	if !c.called {
		t.Fatal("deep verb argv must transport to the weaver")
	}
	if c.envelope["name"] != "spool" {
		t.Fatalf("op name = %v, want spool", c.envelope["name"])
	}
	wantArgv := []any{"catalog", "status", "--help"}
	if !reflect.DeepEqual(toAnySlice(c.envelope["argv"]), wantArgv) {
		t.Fatalf("argv = %#v, want %#v", c.envelope["argv"], wantArgv)
	}
}
