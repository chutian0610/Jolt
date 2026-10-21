package io.github.chutian0610.jolt.tree;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tree loading entry points. Reuses the verified core parser (both engines, depth limit,
 * boundary-exception contract) and drains it into a {@link Node} tree. Iterative — no
 * recursion on document nesting depth.
 */
public final class Tree {

    private Tree() {}

    /** Loads a UTF-8 JSON document into a {@link Node} tree. */
    public static Node parse(byte[] json) {
        return load(Jolt.parse(json));
    }

    /** Loads a String document on the UTF-16 engine — no transcoding bridge. */
    public static Node parse(String json) {
        return load(Jolt.parse(json));
    }

    /** Loads a UTF-8 document under an explicit configuration (lenient switches, depth limit). */
    public static Node parse(byte[] json, io.github.chutian0610.jolt.JoltConfig config) {
        return load(Jolt.parse(json, config));
    }

    /** Loads a String document under an explicit configuration. */
    public static Node parse(String json, io.github.chutian0610.jolt.JoltConfig config) {
        return load(Jolt.parse(json, config));
    }

    static Node load(JoltParser parser) {
        Deque<Node> containers = new ArrayDeque<>();
        Node root = null;
        String pendingKey = null;
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            if (t == Token.FIELD_NAME) {
                pendingKey = parser.textValue();
                continue;
            }
            Node value = scalarOrNull(parser, t);
            if (value == null) {
                if (t == Token.START_OBJECT) {
                    value = Node.newObject();
                } else if (t == Token.START_ARRAY) {
                    value = Node.newArray();
                } else if (t == Token.END_OBJECT || t == Token.END_ARRAY) {
                    // Nested containers were attached to their parent at open time.
                    Node closed = containers.pop();
                    if (containers.isEmpty()) {
                        root = closed;
                    }
                    continue;
                } else {
                    throw new IllegalStateException("unexpected token " + t);
                }
            }
            if (containers.isEmpty()) {
                root = value;
            } else {
                attach(containers.peek(), pendingKey, value);
                pendingKey = null;
            }
            if (value.type() == TypeTag.OBJECT || value.type() == TypeTag.ARRAY) {
                containers.push(value);
            }
        }
        if (root == null) {
            throw new IllegalStateException("empty document");
        }
        return root;
    }

    private static void attach(Node container, String key, Node value) {
        if (container.type() == TypeTag.OBJECT) {
            container.put(key, value);
        } else {
            container.add(value);
        }
    }

    /** Builds the node for a scalar token, or null when the token opens/closes a container. */
    private static Node scalarOrNull(JoltParser parser, Token t) {
        return switch (t) {
            case VALUE_STRING -> Node.ofString(parser.textValue());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> Node.ofNumber(parser.numberText());
            case VALUE_TRUE -> Node.ofBoolean(true);
            case VALUE_FALSE -> Node.ofBoolean(false);
            case VALUE_NULL -> Node.ofNull();
            default -> null;
        };
    }
}
