package kira.ditto.data.chatdb

/**
 * The space every conversation starts in, and the one a user who never creates another will only
 * ever see as "the app".
 *
 * It doubles as a directory name under `/workspace`, so it stays lowercase ASCII: Kimi Code CLI
 * derives its own workspace key from `basename(root)` and slugifies anything else, and a name that
 * survives that step unchanged keeps the guest path and the CLI's registry entry legible together.
 *
 * Deliberately not `"workspace"`: that is the old shared root, which is being emptied rather than
 * inherited. Reusing the name would hand the new default space the very leftovers this change
 * exists to get rid of.
 */
const val DefaultWorkspaceId: String = "home"

/** Shown until the user renames it. */
const val DefaultWorkspaceName: String = "Home"
