package main

import (
	"fmt"
	"strconv"

	"openflux/transport"
	"openflux/transport/control"
	"openflux/transport/cupsonline"
	"openflux/transport/mailru"
	"openflux/transport/manager"
	"openflux/transport/oneme"
	"openflux/transport/yandex"
)

// yandexCookiesFile is --yandex-cookies-file: a Netscape cookies.txt with
// a Yandex login that every vyandex transport starts with.
var yandexCookiesFile string

// mailruCookiesFile is --mailru-cookies-file: a Netscape cookies.txt with a
// Mail.ru session that every mailru transport starts with.
var mailruCookiesFile string

func newVolgaTransport(docURL string, cfg transport.TransportConfig) (transport.Transport, error) {
	t := yandex.NewYandexVolgaTransport(docURL, cfg)
	if yandexCookiesFile != "" {
		if err := t.LoadCookieFile(yandexCookiesFile); err != nil {
			return nil, err
		}
	}
	return t, nil
}

// newMailruTransport builds the mailru transport and loads the Mail.ru
// session from --mailru-cookies-file, if the owner gave one. A document whose
// share settings refuse anonymous editing needs that session: the editor API
// answers 401 "public is available with auth only" without it.
func newMailruTransport(docURL string, cfg transport.TransportConfig) (transport.Transport, error) {
	t := mailru.NewMailruDocsTransport(docURL, cfg)
	if mailruCookiesFile != "" {
		if err := t.LoadCookieFile(mailruCookiesFile); err != nil {
			return nil, err
		}
	}
	return t, nil
}

// transportFactory builds a raw transport from a control.TransportConfig.
// It is the single place that knows every transport package. main.go passes
// it into manager.New, and manager calls it whenever the peer asks the exit
// to bring up an additional transport at runtime.
//
// isExit is this process's role, and it is a parameter rather than a constant
// because of Cups.online: a client has to create and enter rooms, an exit has to
// join the rooms its client is in. Building it as a client everywhere made a
// node's dynamically requested carrier create four rooms of its own and wait in
// them, in rooms the client never joined - the channel then has nobody on the
// other end.
func transportFactory(baseCfg transport.TransportConfig, isExit bool) manager.Factory {
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
			return newMailruTransport(cfg.URL, baseCfg)
		case "cupsonline":
			return cupsonline.NewCupsonlineTransport(cfg.URL, baseCfg, !isExit), nil
		case "oneme":
			token, _ := cfg.Params["token"].(string)
			uidStr, _ := cfg.Params["uid"].(string)
			uid, _ := strconv.ParseInt(uidStr, 10, 64)
			exit, _ := cfg.Params["exit"].(bool)
			return oneme.NewOneMeTransport(exit, token, uid, baseCfg), nil
		case "direct":
			dcfg := transport.DefaultDirectConfig()
			if v, ok := cfg.Params["listen"].(string); ok {
				dcfg.ListenAddr = v
			}
			if v, ok := cfg.Params["dial"].(string); ok {
				dcfg.DialAddr = v
			}
			if v, ok := cfg.Params["is_exit"].(bool); ok {
				dcfg.IsExit = v
			}
			// This builder serves the operator's own configuration as well as a
			// peer's, and it must keep doing so: main.go's bootstrap path and
			// the .conf path both reach it through makeRawTransport. What a
			// PEER may choose is decided at the trust boundary instead - see
			// manager.peerParams, which strips listen, is_exit and exit.
			return transport.NewDirectTransport(baseCfg, dcfg), nil
		default:
			return nil, fmt.Errorf("factory: unknown transport type %q", cfg.Type)
		}
	}
}
