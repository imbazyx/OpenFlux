package main

import (
	"fmt"
	"os"

	"openflux/share"
)

func main() {
	docURL := os.Getenv("YANDEX_DOC_URL")
	if docURL == "" {
		fmt.Fprintln(os.Stderr, "YANDEX_DOC_URL not set")
		os.Exit(1)
	}
	keyFile := os.Getenv("YANDEX_KEY_FILE")
	if keyFile == "" {
		keyFile = "keys/secret-exit1-yandex"
	}
	secret, err := os.ReadFile(keyFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read key: %v\n", err)
		os.Exit(1)
	}
	c := share.Config{
		Name:    "yandex-exit",
		Secret:  string(secret),
		Context: docURL,
		Transports: []share.Transport{
			{Type: "vyandex", URL: docURL},
		},
	}
	link, err := share.Encode(c)
	if err != nil {
		fmt.Fprintf(os.Stderr, "encode: %v\n", err)
		os.Exit(1)
	}
	fmt.Println(link)
}
