package de.boondocksulfur.christmas.util;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Adds keys that are missing in a server YAML file from the bundled default of the same name.
 *
 * <p>Missing keys are inserted with their comments next to their neighbours; existing keys,
 * values and formatting are never changed or removed. The file is only rewritten when
 * something was added, and the previous version is kept as {@code <name>.old}.
 */
public final class ConfigMerger {

    private ConfigMerger() {
    }

    /** Result of a merge: the keys that were added, or the reason nothing could be added. */
    public record Result(List<String> addedKeys, List<String> skippedKeys, String error) {
        static Result none() {
            return new Result(List.of(), List.of(), null);
        }
    }

    /**
     * Merges the bundled {@code defaults} into {@code file}. A missing, empty or unreadable
     * file is left untouched.
     */
    public static Result merge(File file, InputStream defaults) {
        if (defaults == null || !file.isFile()) return Result.none();
        try (InputStream in = defaults) {
            String defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Path path = file.toPath();
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.isBlank()) return Result.none();

            List<String> found = new ArrayList<>();
            String merged = mergeMissing(text, defaultText, found);
            if (found.isEmpty()) return Result.none();
            if (merged == null) return new Result(List.of(), List.copyOf(found), null);

            Files.copy(path, path.resolveSibling(file.getName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            Path temp = path.resolveSibling(file.getName() + ".tmp");
            Files.writeString(temp, merged, StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Result(List.copyOf(found), List.of(), null);
        } catch (IOException | RuntimeException e) {
            return new Result(List.of(), List.of(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * Inserts the text blocks of keys missing in {@code text} (including the comments above
     * them) after their preceding sibling. Everything else stays byte for byte the same.
     *
     * @return the merged text, {@code text} itself if nothing is missing, or {@code null} if the
     *         result would change existing values (unusual layout); {@code found} then still lists
     *         the missing keys
     */
    static String mergeMissing(String text, String defaultText, List<String> found) {
        Yaml yaml = yaml();
        Node userRoot = yaml.compose(new StringReader(text));
        Node defaultRoot = yaml.compose(new StringReader(defaultText));
        if (!(userRoot instanceof MappingNode user)) throw new IllegalArgumentException("top level is not a mapping");
        if (!(defaultRoot instanceof MappingNode defaults)) return text;

        String[] userLines = text.split("\n", -1);
        String[] defaultLines = defaultText.split("\n", -1);
        // insertion line (insert before this index) -> blocks in order
        TreeMap<Integer, List<String>> inserts = new TreeMap<>();
        collect(user, defaults, -1, -1, "", userLines, defaultLines, inserts, found);
        if (found.isEmpty()) return text;

        List<String> out = new ArrayList<>(List.of(userLines));
        for (Map.Entry<Integer, List<String>> entry : inserts.descendingMap().entrySet()) {
            List<String> block = new ArrayList<>();
            entry.getValue().forEach(b -> block.addAll(List.of(b.split("\n", -1))));
            out.addAll(entry.getKey(), block);
        }
        String merged = String.join("\n", out);

        if (!containsAll(parseMap(merged), parseMap(text))) return null;
        return merged;
    }

    private static void collect(MappingNode user, MappingNode defaults, int userParentLine, int defaultParentLine,
                                String prefix, String[] userLines, String[] defaultLines,
                                TreeMap<Integer, List<String>> inserts, List<String> found) {
        int previousDefaultEnd = defaultParentLine;
        int insertAt = userParentLine + 1;
        for (NodeTuple defaultTuple : defaults.getValue()) {
            String key = keyOf(defaultTuple);
            int defaultEnd = lastLine(defaultTuple.getValueNode(), defaultLines);
            if (key == null) {
                previousDefaultEnd = defaultEnd;
                continue;
            }
            NodeTuple userTuple = find(user, key);
            if (userTuple == null) {
                StringBuilder block = new StringBuilder();
                for (int i = previousDefaultEnd + 1; i <= defaultEnd; i++) {
                    if (!block.isEmpty()) block.append('\n');
                    block.append(defaultLines[i]);
                }
                inserts.computeIfAbsent(insertAt, k -> new ArrayList<>()).add(block.toString());
                found.add(prefix + key);
            } else {
                insertAt = lastLine(userTuple.getValueNode(), userLines) + 1;
                if (userTuple.getValueNode() instanceof MappingNode userChild
                        && defaultTuple.getValueNode() instanceof MappingNode defaultChild
                        && userChild.getFlowStyle() == DumperOptions.FlowStyle.BLOCK
                        && defaultChild.getFlowStyle() == DumperOptions.FlowStyle.BLOCK) {
                    collect(userChild, defaultChild,
                            userTuple.getKeyNode().getStartMark().getLine(),
                            defaultTuple.getKeyNode().getStartMark().getLine(),
                            prefix + key + ".", userLines, defaultLines, inserts, found);
                }
            }
            previousDefaultEnd = defaultEnd;
        }
    }

    /** Last line that belongs to the node, ignoring trailing blank and comment lines. */
    private static int lastLine(Node node, String[] lines) {
        int line = node.getEndMark().getLine();
        int column = node.getEndMark().getColumn();
        if (line >= lines.length) {
            line = lines.length - 1;
        } else if (lines[line].substring(0, Math.min(column, lines[line].length())).isBlank()) {
            // Block collections end at the first token of the next entry
            line--;
        }
        int start = node.getStartMark().getLine();
        while (line > start && isBlankOrComment(lines[line])) line--;
        return line;
    }

    private static boolean isBlankOrComment(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    /** True if every value of {@code subset} is present and equal in {@code map}. */
    private static boolean containsAll(Map<?, ?> map, Map<?, ?> subset) {
        for (Map.Entry<?, ?> entry : subset.entrySet()) {
            Object other = map.get(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> sub) {
                if (!(other instanceof Map<?, ?> otherMap) || !containsAll(otherMap, sub)) return false;
            } else if (!Objects.equals(value, other)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String text) {
        Object loaded = yaml().load(text);
        if (loaded == null) return Map.of();
        if (!(loaded instanceof Map<?, ?>)) throw new IllegalArgumentException("top level is not a mapping");
        return (Map<String, Object>) loaded;
    }

    private static NodeTuple find(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (key.equals(keyOf(tuple))) return tuple;
        }
        return null;
    }

    private static String keyOf(NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : null;
    }

    private static Yaml yaml() {
        LoaderOptions loader = new LoaderOptions();
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }
}
