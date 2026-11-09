import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Parse grouped settings with the installed host parser, without starting the player. */
public final class PreferenceGroupingProbe {
    public static void main(String[] args) throws Exception {
        Class<?> parser = Class.forName(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8));
        Method parse = Arrays.stream(parser.getDeclaredMethods()).filter(m ->
            Modifier.isStatic(m.getModifiers()) && m.getReturnType() == List.class &&
            Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class})).findFirst().orElseThrow();
        List<?> groups = (List<?>) parse.invoke(null, Files.readString(Path.of(args[1])));
        List<String> titles = List.of("常规", "显示", "动画", "更新与诊断");
        List<Integer> sizes = List.of(3, 5, 2, 2);
        List<Integer> savedSettings = List.of(2, 5, 2, 1);
        if (groups.size() != titles.size()) throw new AssertionError("Host lost settings groups: " + groups.size());
        int total = 0;
        for (int i = 0; i < groups.size(); i++) {
            Object group = groups.get(i);
            if (!hasString(group, titles.get(i)) || !hasString(group, "config.json"))
                throw new AssertionError("Group title/path mismatch: " + group);
            Method entries = Arrays.stream(group.getClass().getDeclaredMethods()).filter(m ->
                m.getParameterCount() == 0 && m.getReturnType() == List.class).findFirst().orElseThrow();
            List<?> items = (List<?>) entries.invoke(group);
            if (items.size() != sizes.get(i)) throw new AssertionError("Host lost settings entries: " + titles.get(i));
            int configured = 0;
            for (Object item : items) if (hasString(item, "config.json")) configured++;
            if (configured != savedSettings.get(i))
                throw new AssertionError("Saved settings no longer use the shared config file");
            total += items.size();
        }
        System.out.println("PASS installed host parsed four ordered groups, " + total +
            " preferences and 10 saved settings sharing config.json");
    }

    private static boolean hasString(Object value, String expected) throws Exception {
        for (Class<?> type = value.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String.class &&
                        field.trySetAccessible() && expected.equals(field.get(value))) return true;
            }
        }
        return false;
    }
}
