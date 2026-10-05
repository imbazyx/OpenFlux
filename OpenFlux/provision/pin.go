package provision

// Pinned is the node-install.sh this app build runs: the file at a fixed
// commit of the app's own repository and its SHA-256. TestPinnedScriptHash
// keeps the hash in step with deploy/node-install.sh; when the script
// changes, commit it, then point PinnedCommit at that commit.
const (
	PinnedRepo   = "imbazyx/OpenFlux"
	PinnedCommit = "da6fe76bc289e7b5fb28ec12fec22c8185e654b9"
	PinnedSHA256 = "022bc93273096e53538c148610163e379f6962564c838b9813aed7daa71fc469"
)

// Pinned returns the script location for this build.
func Pinned() Script {
	return Script{
		URL:    "https://raw.githubusercontent.com/" + PinnedRepo + "/" + PinnedCommit + "/deploy/node-install.sh",
		SHA256: PinnedSHA256,
	}
}
