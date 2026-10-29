package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.GeneratedFields;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.tree.Node;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The base class every generated binder extends: one public no-arg constructor (ByteBuddy
 * subclasses inherit it) plus {@code assignPlan} to bake the per-type plan; parse and write
 * execute the plan. Generated subclasses carry no logic of their own — the per-type identity
 * is the baked plan, and all semantics live here, testable directly.
 */
public class GenBinderBase implements TypedBinder<Object>, GeneratedFields {

    GenPlan<?> plan;

    public GenBinderBase() {}

    void assignPlan(GenPlan<?> plan) {
        this.plan = plan;
    }

    @Override
    public Object parse(Node node, String path, BindConfig config) {
        if (plan == null) {
            throw new JsonBindException("generated binder was not assigned a plan");
        }
        if (plan.isRecord) {
            return parseRecord(node, path, config);
        }
        return parsePojo(node, path, config);
    }

    /**
     * The nested form of the direct read: the CALLER has already consumed this value's
     * START_OBJECT off the shared parser stream and owns the path position, the drain, and
     * the cursor — the body continues where the caller stopped (the M6
     * {@code readRecordBody} contract, cross-package). Capable generated classes override
     * it with the emitted body; the parent's nested dispatch consults the child's emitted
     * shape at generation time, so the default is a tripwire, not a fallback.
     */
    public Object parseTokensBody(JoltParser parser, BindConfig config) {
        throw new UnsupportedOperationException(
                getClass().getName() + " carries no direct body");
    }

    @Override
    public void write(Object value, JoltWriter writer) {
        // Full-document contract: the binder owns the enclosing object braces.
        writer.startObject();
        writeFields(value, writer, "$");
        writer.endObject();
    }

    @Override
    public void writeFields(Object value, JoltWriter writer, String path) {
        for (int i = 0; i < plan.accessors.length; i++) {
            String name = plan.isRecord ? plan.names[i]
                    : propertyName(plan.accessors[i].getName());
            Object fieldValue;
            try {
                fieldValue = plan.accessors[i].invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot read property " + name, path);
            }
            writer.fieldName(name);
            Bind.writeTo(fieldValue, writer, path + "." + name);
        }
    }

    private Object parseRecord(Node node, String path, BindConfig config) {
        Object[] args = new Object[plan.names.length];
        for (int i = 0; i < plan.names.length; i++) {
            args[i] = Bind.toValue(node.get(plan.names[i]), plan.genericTypes[i],
                    path + "." + plan.names[i], config);
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                boolean known = false;
                for (String name : plan.names) {
                    known |= name.equals(key);
                }
                if (!known) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        try {
            return plan.constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException("record constructor rejected the bound values: "
                    + e.getCause(), path);
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot construct " + plan.type.getName() + ": " + e, path);
        }
    }

    private Object parsePojo(Node node, String path, BindConfig config) {
        Object instance;
        try {
            instance = plan.constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + plan.type.getName(), path);
        }
        for (Method setter : plan.setters) {
            String mn = setter.getName();
            String key = Character.toLowerCase(mn.charAt(3)) + mn.substring(4);
            Node value = node.get(key);
            if (value == null) {
                continue;
            }
            Object bound = Bind.toValue(value, setter.getGenericParameterTypes()[0],
                    path + "." + key, config);
            try {
                setter.invoke(instance, bound);
            } catch (InvocationTargetException e) {
                throw new JsonBindException("setter rejected the bound value: " + e.getCause(),
                        path + "." + key);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot invoke setter for " + key, path + "." + key);
            }
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                if (!plan.nameSet.contains(key)) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        return instance;
    }


    private static String propertyName(String accessorName) {
        int prefix = accessorName.startsWith("is") ? 2 : 3;
        if (accessorName.length() <= prefix) {
            return accessorName; // record accessors named exactly like components
        }
        return Character.toLowerCase(accessorName.charAt(prefix))
                + accessorName.substring(prefix + 1);
    }
}
