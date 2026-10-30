package dev.buddy.shell

object ShellProtocol {
    const val VERSION = 2
    const val SHELL_UID = 2000
    const val LEASE_MS = 30_000L
    const val AUTHORITY_SUFFIX = ".bootstrap"
    const val HANDOFF = "handoff"
    const val REVALIDATE = "revalidate"
}
