package main

import (
	"os"

	"skein-strand-cli/internal/dispatch"
)

func main() {
	os.Exit(dispatch.Run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}
