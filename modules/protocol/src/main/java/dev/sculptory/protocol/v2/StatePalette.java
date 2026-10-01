package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-message block-state palette: {@code count | format(state)...}. Message bodies refer to states by
 * palette index, so raw handles (which differ between client and server) never cross the wire.
 */
final class StatePalette {
    /** Most distinct states one message may carry. */
    static final int MAX_ENTRIES = 4096;

    private StatePalette() {}

    /** Encoding side: assigns indices in first-use order. */
    static final class Builder {
        private final StateSpace states;
        private final Map<Integer, Integer> indices = new HashMap<>();
        private final List<String> specs = new ArrayList<>();

        Builder(StateSpace states) {
            if (states == null) throw new IllegalArgumentException("A StateSpace is needed to encode block states");
            this.states = states;
        }

        /** The palette index of {@code handle}. Throws for a handle outside the state space (a caller bug). */
        int indexOf(int handle) throws ProtocolException {
            Integer index = indices.get(handle);
            if (index != null) return index;
            if (specs.size() >= MAX_ENTRIES) {
                throw new ProtocolException(ProtocolException.Reason.TOO_LARGE, "Over " + MAX_ENTRIES + " block states");
            }
            specs.add(states.format(handle));
            indices.put(handle, specs.size() - 1);
            return specs.size() - 1;
        }

        void writeTo(WireWriter out) throws ProtocolException {
            out.count(specs.size(), MAX_ENTRIES, "block palette");
            for (String spec : specs) out.string(spec, BlockDescriptor.MAX_SPEC_BYTES, "block state");
        }
    }

    /** Decoding side: palette index to local handle. */
    static final class Table {
        private final int[] handles;
        /** The space the handles were resolved in ({@code null} only for an empty palette). */
        private final StateSpace states;

        private Table(int[] handles, StateSpace states) {
            this.handles = handles;
            this.states = states;
        }

        /** The state space the palette was read with; {@code null} when the palette is empty and none was given. */
        StateSpace states() {
            return states;
        }

        static Table read(WireReader in, StateSpace states) throws ProtocolException {
            int count = in.count(MAX_ENTRIES, "block palette");
            if (count > 0 && states == null) {
                throw new ProtocolException(ProtocolException.Reason.UNKNOWN_STATE, "No StateSpace to resolve block states");
            }
            int[] handles = new int[count];
            for (int i = 0; i < count; i++) {
                String spec = in.string(BlockDescriptor.MAX_SPEC_BYTES, "block state");
                int handle;
                try {
                    handle = states.parse(spec);
                } catch (RuntimeException e) {
                    handle = -1;
                }
                if (handle < 0 || handle >= states.size()) {
                    throw new ProtocolException(ProtocolException.Reason.UNKNOWN_STATE, "Unknown block state: " + clip(spec));
                }
                handles[i] = handle;
            }
            return new Table(handles, states);
        }

        int handle(int index) throws ProtocolException {
            if (index < 0 || index >= handles.length) throw WireReader.malformed("Palette index " + index + " out of range");
            return handles[index];
        }

        int readHandle(WireReader in) throws ProtocolException {
            return handle(in.varint());
        }
    }

    private static String clip(String spec) {
        return spec.length() <= 120 ? spec : spec.substring(0, 120) + "...";
    }
}
