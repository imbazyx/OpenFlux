package main

import (
	"fmt"
	"net"
	"strconv"

	"openflux/transport"
	"openflux/transport/control"
	"openflux/transport/cupsonline"
	"openflux/transport/mailru"
	"openflux/transport/manager"
	"openflux/transport/oneme"
	"openflux/transport/yandex"
	"openflux/tunnel/l3"
)

// okDialTarget refuses a dial address that would make the node talk to itself.
// A host name is allowed: it is resolved by the operator's own resolver, and
// once it becomes an address the exit filter applies to tunnel traffic anyway.
func okDialTarget(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host).To4()
	if ip == nil {
		return true
	}
	var dst [4]byte
	copy(dst[:], ip)
	return !l3.BlockedDestination(dst, [4]byte{})
}

// yandexCookiesFile is --yandex-cookies-file: a Netscape cookies.txt with
// a Yandex login that every vyandex transport starts with.
var yandexCookiesFile string

func newVolgaTransport(docURL string, cfg transport.TransportConfig) (transport.Transport, error) {
	t := yandex.NewYandexVolgaTransport(docURL, cfg)
	if yandexCookiesFile != "" {
		if err := t.LoadCookieFile(yandexCookiesFile); err != nil {
			return nil, err
		}
	}
	return t, nil
}

// transportFactory builds a raw transport from a control.TransportConfig.
// It is the single place that knows every transport package. main.go passes
// it into manager.New, and manager calls it whenever the peer asks the exit
// to bring up an additional transport at runtime.
func transportFactory(baseCfg transport.TransportConfig) manager.Factory {
	return func(cfg *control.TransportConfig) (transport.Transport, error) {
		if cfg == nil {
			return nil, fmt.Errorf("factory: nil config")
		}
		switch cfg.Type {
		case "yandex":
			return yandex.NewYandexDocsTransport(cfg.URL, baseCfg), nil
		case "vyandex":
			return newVolgaTransport(cfg.URL, baseCfg)
		case "boards":
			return yandex.NewBoardsTransport(cfg.URL, baseCfg), nil
		case "mailru":
			return mailru.NewMailruDocsTransport(cfg.URL, baseCfg), nil
		case "cupsonline":
			return cupsonline.NewCupsonlineTransport(cfg.URL, baseCfg, false), nil
		case "oneme":
			token, _ := cfg.Params["token"].(string)
			uidStr, _ := cfg.Params["uid"].(string)
			uid, _ := strconv.ParseInt(uidStr, 10, 64)
			exit, _ := cfg.Params["exit"].(bool)
			return oneme.NewOneMeTransport(exit, token, uid, baseCfg), nil
		case "direct":
			dcfg := transport.DefaultDirectConfig()
			// Which address to bind and whether to bind at all are the operator's
			// decisions, not the peer's. A peer that can reach the transport must
			// not be able to make a root VDS listen on a port of its choosing
			// outside every firewall rule the installer wrote, and must not be
			// able to point the dial side at the node's own loopback, which is
			// the same pivot the exit filter closes for tunnel traffic.
			//
			// Nothing legitimate is lost: the app and the CLI set these by
			// calling NewDirectTransport directly (mobile.go:214) and never come
			// through here. This path exists for peer-requested carriers.
			if v, ok := cfg.Params["dial"].(string); ok && okDialTarget(v) {
				dcfg.DialAddr = v
			}
			return transport.NewDirectTransport(baseCfg, dcfg), nil
		default:
			return nil, fmt.Errorf("factory: unknown transport type %q", cfg.Type)
		}
	}
}
