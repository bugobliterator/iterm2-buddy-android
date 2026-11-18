package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.wire.WireDecodeException
import dev.bugobliterator.itermbuddy.core.wire.int
import dev.bugobliterator.itermbuddy.core.wire.obj
import dev.bugobliterator.itermbuddy.core.wire.string
import kotlinx.serialization.json.JsonObject

/**
 * `RemoteCommand.Content` (`RemoteCommand.swift:171-196`): the 26 typed tool calls of a
 * session-bound chat. Each case keeps the arguments its [markdownDescription] shows; the others
 * are checked on decode, as upstream's synthesized decoder requires them, and dropped.
 *
 * On the wire the case is the `content` object's single key, its argument struct under `_0`,
 * beside the `llmMessage` (`RemoteCommand.swift:104-139, 379-380`). [functionName] and
 * [permissionCategory] are computed here as the Mac computes them, never read from the wire.
 */
sealed interface CommandContent {
    /** The case name, the wire discriminator. */
    val wireName: String

    /** The tool name the model sees (`RemoteCommand.swift:535-590`). */
    val functionName: String

    data object IsAtPrompt : CommandContent {
        override val wireName = "isAtPrompt"
        override val functionName = "is_at_prompt"
    }

    data class ExecuteCommand(val command: String) : CommandContent {
        override val wireName get() = "executeCommand"
        override val functionName get() = "execute_command"

        override fun toString(): String = "ExecuteCommand(command(len=${command.length}))"
    }

    data object GetLastExitStatus : CommandContent {
        override val wireName = "getLastExitStatus"
        override val functionName = "get_last_exit_status"
    }

    data object GetCommandHistory : CommandContent {
        override val wireName = "getCommandHistory"
        override val functionName = "get_command_history"
    }

    data object GetLastCommand : CommandContent {
        override val wireName = "getLastCommand"
        override val functionName = "get_last_command"
    }

    data object GetCommandBeforeCursor : CommandContent {
        override val wireName = "getCommandBeforeCursor"
        override val functionName = "get_command_before_cursor"
    }

    data object SearchCommandHistory : CommandContent {
        override val wireName = "searchCommandHistory"
        override val functionName = "search_command_history"
    }

    data object GetCommandOutput : CommandContent {
        override val wireName = "getCommandOutput"
        override val functionName = "get_command_output"
    }

    data object GetScreenContents : CommandContent {
        override val wireName = "getScreenContents"
        override val functionName = "get_screen_contents"
    }

    data object GetTerminalSize : CommandContent {
        override val wireName = "getTerminalSize"
        override val functionName = "get_terminal_size"
    }

    data object GetShellType : CommandContent {
        override val wireName = "getShellType"
        override val functionName = "get_shell_type"
    }

    data object DetectSSHSession : CommandContent {
        override val wireName = "detectSSHSession"
        override val functionName = "detect_ssh_session"
    }

    data object GetRemoteHostname : CommandContent {
        override val wireName = "getRemoteHostname"
        override val functionName = "get_remote_hostname"
    }

    data object GetUserIdentity : CommandContent {
        override val wireName = "getUserIdentity"
        override val functionName = "get_user_identity"
    }

    data object GetCurrentDirectory : CommandContent {
        override val wireName = "getCurrentDirectory"
        override val functionName = "get_current_directory"
    }

    data object SetClipboard : CommandContent {
        override val wireName = "setClipboard"
        override val functionName = "set_clipboard"
    }

    data class InsertTextAtCursor(val text: String) : CommandContent {
        override val wireName get() = "insertTextAtCursor"
        override val functionName get() = "insert_text_at_cursor"

        override fun toString(): String = "InsertTextAtCursor(text(len=${text.length}))"
    }

    data object DeleteCurrentLine : CommandContent {
        override val wireName = "deleteCurrentLine"
        override val functionName = "delete_current_line"
    }

    data class GetManPage(val cmd: String) : CommandContent {
        override val wireName get() = "getManPage"
        override val functionName get() = "get_man_page"

        override fun toString(): String = "GetManPage(cmd(len=${cmd.length}))"
    }

    /** `createFile`: the file's content is checked on decode and not kept. */
    data class CreateFile(val filename: String) : CommandContent {
        override val wireName get() = "createFile"
        override val functionName get() = "create_file"

        override fun toString(): String = "CreateFile(filename(len=${filename.length}))"
    }

    data class SearchBrowser(val query: String) : CommandContent {
        override val wireName get() = "searchBrowser"
        override val functionName get() = "find_on_page"

        override fun toString(): String = "SearchBrowser(query(len=${query.length}))"
    }

    data class LoadURL(val url: String) : CommandContent {
        override val wireName get() = "loadURL"
        override val functionName get() = "load_url"

        override fun toString(): String = "LoadURL(url(len=${url.length}))"
    }

    data class WebSearch(val query: String) : CommandContent {
        override val wireName get() = "webSearch"
        override val functionName get() = "web_search_in_browser"

        override fun toString(): String = "WebSearch(query(len=${query.length}))"
    }

    data object GetURL : CommandContent {
        override val wireName = "getURL"
        override val functionName = "get_current_url"
    }

    data object ReadWebPage : CommandContent {
        override val wireName = "readWebPage"
        override val functionName = "read_web_page_section"
    }

    data object RestartSession : CommandContent {
        override val wireName = "restartSession"
        override val functionName = "restart_session"
    }
}

/** `RemoteCommand.Content.PermissionCategory` (`RemoteCommand.swift:202-210`); [label] is the raw value. */
enum class PermissionCategory(val label: String) {
    CHECK_TERMINAL_STATE("Check Terminal State"),
    RUN_COMMANDS("Run Commands"),
    VIEW_CONTENTS("View Contents"),
    WRITE_TO_CLIPBOARD("Write to the Clipboard"),
    CONTROL_TERMINAL("Control Terminal"),
    VIEW_MANPAGES("View Manpages"),
    WRITE_TO_FILESYSTEM("Write to the File System"),
    ACT_IN_WEB_BROWSER("Act in Web Browser"),
}

/** `RemoteCommand.Content.permissionCategory` (`RemoteCommand.swift:323-344`). */
val CommandContent.permissionCategory: PermissionCategory
    get() = when (this) {
        CommandContent.IsAtPrompt, CommandContent.GetLastExitStatus, CommandContent.GetTerminalSize,
        CommandContent.GetShellType, CommandContent.DetectSSHSession, CommandContent.GetRemoteHostname,
        CommandContent.GetUserIdentity, CommandContent.GetCurrentDirectory,
        -> PermissionCategory.CHECK_TERMINAL_STATE
        is CommandContent.ExecuteCommand -> PermissionCategory.RUN_COMMANDS
        CommandContent.GetCommandHistory, CommandContent.GetLastCommand, CommandContent.GetCommandBeforeCursor,
        CommandContent.SearchCommandHistory, CommandContent.GetCommandOutput, CommandContent.GetScreenContents,
        -> PermissionCategory.VIEW_CONTENTS
        CommandContent.SetClipboard -> PermissionCategory.WRITE_TO_CLIPBOARD
        is CommandContent.InsertTextAtCursor, CommandContent.DeleteCurrentLine, CommandContent.RestartSession,
        -> PermissionCategory.CONTROL_TERMINAL
        is CommandContent.GetManPage -> PermissionCategory.VIEW_MANPAGES
        is CommandContent.CreateFile -> PermissionCategory.WRITE_TO_FILESYSTEM
        is CommandContent.SearchBrowser, is CommandContent.LoadURL, is CommandContent.WebSearch,
        CommandContent.GetURL, CommandContent.ReadWebPage,
        -> PermissionCategory.ACT_IN_WEB_BROWSER
    }

/** The cut `markdownDescription` applies to a code-span argument (`RemoteCommand.swift:409, 439, 443`). */
private const val CODE_ARGUMENT_MAX_CHARACTERS = 32

/**
 * `RemoteCommand.markdownDescription` (`RemoteCommand.swift:404-459`) with the English default
 * values. A code-span argument has its backticks escaped (`TerminalCommand.swift:22-25`) and is then
 * cut to 32 `Character`s with a trailing "…" ([truncatedWithTrailingEllipsis]).
 */
fun CommandContent.markdownDescription(): String = when (this) {
    CommandContent.IsAtPrompt -> "Checking if you’re at a shell prompt"
    is CommandContent.ExecuteCommand -> "Executing `${codeArgument(command)}`"
    CommandContent.GetLastExitStatus -> "Checking the exit status of the last command"
    CommandContent.GetCommandHistory -> "Reviewing the history of commands you have run in this session"
    CommandContent.GetLastCommand -> "Viewing the last command you ran in this session"
    CommandContent.GetCommandBeforeCursor -> "Reading your current command prompt"
    CommandContent.SearchCommandHistory -> "Searching the history of commands you have run in this session"
    CommandContent.GetCommandOutput -> "Fetching the output of a previously run command"
    CommandContent.GetScreenContents -> "Reading the visible screen"
    CommandContent.GetTerminalSize -> "Querying the size of your terminal window"
    CommandContent.GetShellType -> "Determining which shell you use"
    CommandContent.DetectSSHSession -> "Checking if you are using SSH"
    CommandContent.GetRemoteHostname -> "Getting the current host name of this terminal session"
    CommandContent.GetUserIdentity -> "Checking your username"
    CommandContent.GetCurrentDirectory -> "Discovering your current directory"
    CommandContent.SetClipboard -> "Pasting to the clipboard"
    is CommandContent.InsertTextAtCursor -> "Typing `${codeArgument(text)}` into the current session"
    CommandContent.DeleteCurrentLine -> "Erasing the current command line"
    is CommandContent.GetManPage -> "Checking the manpage for `${codeArgument(cmd)}`"
    is CommandContent.CreateFile -> "Creating $filename"
    is CommandContent.SearchBrowser -> "Search in browser for $query"
    is CommandContent.LoadURL -> "Navigate to $url"
    is CommandContent.WebSearch -> "Search the web for “$query”"
    CommandContent.GetURL -> "Get the current URL"
    CommandContent.ReadWebPage -> "View the current web page"
    CommandContent.RestartSession -> "Restarting this session"
}

/** True when [markdownDescription] cut this command's argument, so the full text is worth showing. */
val CommandContent.argumentWasCut: Boolean
    get() = when (this) {
        is CommandContent.ExecuteCommand -> graphemeCount(escapedForMarkdownCode(command)) > CODE_ARGUMENT_MAX_CHARACTERS
        is CommandContent.InsertTextAtCursor -> graphemeCount(escapedForMarkdownCode(text)) > CODE_ARGUMENT_MAX_CHARACTERS
        is CommandContent.GetManPage -> graphemeCount(escapedForMarkdownCode(cmd)) > CODE_ARGUMENT_MAX_CHARACTERS
        else -> false
    }

private fun codeArgument(argument: String): String =
    escapedForMarkdownCode(argument).truncatedWithTrailingEllipsis(CODE_ARGUMENT_MAX_CHARACTERS)

/** `String.escapedForMarkdownCode` (`TerminalCommand.swift:22-25`). */
internal fun escapedForMarkdownCode(text: String): String = text.replace("`", "\\`")

/**
 * Decodes a `RemoteCommand`'s `content` object, `{"<case>": {"_0": {<arguments>}}}`. An unknown
 * case, a missing or mistyped argument, or any other shape upstream's synthesized decoder rejects
 * throws a [WireDecodeException] naming [path] and keys only.
 */
internal fun decodeCommandContent(content: JsonObject, path: String): CommandContent {
    val case = content.keys.singleOrNull() ?: throw WireDecodeException("$path must have exactly one key")
    val body = content[case] as? JsonObject ?: throw WireDecodeException("$path.$case is not an object")
    val argumentsPath = "$path.$case"
    val arguments = body.obj(argumentsPath, "_0")
    val at = "$argumentsPath._0"
    return when (case) {
        "isAtPrompt" -> CommandContent.IsAtPrompt
        "executeCommand" -> CommandContent.ExecuteCommand(arguments.string(at, "command"))
        "getLastExitStatus" -> CommandContent.GetLastExitStatus
        "getCommandHistory" -> CommandContent.GetCommandHistory.also { arguments.int(at, "limit") }
        "getLastCommand" -> CommandContent.GetLastCommand
        "getCommandBeforeCursor" -> CommandContent.GetCommandBeforeCursor
        "searchCommandHistory" -> CommandContent.SearchCommandHistory.also { arguments.string(at, "query") }
        "getCommandOutput" -> CommandContent.GetCommandOutput.also { arguments.string(at, "id") }
        "getScreenContents" -> CommandContent.GetScreenContents.also { arguments.int(at, "lines") }
        "getTerminalSize" -> CommandContent.GetTerminalSize
        "getShellType" -> CommandContent.GetShellType
        "detectSSHSession" -> CommandContent.DetectSSHSession
        "getRemoteHostname" -> CommandContent.GetRemoteHostname
        "getUserIdentity" -> CommandContent.GetUserIdentity
        "getCurrentDirectory" -> CommandContent.GetCurrentDirectory
        "setClipboard" -> CommandContent.SetClipboard.also { arguments.string(at, "text") }
        "insertTextAtCursor" -> CommandContent.InsertTextAtCursor(arguments.string(at, "text"))
        "deleteCurrentLine" -> CommandContent.DeleteCurrentLine
        "getManPage" -> CommandContent.GetManPage(arguments.string(at, "cmd"))
        "createFile" -> {
            arguments.string(at, "content")
            CommandContent.CreateFile(arguments.string(at, "filename"))
        }
        "searchBrowser" -> CommandContent.SearchBrowser(arguments.string(at, "query"))
        "loadURL" -> CommandContent.LoadURL(arguments.string(at, "url"))
        "webSearch" -> CommandContent.WebSearch(arguments.string(at, "query"))
        "getURL" -> CommandContent.GetURL
        "readWebPage" -> {
            arguments.int(at, "startingLineNumber")
            arguments.int(at, "numberOfLines")
            CommandContent.ReadWebPage
        }
        "restartSession" -> CommandContent.RestartSession
        else -> throw WireDecodeException("$path has an unknown case")
    }
}
