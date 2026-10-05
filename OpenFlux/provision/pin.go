package provision

// Pinned is the node-install.sh this app build runs: the file at a fixed
// commit of the app's own repository and its SHA-256. TestPinnedScriptHash
// keeps the hash in step with deploy/node-install.sh; when the script
// changes, commit it, then point PinnedCommit at that commit.
const (
	PinnedRepo   = "imbazyx/OpenFlux"
	PinnedCommit = "0747deca6c9a859eb6b59b2fb56668dd1cbfa8e6"
	PinnedSHA256 = "409e998065069614023bb4dff2b1583c2937bd85ee8bd3022229133b20221ea0"
)

// PinnedCommit must stay reachable from main.
//
// It used to name da6fe76, which the history rewrite orphaned: the commit is
// gone from main and from origin/main, so raw.githubusercontent would only
// serve it for as long as GitHub keeps unreachable objects around, and not at
// all for anyone who cloned fresh. TestPinnedScriptHash still passed here,
// because the object was sitting in the local repository - the test reads the
// file through git, so it cannot tell a reachable commit from an orphan.
// That is the whole lesson: check the pin against `git merge-base
// --is-ancestor <commit> main`, not only against the SHA-256.

// PinnedPath is the script's location inside the repository.
//
// The OpenFlux/ prefix is not decoration. The pinned repository is this
// project itself, and the Go core - the script included - lives in the
// OpenFlux/ subdirectory. Asking for a top-level deploy/node-install.sh
// returned 404 from GitHub for every user, on every channel, so "Своя нода"
// failed outright with "сервер не смог скачать скрипт установки с GitHub".
//
// Reachability is not enough either: PinnedCommit was on main and the URL was
// still 404. TestPinnedURLMatchesLayout is what would have caught it, because
// it compares the requested path against where the file actually is in this
// checkout.
const PinnedPath = "OpenFlux/deploy/node-install.sh"

// Pinned returns the script location for this build.
func Pinned() Script {
	return Script{
		URL:    "https://raw.githubusercontent.com/" + PinnedRepo + "/" + PinnedCommit + "/" + PinnedPath,
		SHA256: PinnedSHA256,
	}
}
