import com.xuncorp.spc.core.queue.PlaybackQueue;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.flow.StateFlow;

/** Inspect the real queue with synthetic strings; never load a player or playback service. */
public final class QueueAdditionResearchProbe {
    private static final Class<?> QUEUE = PlaybackQueue.class;
    private static final Method INSERT = List.of(QUEUE.getMethods()).stream().filter(method -> {
        Class<?>[] types = method.getParameterTypes();
        return types.length == 3 && types[0].isEnum() && types[1] == List.class
            && types[2] == Continuation.class;
    }).reduce((a, b) -> { throw new IllegalStateException("Ambiguous insert method"); }).orElseThrow();
    private static final Method SET = List.of(QUEUE.getMethods()).stream().filter(method -> {
        Class<?>[] types = method.getParameterTypes();
        return types.length == 3 && types[0] == List.class && types[1] == int.class
            && types[2] == Continuation.class;
    }).reduce((a, b) -> { throw new IllegalStateException("Ambiguous set method"); }).orElseThrow();
    private static final Method STATE = List.of(QUEUE.getMethods()).stream().filter(method ->
        method.getParameterCount() == 0 && method.getReturnType() == StateFlow.class
    ).findFirst().orElseThrow();
    private static final Continuation<Object> IMMEDIATE = new Continuation<>() {
        public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
        public void resumeWith(Object result) { throw new AssertionError("Unexpected asynchronous continuation"); }
    };

    private static void call(Method method, Object queue, Object... arguments) throws Exception {
        Object result = method.invoke(queue, arguments);
        if (result == IntrinsicsKt.getCOROUTINE_SUSPENDED()) throw new AssertionError("Unexpected suspension");
    }

    private static void insert(Object queue, String position, String item) throws Exception {
        Object mode = List.of(INSERT.getParameterTypes()[0].getEnumConstants()).stream()
            .filter(value -> ((Enum<?>) value).name().equals(position)).findFirst().orElseThrow();
        call(INSERT, queue, mode, List.of(item), IMMEDIATE);
    }

    private static void check(Object queue, String label, List<String> expected) throws Exception {
        Object state = ((StateFlow<?>) STATE.invoke(queue)).getValue();
        // These two getter names are inspected in the local 1.19.3 bytecode, research only.
        List<?> entries = (List<?>) state.getClass().getMethod("\u0528").invoke(state);
        List<String> actual = new ArrayList<>();
        for (Object entry : entries) actual.add((String) entry.getClass().getMethod("\u0528").invoke(entry));
        if (!actual.equals(expected)) throw new AssertionError(label + ": " + actual + " != " + expected);
        System.out.println("PASS " + label + ": " + actual);
    }

    public static void main(String[] args) throws Exception {
        PlaybackQueue<String> queue = new PlaybackQueue<>((left, right) -> left.equals(right), item -> item);
        call(SET, queue, List.of("A", "B", "C"), 0, IMMEDIATE);
        insert(queue, "AfterLastAdded", "X");
        check(queue, "after-last-added first insertion", List.of("A", "X", "B", "C"));
        insert(queue, "AfterLastAdded", "Y");
        check(queue, "after-last-added preserves insertion order", List.of("A", "X", "Y", "B", "C"));
        insert(queue, "AtQueueEnd", "Z");
        check(queue, "queue-end appends", List.of("A", "X", "Y", "B", "C", "Z"));
        insert(queue, "AtQueueEnd", "B");
        check(queue, "existing noncurrent item moves to end", List.of("A", "X", "Y", "C", "Z", "B"));
        insert(queue, "AtQueueEnd", "A");
        check(queue, "current item is not added again", List.of("A", "X", "Y", "C", "Z", "B"));
        PlaybackQueue<String> empty = new PlaybackQueue<>((left, right) -> left.equals(right), item -> item);
        insert(empty, "AtQueueEnd", "Q");
        check(empty, "empty queue accepts the first item", List.of("Q"));
        System.out.println("Only the generic queue ran; no real library, PlaybackController or audio engine was used.");
    }
}
