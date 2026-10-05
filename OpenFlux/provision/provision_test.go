package provision

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestPinnedScriptHash(t *testing.T) {
	body, err := os.ReadFile("../deploy/node-install.sh")
	if err != nil {
		t.Fatal(err)
	}
	if got := ScriptHash(body); got != PinnedSHA256 {
		t.Fatalf("deploy/node-install.sh changed: sha256 %s, pinned %s; commit it and update pin.go", got, PinnedSHA256)
	}
}

// TestPinnedCommitIsReachable is the check TestPinnedScriptHash could not do.
//
// The hash test reads the file from the working tree, so it is happy whether
// the pinned commit is part of the project's history or a stray object that
// happens to be lying around locally. When the history was rewritten, the pin
// kept naming a commit that no longer existed on any branch: the test still
// passed, and the build would have asked GitHub for a URL that only worked
// for as long as GitHub kept unreachable objects around.
//
// A pin is a promise to a machine that does not have this repository. It has
// to name a commit a fresh clone can actually resolve.
func TestPinnedCommitIsReachable(t *testing.T) {
	if len(PinnedCommit) != 40 || strings.ContainsAny(PinnedCommit, "ghijklmnopqrstuvwxyzGHIJKLMNOPQRSTUVWXYZ+-.") {
		t.Fatalf("PinnedCommit %q is not a full sha1", PinnedCommit)
	}
	// Ask git where the repository is rather than guessing: OpenFlux/ is a
	// subdirectory of the checkout, not its root, and worktrees and submodules
	// differ again. The first version of this test looked for ../.git, skipped
	// itself, and reported as coverage while checking nothing.
	if _, err := exec.Command("git", "rev-parse", "--git-dir").Output(); err != nil {
		t.Skip("not a git checkout; cannot check reachability")
	}
	// Reachability is only decidable where the history is present. Measured on
	// `git clone --depth 1`: merge-base exits 128 with "Not a valid object
	// name", which this test would report as a broken pin and turn CI red on a
	// pin that is fine. actions/checkout@v4 defaults to depth 1.
	//
	// The test is whether the pinned commit is PRESENT, not whether the
	// repository claims to be shallow: this working copy answers
	// --is-shallow-repository with true - it was cloned with --depth 1 once -
	// while carrying the full history, so a shallow-flag check would skip the
	// test exactly where it is most useful.
	// Checked against the REMOTE-tracking ref, not a local branch. release.yml
	// runs this on a tag push with actions/checkout, which leaves a detached
	// HEAD: origin/main is present, a local `main` is not. Asking for `main`
	// therefore made the test skip itself in exactly the configuration that
	// produces it, and it is the configuration that matters - the pin exists to
	// stop an already-published commit becoming unreachable.
	ref := "origin/main"
	if _, err := exec.Command("git", "rev-parse", "--verify", ref).Output(); err != nil {
		if _, err2 := exec.Command("git", "rev-parse", "--verify", "main").Output(); err2 != nil {
			t.Skip("neither origin/main nor main is in this checkout; reachability is not decidable")
		}
		ref = "main"
	}
	if _, err := exec.Command("git", "cat-file", "-e", PinnedCommit+"^{commit}").Output(); err != nil {
		t.Skip("pinned commit is not in this clone; reachability is not decidable here")
	}
	out, err := exec.Command("git", "merge-base", "--is-ancestor", PinnedCommit, ref).CombinedOutput()
	if err != nil {
		t.Fatalf("PinnedCommit %s is not reachable from %s: %v\n%s\n"+
			"the pin points at a commit that vanished in a rewrite; "+
			"point it at the commit that last changed deploy/node-install.sh",
			PinnedCommit[:12], ref, err, out)
	}
}

// TestPinnedURLMatchesLayout is the check that would have caught the 404.
//
// Reachability and hash both passed while the URL was broken: the pinned
// commit was on main, and the bytes at the corrected path hash to exactly
// PinnedSHA256. What was wrong is the PATH - the core lives under OpenFlux/,
// and asking for a top-level deploy/node-install.sh 404'd for every user, so
// installing a node of one's own failed outright.
//
// Asserting against the real layout catches a moved or renamed file and needs
// no network. A second test that fetches the URL would catch what this one
// cannot - a repository that is private, renamed, or deleted - but it must
// skip in CI, because a network test in the unit suite is a test that fails
// when someone else's Wi-Fi hiccups.
func TestPinnedURLMatchesLayout(t *testing.T) {
	const want = "openflux/deploy/node-install.sh"
	if got := strings.ToLower(PinnedPath); got != want {
		t.Fatalf("PinnedPath is %q, but the script is at %q in this repository; "+
			"GitHub serves raw paths from the repo root, so this is a 404 for every user",
			got, want)
	}
	if !strings.HasSuffix(Pinned().URL, "/"+PinnedPath) {
		t.Fatalf("Pinned().URL %q does not end with %q", Pinned().URL, "/"+PinnedPath)
	}
}

func TestPinnedURLResolves(t *testing.T) {
	if os.Getenv("OPENFLUX_NET") == "" {
		t.Skip("OPENFLUX_NET=1 to check the pinned URL over the network")
	}
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Get(Pinned().URL)
	if err != nil {
		t.Fatalf("fetching %s: %v", Pinned().URL, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("%s returned %s: installing a node of one's own would fail for every user",
			Pinned().URL, resp.Status)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if got := ScriptHash(body); got != PinnedSHA256 {
		t.Fatalf("the served script hashes %s, pinned %s", got, PinnedSHA256)
	}
}

func TestNewChannelIDMatchesScript(t *testing.T) {
	re := regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,30}$`)
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		id, err := NewChannelID()
		if err != nil {
			t.Fatal(err)
		}
		if !re.MatchString(id) {
			t.Fatalf("bad id %q", id)
		}
		seen[id] = true
	}
	if len(seen) < 190 {
		t.Fatalf("ids repeat too often: %d unique of 200", len(seen))
	}
}

func TestNewKey(t *testing.T) {
	k, err := NewKey()
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(k) {
		t.Fatalf("bad key %q", k)
	}
}

func TestLastJSON(t *testing.T) {
	var v struct {
		OK bool `json:"ok"`
	}
	if err := lastJSON([]byte("noise\n{\"ok\":true}\n\n"), &v); err != nil || !v.OK {
		t.Fatalf("lastJSON: %v %v", err, v)
	}
	if err := lastJSON([]byte("no json"), &v); err == nil {
		t.Fatal("want error")
	}
}

func TestSudoRefusal(t *testing.T) {
	if !isSudoRefusal([]byte("Sorry, try again.\nsudo: 1 incorrect password attempt")) {
		t.Fatal("want refusal")
	}
	if isSudoRefusal([]byte("sh: 1: foo: not found")) {
		t.Fatal("unexpected refusal")
	}
}

func TestCookieStoreFormat(t *testing.T) {
	b64, signedIn, err := CookieStore("https://docs.yandex.ru/edit/d/x", " Session_id=a=b ; yandexuid=1;bad;\nx=y")
	if err != nil || !signedIn {
		t.Fatalf("%v %v", signedIn, err)
	}
	raw, _ := base64.StdEncoding.DecodeString(b64)
	var store map[string]map[string]string
	if err := json.Unmarshal(raw, &store); err != nil {
		t.Fatal(err)
	}
	jar := store["https://docs.yandex.ru/edit/d/x"]
	if jar["Session_id"] != "a=b" || jar["yandexuid"] != "1" || len(jar) != 3 {
		t.Fatalf("jar = %v", jar)
	}
	if _, signedIn, _ := CookieStore("u", "yandexuid=1"); signedIn {
		t.Fatal("no Session_id means not signed in")
	}
}
