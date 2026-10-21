#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const NO_AI =
  "Cette PR n'utilise pas d'outils d'IA générative.";
export const AI_USED =
  "J'ai utilisé des outils d'IA générative pour contribuer à cette PR.";
export const REVIEWED =
  "Je confirme avoir relu, compris et validé les modifications proposées par l'IA.";

const ITEM = /^\s*-\s*\[([ xX])\]\s*(.*?)\s*$/;

function items(body) {
  const found = [];
  for (const line of String(body ?? "").split(/\r?\n/)) {
    const match = line.match(ITEM);
    if (match) {
      found.push({ checked: match[1].toLowerCase() === "x", text: match[2] });
    }
  }
  return found;
}

export function validateAiDisclosure(body) {
  const found = items(body);
  const byText = (text) => found.filter((item) => item.text === text);

  for (const text of [NO_AI, AI_USED, REVIEWED]) {
    const count = byText(text).length;
    if (count !== 1) {
      return {
        ok: false,
        message:
          `The AI disclosure checklist must contain exactly one "${text}" line (found ${count}). ` +
          "Restore the pull request template's Utilisation de l'IA section and try again.",
      };
    }
  }

  const noAiChecked = byText(NO_AI)[0].checked;
  const aiUsedChecked = byText(AI_USED)[0].checked;
  const reviewedChecked = byText(REVIEWED)[0].checked;

  if (noAiChecked === aiUsedChecked) {
    return {
      ok: false,
      message:
        "Check exactly one AI usage choice: either " +
        '"Cette PR n\'utilise pas d\'outils d\'IA générative." ' +
        'or "J\'ai utilisé des outils d\'IA générative pour contribuer à cette PR."',
    };
  }

  if (aiUsedChecked && !reviewedChecked) {
    return {
      ok: false,
      message:
        "You declared AI use, so you must also check: " +
        `"${REVIEWED}" ` +
        "(review, understand and validate the AI-assisted changes).",
    };
  }

  if (noAiChecked && reviewedChecked) {
    return {
      ok: false,
      message:
        "Contradictory declaration: no AI was used, but the human review acknowledgment is checked. " +
        `Uncheck "${REVIEWED}".`,
    };
  }

  return {
    ok: true,
    message: noAiChecked
      ? "No AI use declared; the human review acknowledgment is correctly unchecked."
      : "AI use declared and the human review acknowledgment is checked.",
  };
}

function main() {
  const body = process.env.PR_BODY ?? (process.argv[2] ? readFileSync(process.argv[2], "utf8") : "");
  const result = validateAiDisclosure(body);
  if (!result.ok) {
    console.error(`::error title=AI disclosure::${result.message}`);
    process.exit(1);
  }
  console.log(result.message);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
