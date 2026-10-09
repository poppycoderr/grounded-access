package io.groundedaccess.answering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AnswerParserTest {

    private static final Set<String> EVIDENCE = Set.of("S1", "S2");

    @Test
    void keepsStatementsWhoseEveryCitationWasInThePrompt() {
        var parsed = AnswerParser.parse("""
                {"answerable": true, "statements": [
                  {"text": "EU employees receive two paid volunteer days.", "citations": ["S1"]},
                  {"text": " Unused days do not carry over. ", "citations": ["S2", "S1", "S2"]}]}""", EVIDENCE).orElseThrow();

        assertThat(parsed.answerable()).isTrue();
        assertThat(parsed.statements()).containsExactly(new Statement("EU employees receive two paid volunteer days.", List.of("S1")),
                new Statement("Unused days do not carry over.", List.of("S2", "S1")));
        assertThat(parsed.rejected()).isZero();
    }

    @Test
    void dropsStatementsThatCiteNothingOrSomethingThatWasNotShown() {
        var parsed = AnswerParser.parse("""
                {"answerable": true, "statements": [
                  {"text": "Supported.", "citations": ["S1"]},
                  {"text": "Cites evidence that was never in the prompt.", "citations": ["S9"]},
                  {"text": "Mixes a real and an invented citation.", "citations": ["S1", "S7"]},
                  {"text": "No citation at all.", "citations": []},
                  {"text": "Citations missing."},
                  {"text": "", "citations": ["S1"]},
                  {"text": "Citation is not a string.", "citations": [1]}]}""", EVIDENCE).orElseThrow();

        assertThat(parsed.statements()).extracting(Statement::text).containsExactly("Supported.");
        assertThat(parsed.rejected()).isEqualTo(6);
    }

    @Test
    void readsARefusal() {
        var parsed = AnswerParser.parse("{\"answerable\": false, \"statements\": []}", EVIDENCE).orElseThrow();

        assertThat(parsed.answerable()).isFalse();
        assertThat(parsed.statements()).isEmpty();
        assertThat(AnswerParser.parse("{\"answerable\": false}", EVIDENCE)).isPresent();
    }

    @Test
    void acceptsJsonInsideAMarkdownCodeFence() {
        var parsed = AnswerParser.parse("```json\n{\"answerable\": true, \"statements\": [{\"text\": \"Yes.\", \"citations\": [\"S2\"]}]}\n```", EVIDENCE).orElseThrow();

        assertThat(parsed.statements()).containsExactly(new Statement("Yes.", List.of("S2")));
    }

    @Test
    void anythingThatIsNotTheExpectedObjectIsNotAnAnswer() {
        for (String reply : List.of("", "The answer is two days [S1].", "[]", "{\"statements\": []}", "{\"answerable\": \"yes\", \"statements\": []}",
                "{\"answerable\": true, \"statements\": \"S1\"}", "{\"answerable\": true, \"statements\": [")) {
            assertThat(AnswerParser.parse(reply, EVIDENCE)).as(reply).isEmpty();
        }
    }
}
