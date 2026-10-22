package mailru

import (
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

// A Netscape export with one Mail.ru cookie, one foreign cookie and one
// expired Mail.ru cookie.
const cookieFixture = "# Netscape HTTP Cookie File\n" +
	".mail.ru\tTRUE\t/\tTRUE\t4102444800\tsid\tsecret-session\n" +
	".example.com\tTRUE\t/\tTRUE\t4102444800\tforeign\tnope\n" +
	".mail.ru\tTRUE\t/\tTRUE\t1\tgone\tstale\n"

func TestLoadMailruCookiesKeepsOnlyMailRu(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cookies.txt")
	if err := os.WriteFile(path, []byte(cookieFixture), 0o600); err != nil {
		t.Fatal(err)
	}
	jar, _ := cookiejar.New(nil)
	if err := loadMailruCookies(path, jar); err != nil {
		t.Fatalf("load: %v", err)
	}
	u, _ := url.Parse("https://cloud.mail.ru/")
	names := map[string]string{}
	for _, c := range jar.Cookies(u) {
		names[c.Name] = c.Value
	}
	if names["sid"] != "secret-session" {
		t.Fatalf("session cookie not loaded: %v", names)
	}
	if _, ok := names["foreign"]; ok {
		t.Fatalf("a foreign cookie was loaded: %v", names)
	}
	if _, ok := names["gone"]; ok {
		t.Fatalf("an expired cookie was loaded: %v", names)
	}
}

func TestLoadMailruCookiesRejectsAForeignOnlyFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cookies.txt")
	if err := os.WriteFile(path, []byte(".example.com\tTRUE\t/\tTRUE\t4102444800\ta\tb\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	jar, _ := cookiejar.New(nil)
	if err := loadMailruCookies(path, jar); err == nil {
		t.Fatal("a file without Mail.ru cookies must be refused")
	}
}

// The CSRF token lives in the page's state; the editor API refuses the
// request without it (401 "public is available with auth only", then 403
// {"error":"token"}).
func TestCSRFPatternReadsThePageState(t *testing.T) {
	page := `...,"extra_o_v":5496,"tokens":{"csrf":"AG_gDrAzxn8FZ166LH5i7YJ800"},"user":{...}`
	m := mailruCSRFPattern.FindStringSubmatch(page)
	if m == nil {
		t.Fatal("no CSRF token found in the page state")
	}
	if m[1] != "AG_gDrAzxn8FZ166LH5i7YJ800" {
		t.Fatalf("wrong token: %q", m[1])
	}
	if mailruCSRFPattern.MatchString(`"tokens":{}`) {
		t.Fatal("an empty token object must not match")
	}
}

// A document that only lets the session read must not be written into: the
// editor server closes the socket on the first write.
func TestCanEdit(t *testing.T) {
	if !canEdit(nil) {
		t.Fatal("no permissions means the session may edit")
	}
	if !canEdit(map[string]interface{}{"edit": true}) {
		t.Fatal("edit:true must be allowed")
	}
	if canEdit(map[string]interface{}{"edit": false}) {
		t.Fatal("edit:false must be refused")
	}
	if !canEdit(map[string]interface{}{"download": true}) {
		t.Fatal("a permissions map without an edit key means the session may edit")
	}
}
