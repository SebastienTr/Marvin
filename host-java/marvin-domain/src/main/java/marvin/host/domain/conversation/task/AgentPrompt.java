// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation.task;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import marvin.host.domain.conversation.Persona;
import marvin.host.domain.conversation.tool.ToolSpec;

/**
 * What a background task's model is told: who it works for, the tools it has (and that it has no other), how to finish.
 * The honesty rules of the persona hold here too: never claim a tool, a source or a result it does not have.
 */
public final class AgentPrompt {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy, HH:mm", Locale.ENGLISH);

    static final String SYSTEM = """
            You are the background worker of Marvin, a small robot on its owner's desk. Marvin asked you to carry out a \
            task for its owner while their conversation goes on. You run on the owner's computer.

            How you work:
            - Think, plan briefly, then use your tools one step at a time, reading each result before the next step. Do \
            not call the same tool with the same arguments twice.
            - Your tools are the only things you can do: {tools}. You cannot browse the web, read email or a calendar, \
            or reach anything else unless one of these tools does it. Never pretend you used a tool, a source or a fact \
            you do not have, and never invent numbers, dates or quotes. When the task needs something you cannot do, say \
            so in the result and do what you can.
            - Remember something (the remember tool, if you have it) only when the task asks for it.
            - You have at most {steps} steps; finish well before. When you have what you need, or cannot get further, \
            call final_answer once: its summary is one or two short sentences in {language} that Marvin says aloud \
            (plain words, no markdown, numbers written as they are said); its result is the full answer in Markdown, in \
            {language}, concise and structured (short headings, lists, a small table when it helps), saying what you \
            could not check.""";

    private AgentPrompt() {
    }

    /** The system prompt for a task: its tools by name, its budget, its language; then what memory knows of the owner. */
    public static String system(List<ToolSpec> tools, int steps, String language, String profile) {
        List<String> names = tools.stream().map(ToolSpec::name).filter(n -> !n.equals("final_answer")).toList();
        String p = SYSTEM.replace("{tools}", names.isEmpty() ? "none (only final_answer)" : String.join(", ", names)
                        + " (and final_answer to finish)")
                .replace("{steps}", Integer.toString(steps))
                .replace("{language}", Persona.languageName(language));
        if (profile != null && !profile.isBlank()) {
            p += "\n\nWhat Marvin knows about its owner (from its memory; use it when it helps):\n" + profile.strip();
        }
        return p;
    }

    /** The task's first message: when it was asked, and the goal. */
    public static String task(String goal, LocalDateTime now, String language) {
        return "Now: " + WHEN.format(now) + ".\nThe owner asked (in " + Persona.languageName(language) + "): " + goal.strip();
    }

    /** Said to the model when its budget is nearly spent. */
    public static final String LAST_STEP = "Your budget is nearly spent: call final_answer now with what you have, and say "
            + "what is missing.";

    /** Said to the model when it answered in plain text instead of finishing. */
    public static final String FINISH = "If you are done, call final_answer with the summary and the result; otherwise "
            + "go on with your tools.";
}
