package main

import (
	"fmt"
	"os"

	"openflux/share"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: decodeshare <link>")
		os.Exit(1)
	}
	c, err := share.Decode(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "decode: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("Name: %s\n", c.Name)
	fmt.Printf("Secret: %s\n", c.Secret)
	fmt.Printf("Context: %s\n", c.Context)
	for _, t := range c.Transports {
		fmt.Printf("Transport: type=%s url=%s priority=%d dial=%s\n", t.Type, t.URL, t.Priority, t.Dial)
	}
}
