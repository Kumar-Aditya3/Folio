package logcat

/**
 * Minimal stand-in for the `logcat` library's priority enum, keeping the FQCN that the
 * vendored Mihon sources import.
 */
enum class LogPriority(val value: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
    ASSERT(7),
}
