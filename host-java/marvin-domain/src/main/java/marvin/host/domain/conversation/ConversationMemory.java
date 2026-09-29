// SPDX-License-Identifier: MIT
package marvin.host.domain.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * What Marvin remembers of the conversation for the next question (the Python host's
 * {@code VoiceAssistant._remember}).
 *
 * <p>The model server reuses its work on everything up to the first message that changed, so the history
 * only ever grows at the end: dropping the oldest turn every time would change its start at each question
 * and make the model read the whole conversation again. When it is full, the older half of the turns goes
 * at once. A turn is everything from a question to the next one: a tool exchange is never cut in two. After
 * {@code resetAfterS} of silence, it is forgotten. With a token budget (docs/design.md 5.3: at most 2500 tokens of
 * history on the voice path), the older half also goes when the history grows over it, the same way. Times are
 * monotonic seconds. Not thread-safe.
 */
public final class ConversationMemory {
    private final int turns;
    private final double resetAfterS;
    private final int maxTokens;
    private final ToIntFunction<String> tokens;
    private final List<ChatMessage> history = new ArrayList<>();
    private double lastTurn;

    public ConversationMemory(int turns, double resetAfterS) {
        this(turns, resetAfterS, Integer.MAX_VALUE, s -> 0);
    }

    /** With a budget: {@code tokens} estimates a message's content. */
    public ConversationMemory(int turns, double resetAfterS, int maxTokens, ToIntFunction<String> tokens) {
        this.turns = turns;
        this.resetAfterS = resetAfterS;
        this.maxTokens = maxTokens;
        this.tokens = tokens;
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
        while (tokens() > maxTokens) {
            List<Integer> s = userStarts();
            if (s.size() <= 1) {
                break;                          // the last turn stays whole, whatever its size
            }
            history.subList(0, s.get(s.size() - Math.max(1, s.size() / 2))).clear();
        }
        lastTurn = now;
    }

    /**
     * Drops the last turn if it is the question {@code question} (its answer was cut to go on: the question that
     * continues it says it all again). Only the end of the history changes. Whether it was dropped.
     */
    public boolean forgetLast(String question) {
        List<Integer> s = userStarts();
        if (s.isEmpty() || !history.get(s.getLast()).content().equals(question)) {
            return false;
        }
        history.subList(s.getLast(), history.size()).clear();
        return true;
    }

    private List<Integer> userStarts() {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).role().equals("user")) {
                starts.add(i);
            }
        }
        return starts;
    }

    /** The estimated tokens of the history (0 without a budget). */
    public int tokens() {
        int n = 0;
        for (ChatMessage m : history) {
            n += tokens.applyAsInt(m.content());
        }
        return n;
    }

    public int size() {
        return history.size();
    }

    public void clear() {
        history.clear();
    }
}
