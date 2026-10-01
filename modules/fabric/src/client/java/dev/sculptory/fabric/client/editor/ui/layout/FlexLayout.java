package dev.sculptory.fabric.client.editor.ui.layout;

/** Pure main-axis size distribution used by {@link Row} and {@link Column}. */
public final class FlexLayout {
    private FlexLayout() {
    }

    /**
     * Splits {@code available} pixels (including {@code gap} between items) among items.
     * Spare space goes to items with a positive {@code grow}, in proportion. When space is short,
     * items shrink in proportion to how far they are above their minimum, never below it; if the
     * minimums alone don't fit, items stay at their minimums and overflow.
     */
    public static int[] distribute(int available, int gap, int[] preferred, int[] minimum, float[] grow) {
        int count = preferred.length;
        int[] sizes = preferred.clone();
        if (count == 0) {
            return sizes;
        }
        int space = available - gap * (count - 1);
        int total = 0;
        for (int size : preferred) {
            total += size;
        }
        if (space > total) {
            growInto(sizes, space - total, grow);
        } else if (space < total) {
            shrink(sizes, total - space, minimum);
        }
        return sizes;
    }

    private static void growInto(int[] sizes, int extra, float[] grow) {
        float totalGrow = 0;
        for (float weight : grow) {
            totalGrow += weight;
        }
        if (totalGrow <= 0) {
            return;
        }
        int given = 0;
        int last = -1;
        for (int i = 0; i < sizes.length; i++) {
            if (grow[i] > 0) {
                int share = (int) Math.floor(extra * (grow[i] / totalGrow));
                sizes[i] += share;
                given += share;
                last = i;
            }
        }
        sizes[last] += extra - given;
    }

    private static void shrink(int[] sizes, int deficit, int[] minimum) {
        long slack = 0;
        for (int i = 0; i < sizes.length; i++) {
            slack += Math.max(0, sizes[i] - minimum[i]);
        }
        if (slack == 0) {
            return;
        }
        int take = (int) Math.min(deficit, slack);
        int taken = 0;
        int[] cuts = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            int itemSlack = Math.max(0, sizes[i] - minimum[i]);
            cuts[i] = (int) (take * (long) itemSlack / slack);
            taken += cuts[i];
        }
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] -= cuts[i];
        }
        int remainder = take - taken;
        for (int i = sizes.length - 1; i >= 0 && remainder > 0; i--) {
            int room = sizes[i] - minimum[i];
            if (room > 0) {
                int cut = Math.min(room, remainder);
                sizes[i] -= cut;
                remainder -= cut;
            }
        }
    }
}
