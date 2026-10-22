package mailru

import (
	"bufio"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// loadMailruCookies imports a Netscape cookies.txt export into an HTTP cookie
// jar. Only Mail.ru hosts are accepted; cookie values never appear in error
// messages.
//
// This exists because of what the editor API answers from outside Russia.
// Measured 2026-10-22 from a German host, for a document whose share dialog
// says "доступен всем":
//
//	POST https://cloud.mail.ru/api/v4/r7/edit
//	401 {"error":"FORBIDDEN/ANONYMOUSE/ACCESS",
//	      "error_description":"public is available with auth only"}
//
// The document opens in a browser from that host - the owner is logged in
// there - but the anonymous editor API refuses the same document. A session is
// the missing piece, and a session is a cookie.
func loadMailruCookies(path string, jar http.CookieJar) error {
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open Mail.ru cookie file: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	loaded := 0
	for lineNo := 1; scanner.Scan(); lineNo++ {
		line := strings.TrimSuffix(scanner.Text(), "\r")
		if line == "" || (strings.HasPrefix(line, "#") && !strings.HasPrefix(line, "#HttpOnly_")) {
			continue
		}

		fields := strings.Split(line, "\t")
		if len(fields) != 7 {
			return fmt.Errorf("Mail.ru cookie file line %d: expected 7 tab-separated fields", lineNo)
		}
		rawDomain := strings.TrimPrefix(fields[0], "#HttpOnly_")
		domain := strings.ToLower(strings.TrimPrefix(rawDomain, "."))
		if !isMailruHost(domain) {
			continue
		}
		if fields[1] != "TRUE" && fields[1] != "FALSE" {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid subdomain flag", lineNo)
		}
		if fields[3] != "TRUE" && fields[3] != "FALSE" {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid secure flag", lineNo)
		}
		expires, err := strconv.ParseInt(fields[4], 10, 64)
		if err != nil {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid expiry", lineNo)
		}
		if expires != 0 && expires <= time.Now().Unix() {
			continue
		}
		if fields[5] == "" {
			return fmt.Errorf("Mail.ru cookie file line %d: empty cookie name", lineNo)
		}
		cookiePath := fields[2]
		if !strings.HasPrefix(cookiePath, "/") {
			return fmt.Errorf("Mail.ru cookie file line %d: invalid cookie path", lineNo)
		}
		cookie := &http.Cookie{
			Name:   fields[5],
			Value:  fields[6],
			Path:   cookiePath,
			Secure: fields[3] == "TRUE",
		}
		if fields[1] == "TRUE" {
			cookie.Domain = domain
		}
		if expires != 0 {
			cookie.Expires = time.Unix(expires, 0)
		}
		jar.SetCookies(&url.URL{Scheme: "https", Host: domain, Path: cookiePath}, []*http.Cookie{cookie})
		loaded++
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read Mail.ru cookie file: %w", err)
	}
	if loaded == 0 {
		return fmt.Errorf("no Mail.ru cookies in %s", path)
	}
	return nil
}

// isMailruHost accepts the hosts a Mail.ru session lives on. The editor API
// lives on cloud.mail.ru, the documents app on mail.ru, and the editor's
// websocket on docs.datacloudmail.ru.
func isMailruHost(domain string) bool {
	switch domain {
	case "mail.ru", "cloud.mail.ru", "datacloudmail.ru", "docs.datacloudmail.ru":
		return true
	}
	return strings.HasSuffix(domain, ".mail.ru") ||
		strings.HasSuffix(domain, ".datacloudmail.ru")
}
