// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * What Marvin remembers of the conversation for the next question (the Python host's
 * {@code VoiceAssistant._remember}).
 *
 * <p>The model server reuses its work on everything up to the first message that changed, so the history
 * only ever grows at the end: dropping the oldest turn every time would change its start at each question
 * and make the model read the whole conversation again. When it is full, the older half of the turns goes
 * at once. A turn is everything from a question to the next one: a tool exchange is never cut in two. After
 * {@code resetAfterS} of silence, it is forgotten. Times are monotonic seconds. Not thread-safe.
 */
public final class ConversationMemory {
    private final int turns;
    private final double resetAfterS;
    private final List<ChatMessage> history = new ArrayList<>();
    private double lastTurn;

    public ConversationMemory(int turns, double resetAfterS) {
        this.turns = turns;
        this.resetAfterS = resetAfterS;
    }

    /** The history to send before a new question at {@code now}: forgotten first if it is too old. */
    public List<ChatMessage> messages(double now) {
        if (!history.isEmpty() && now - lastTurn > resetAfterS) {
            history.clear();
        }
        return List.copyOf(history);
    }

    /** Keeps a question, the tool exchange in between and the answer. */
    public void remember(String question, String answer, List<ChatMessage> exchange, double now) {
        history.add(ChatMessage.user(question));
        history.addAll(exchange);
        history.add(ChatMessage.assistant(answer));
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).role().equals("user")) {
                starts.add(i);
            }
        }
        if (starts.size() > turns) {
            int keep = Math.max(1, turns / 2);
            history.subList(0, starts.get(starts.size() - keep)).clear();
        }
        lastTurn = now;
    }

    public int size() {
        return history.size();
    }

    public void clear() {
        history.clear();
    }
}
