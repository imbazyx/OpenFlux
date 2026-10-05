package provision

// Pinned is the node-install.sh this app build runs: the file at a fixed
// commit of the app's own repository and its SHA-256. TestPinnedScriptHash
// keeps the hash in step with deploy/node-install.sh; when the script
// changes, commit it, then point PinnedCommit at that commit.
const (
	PinnedRepo   = "imbazyx/OpenFlux"
	PinnedCommit = "7879e91eea9d1ce3e7da24bf05e6c7cd1c0d5d87"
	PinnedSHA256 = "022bc93273096e53538c148610163e379f6962564c838b9813aed7daa71fc469"
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

// Pinned returns the script location for this build.
func Pinned() Script {
	return Script{
		URL:    "https://raw.githubusercontent.com/" + PinnedRepo + "/" + PinnedCommit + "/deploy/node-install.sh",
		SHA256: PinnedSHA256,
	}
}
