package dev.anodex.mobile.chat

/**
 * What a tool call did, said the way a person would: "Ran a command", "Read a file".
 *
 * The transcript used to print the tool's own name — `run_command` in code type —
 * beside the computer's title for the call. The name is an implementation detail;
 * the desktop's transcript never shows it either, leading with the action instead.
 * The thing acted on stays beside this, so anyone checking can still see exactly
 * what ran.
 *
 * A tool not listed here falls back to the action word the computer's own title
 * starts with — the desktop's `getToolCallDisplay` split — so a tool added on the
 * desktop tomorrow still reads as an action rather than as an identifier.
 */
fun toolAction(activity: ToolActivity): String {
    val known = TOOL_ACTIONS[activity.name]
    if (known != null) {
        return if (activity.status == ToolActivity.Status.RUNNING) known.doing else known.did
    }
    return splitToolTitle(activity.title).first.ifBlank { activity.name }
}

/**
 * What the call acted on: the title with its action word taken off.
 *
 * "Run: echo 2 + 2" gives "echo 2 + 2", "Read src/App.tsx" gives "src/App.tsx".
 */
fun toolTarget(activity: ToolActivity): String {
    val (action, target) = splitToolTitle(activity.title)
    // A title that is nothing but its action: shown whole beside a known tool's own
    // words, and not at all beside an unknown one, where it is already the action.
    return target.ifBlank { if (TOOL_ACTIONS.containsKey(activity.name)) action else "" }
}

/** The desktop's split: "Action: target", else "Action target". */
internal fun splitToolTitle(title: String): Pair<String, String> {
    val trimmed = title.trim()
    COLON.matchEntire(trimmed)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
    SPACE.matchEntire(trimmed)?.let { return it.groupValues[1].trim() to it.groupValues[2].trim() }
    return trimmed to ""
}

private val COLON = Regex("""^([^:]+):\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
private val SPACE = Regex("""^(\S+)\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

private class Action(val doing: String, val did: String)

private val TOOL_ACTIONS: Map<String, Action> = buildMap {
    fun put(did: String, doing: String, vararg names: String) {
        for (name in names) put(name, Action(doing, did))
    }
    put("Ran a command", "Running a command", "run_command")
    put("Started a process", "Starting a process", "start_process")
    put("Stopped a process", "Stopping a process", "stop_process")
    put("Read a process's output", "Reading a process's output", "read_process_output")
    put("Ran the project's checks", "Running the project's checks", "run_project_check")
    put("Checked the computer", "Checking the computer", "check_computer")
    put("Read a file", "Reading a file", "read_file", "read_file_range")
    put("Read files", "Reading files", "read_multiple_files")
    put("Looked at a file", "Looking at a file", "get_file_info", "code_outline")
    put("Listed a folder", "Listing a folder", "list_directory")
    put("Found files", "Finding files", "find_files")
    put("Searched the files", "Searching the files", "search_files", "search_code")
    put("Edited a file", "Editing a file", "edit_file", "replace_lines", "patch_file")
    put("Wrote a file", "Writing a file", "write_file")
    put("Added to a file", "Adding to a file", "append_file")
    put("Deleted a file", "Deleting a file", "delete_file")
    put("Deleted a folder", "Deleting a folder", "delete_directory")
    put("Moved a file", "Moving a file", "move_file")
    put("Moved to the Trash", "Moving to the Trash", "move_to_trash")
    put("Opened it", "Opening it", "open_item")
    put("Downloaded a file", "Downloading a file", "download_file")
    put("Sent you a file", "Sending you a file", "send_file_to_me")
    put("Made a folder", "Making a folder", "create_directory")
    put("Searched the web", "Searching the web", "web_search")
    put("Opened a page", "Opening a page", "fetch_url")
    put("Checked the changes", "Checking the changes", "git_status", "git_diff", "list_changes")
    put("Sent an email", "Sending an email", "send_email", "forward_email")
    put("Replied to an email", "Replying to an email", "reply_email")
    put("Drafted an email", "Drafting an email", "draft_email", "save_email_draft")
    put("Read an email", "Reading an email", "read_email", "summarize_thread")
    put("Searched email", "Searching email", "search_email", "list_threads")
    put("Wrote a plan", "Writing a plan", "write_plan")
    put("Updated the plan", "Updating the plan", "update_plan_step")
    put("Scheduled a task", "Scheduling a task", "schedule_task")
    put("Set a reminder", "Setting a reminder", "set_reminder")
    put("Remembered something", "Remembering something", "remember_fact")
    put("Made an image", "Making an image", "generate_image")
    put("Previewed a page", "Previewing a page", "preview_html")
    put("Looked at an image", "Looking at an image", "inspect_visual")
    put("Showed an image", "Showing an image", "show_image")
}
