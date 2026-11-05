package dev.dalibor.blinds.cli

import dev.dalibor.blinds.gateway.Command
import dev.dalibor.blinds.gateway.Device
import dev.dalibor.blinds.gateway.GatewayException
import dev.dalibor.blinds.gateway.GatewaySession
import dev.dalibor.blinds.gateway.Wire
import dev.dalibor.blinds.gateway.openPercent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

private const val TIMEOUT_MS = 15_000L

private const val USAGE = """Control Mobilus blinds through a COSMO GTW gateway on your network.

Usage:
  blinds login <host> <user>       Save the gateway address and login (asks for the password)
  blinds list [--json]             Show blinds and their last reported position
  blinds up <blind|all>            Open
  blinds down <blind|all>          Close
  blinds stop <blind|all>          Stop moving
  blinds set <blind|all> <0-100>   Move to a position, where 100 is fully open

<blind> is an id, a name, or the unique start of a name. Names are not case sensitive.
"""

private class UsageError(message: String) : Exception(message)
private class UserError(message: String) : Exception(message)

fun main(args: Array<String>) {
    val code = try {
        run(args.toList())
        0
    } catch (e: UsageError) {
        System.err.println("blinds: ${e.message}\n\n$USAGE")
        2
    } catch (e: UserError) {
        System.err.println("blinds: ${e.message}")
        1
    } catch (e: GatewayException) {
        System.err.println("blinds: ${e.message}")
        1
    }
    // Paho leaves non-daemon threads behind, which would keep the JVM alive otherwise.
    System.out.flush()
    exitProcess(code)
}

private fun run(args: List<String>) {
    when (args.firstOrNull()) {
        null, "help", "-h", "--help" -> print(USAGE)
        "login" -> {
            if (args.size != 3) throw UsageError("login needs a host and a user")
            login(args[1], args[2])
        }
        "list" -> {
            val json = when (args.drop(1)) {
                emptyList<String>() -> false
                listOf("--json") -> true
                else -> throw UsageError("list only accepts --json")
            }
            list(json)
        }
        "up", "down", "stop" -> {
            if (args.size != 2) throw UsageError("${args[0]} needs one blind, or all")
            send(args[1], Command(args[0].uppercase()))
        }
        "set" -> {
            if (args.size != 3) throw UsageError("set needs a blind and a position")
            val percent = args[2].removeSuffix("%").toIntOrNull()
            if (percent == null || percent !in 0..100) throw UsageError("position must be from 0 to 100")
            send(args[1], Command.position(percent))
        }
        else -> throw UsageError("unknown command: ${args[0]}")
    }
}

private fun login(host: String, user: String) {
    val console = System.console()
    val password = if (console != null) {
        console.readPassword("Gateway password for %s: ", user)?.let(::String)
            ?: throw UserError("no password given")
    } else {
        // Lets scripts pipe the password in, so it never has to appear in arguments.
        readlnOrNull() ?: throw UserError("no password given")
    }
    val key = Wire.userKey(password)
    GatewaySession.open(host, user, key, TIMEOUT_MS).use { session ->
        val count = session.devices().count { it.isBlind }
        saveConfig(Config(host, user, key))
        println("Logged in. Found $count blinds.")
    }
}

private fun list(json: Boolean) = session { session ->
    val blinds = session.devices().filter { it.isBlind }
    val state = session.state()
    if (json) {
        println(blinds.joinToString(",", "[", "]") { blind ->
            val open = openPercent(state[blind.id])?.toString() ?: "null"
            """{"id":${blind.id},"name":"${escapeJson(blind.name)}","open":$open}"""
        })
    } else {
        val width = blinds.maxOfOrNull { it.name.length } ?: 0
        for (blind in blinds) {
            val open = openPercent(state[blind.id])?.let { "$it% open" } ?: "unknown"
            println("%4d  %-${width}s  %s".format(blind.id, blind.name, open))
        }
    }
}

private fun send(target: String, command: Command) = session { session ->
    val blinds = session.devices().filter { it.isBlind }
    val selected = when (val match = resolveTarget(blinds, target)) {
        is TargetMatch.Found -> match.devices
        is TargetMatch.None -> throw UserError("no blind matches \"$target\"; try: blinds list")
        is TargetMatch.Ambiguous -> throw UserError(
            "\"$target\" could mean: ${match.devices.joinToString { it.name }}",
        )
    }
    if (command.value.endsWith("%")) {
        selected.filterNot { it.supportsPosition }.forEach {
            System.err.println("blinds: warning: ${it.name} may not support exact positions")
        }
    }
    session.send(selected.map { it.id }, command)
}

private fun session(block: (GatewaySession) -> Unit) {
    val config = loadConfig() ?: throw UserError("not logged in yet; run: blinds login <host> <user>")
    GatewaySession.open(config.host, config.user, config.key, TIMEOUT_MS).use(block)
}

sealed interface TargetMatch {
    class Found(val devices: List<Device>) : TargetMatch
    class Ambiguous(val devices: List<Device>) : TargetMatch
    data object None : TargetMatch
}

/** An exact id or name beats a prefix, so a blind called "Kitchen" stays reachable next to "Kitchen 2". */
fun resolveTarget(blinds: List<Device>, target: String): TargetMatch {
    if (target.equals("all", ignoreCase = true)) {
        return if (blinds.isEmpty()) TargetMatch.None else TargetMatch.Found(blinds)
    }
    target.toLongOrNull()?.let { id ->
        blinds.firstOrNull { it.id == id }?.let { return TargetMatch.Found(listOf(it)) }
    }
    val exact = blinds.filter { it.name.equals(target, ignoreCase = true) }
    if (exact.size == 1) return TargetMatch.Found(exact)
    if (exact.size > 1) return TargetMatch.Ambiguous(exact)
    val prefix = blinds.filter { it.name.startsWith(target, ignoreCase = true) }
    return when (prefix.size) {
        0 -> TargetMatch.None
        1 -> TargetMatch.Found(prefix)
        else -> TargetMatch.Ambiguous(prefix)
    }
}

fun escapeJson(value: String): String = buildString {
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}

private class Config(val host: String, val user: String, val key: ByteArray)

private fun configPath(): Path {
    val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() }
        ?: "${System.getProperty("user.home")}/.config"
    return Path.of(base, "blinds", "config.properties")
}

private fun loadConfig(): Config? {
    val path = configPath()
    if (!path.exists()) return null
    val props = Properties()
    path.inputStream().use(props::load)
    val host = props.getProperty("host") ?: return null
    val user = props.getProperty("user") ?: return null
    val key = props.getProperty("key")?.hexToByteArray() ?: return null
    return Config(host, user, key)
}

/** Stores only the password hash, though the gateway accepts that as a login, hence the 0600 file. */
private fun saveConfig(config: Config) {
    val path = configPath()
    Files.createDirectories(path.parent)
    if (!path.exists()) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }
    val props = Properties()
    props.setProperty("host", config.host)
    props.setProperty("user", config.user)
    props.setProperty("key", config.key.toHexString())
    path.outputStream().use { props.store(it, null) }
}
