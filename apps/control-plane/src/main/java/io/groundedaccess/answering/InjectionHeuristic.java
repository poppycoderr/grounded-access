package io.groundedaccess.answering;

import java.util.regex.Pattern;

/**
 * Flags evidence that reads like an instruction to a language model. It is a keyword heuristic and nothing more: it catches the obvious
 * phrasings and is trivially evaded by rewording, so it only labels evidence for the reader and for audit counts. It never removes a passage
 * and is not a defence; the defence is that the model's reply is validated.
 */
public final class InjectionHeuristic {

    public static final String FLAG = "instruction_like";

    private static final Pattern INSTRUCTION = Pattern.compile(
            "ignore (all |any )?(the )?(previous|prior|above) instructions|disregard (all |any )?(the )?(previous|prior|above)"
                    + "|system (note|prompt|message)|note to (the )?(ai|assistant)|if you are an? (ai|language model|assistant)"
                    + "|as an ai (assistant|model)|you must (now )?(reply|respond|answer) with",
            Pattern.CASE_INSENSITIVE);

    private InjectionHeuristic() {
    }

    public static boolean looksLikeInstruction(String text) {
        return INSTRUCTION.matcher(text).find();
    }
}
