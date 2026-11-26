package de.boondocksulfur.christmas.util;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Changes single values in a YAML file in place; comments and all other lines stay as they are. */
public final class YamlValues {

    private YamlValues() {
    }

    /**
     * Replaces the scalar at {@code path} (dot separated) with {@code value}.
     *
     * @return {@code false} if the key does not exist in the file or is not a plain value
     */
    public static boolean setValue(File file, String path, String value) throws IOException {
        Path target = file.toPath();
        String text = Files.readString(target, StandardCharsets.UTF_8);
        Node node = new Yaml(new LoaderOptions()).compose(new StringReader(text));
        for (String part : path.split("\\.")) {
            if (!(node instanceof MappingNode mapping)) return false;
            node = null;
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(part)) {
                    node = tuple.getValueNode();
                    break;
                }
            }
            if (node == null) return false;
        }
        if (!(node instanceof ScalarNode)) return false;
        // Marks count code points; convert to char offsets for strings with non-BMP characters
        int start = text.offsetByCodePoints(0, node.getStartMark().getIndex());
        int end = text.offsetByCodePoints(0, node.getEndMark().getIndex());
        String updated = text.substring(0, start) + value + text.substring(end);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, updated, StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }
}
