package io.groundedaccess.corpus;

/**
 * Formats vectors as pgvector text literals ({@code [0.1,0.2]}), bound as a parameter and cast in SQL.
 */
public final class Vectors {

    private Vectors() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            literal.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
