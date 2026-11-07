import assert from "node:assert/strict";
import test from "node:test";
import {
  AI_USED,
  NO_AI,
  REVIEWED,
  validateAiDisclosure,
} from "./validate-ai-disclosure.mjs";

const box = (text, checked = false) => `- [${checked ? "x" : " "}] ${text}`;

function body({ noAi = false, aiUsed = false, reviewed = false, eol = "\n" } = {}) {
  return [
    "## Utilisation de l'IA",
    "",
    box(NO_AI, noAi),
    box(AI_USED, aiUsed),
    box(REVIEWED, reviewed),
  ].join(eol);
}

test("no AI use passes without the review acknowledgment", () => {
  const result = validateAiDisclosure(body({ noAi: true }));
  assert.equal(result.ok, true);
});

test("AI use passes when the human review acknowledgment is checked", () => {
  const result = validateAiDisclosure(body({ aiUsed: true, reviewed: true }));
  assert.equal(result.ok, true);
});

test("AI use without the human review acknowledgment fails", () => {
  const result = validateAiDisclosure(body({ aiUsed: true }));
  assert.equal(result.ok, false);
  assert.match(result.message, /must also check/);
});

test("no AI use with the human review acknowledgment fails as contradictory", () => {
  const result = validateAiDisclosure(body({ noAi: true, reviewed: true }));
  assert.equal(result.ok, false);
  assert.match(result.message, /Contradictory/);
});

test("both usage choices checked fail", () => {
  const result = validateAiDisclosure(
    body({ noAi: true, aiUsed: true, reviewed: true }),
  );
  assert.equal(result.ok, false);
  assert.match(result.message, /exactly one AI usage choice/);
});

test("no usage choice checked fails", () => {
  const result = validateAiDisclosure(body());
  assert.equal(result.ok, false);
  assert.match(result.message, /exactly one AI usage choice/);
});

test("a missing checklist item fails", () => {
  const result = validateAiDisclosure(
    [box(NO_AI, true), box(REVIEWED)].join("\n"),
  );
  assert.equal(result.ok, false);
  assert.match(result.message, /exactly one "J'ai utilisé/);
});

test("a duplicated checklist item fails", () => {
  const result = validateAiDisclosure(
    [body({ noAi: true }), box(NO_AI, true)].join("\n"),
  );
  assert.equal(result.ok, false);
  assert.match(result.message, /found 2/);
});

test("CRLF, indentation and uppercase X are accepted", () => {
  const result = validateAiDisclosure(
    [
      `  - [X] ${NO_AI}`,
      `\t- [ ] ${AI_USED}`,
      `  - [ ] ${REVIEWED}`,
    ].join("\r\n"),
  );
  assert.equal(result.ok, true);
});

test("an empty body fails with the actionable checklist message", () => {
  const result = validateAiDisclosure("");
  assert.equal(result.ok, false);
  assert.match(result.message, /pull request template/);
});
