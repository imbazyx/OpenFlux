package main

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"

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
	host := os.Getenv("YANDEX_HOST")
	port := os.Getenv("YANDEX_PORT")
	if port == "" {
		port = "0"
	}
	portNum, _ := strconv.Atoi(port)

	c := share.Config{
		Name:      "yandex-exit",
		Negotiate: true,
		Secret:    strings.TrimSpace(string(secret)),
		Context:   docURL,
		Transports: []share.Transport{
			{Type: "vyandex", URL: docURL, Priority: 100},
		},
	}
	if host != "" && portNum > 0 {
		c.Transports = append(c.Transports, share.Transport{
			Type:     "direct",
			Dial:     net.JoinHostPort(host, strconv.Itoa(portNum)),
			Priority: 50,
		})
	}
	link, err := share.Encode(c)
	if err != nil {
		fmt.Fprintf(os.Stderr, "encode: %v\n", err)
		os.Exit(1)
	}
	fmt.Println(link)
}
