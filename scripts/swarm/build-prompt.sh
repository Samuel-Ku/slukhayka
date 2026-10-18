#!/usr/bin/env bash
#
# Друкує контракт задачі для воркера сварму: JSON тікета зі stdin -> промпт у
# stdout. Винесено окремо від run-issue.sh, бо це ЄДИНА частина диспетчеризації,
# яку можна перевірити без мережі, gh і ssh (scripts/tests/test_swarm_dispatch.py).
#
# Межі, названі тут:
#   * промпт не обіцяє відповідача: у unattended-режимі `ask_user_question`
#     падає fail-closed, тож агент зобов'язаний вирішити сам і записати рішення;
#   * перелік спільних файлів у промпті — не порада, а межа: зміна будь-якого з
#     них переводить вибір тестів у ПОВНИЙ набір (scripts/test-changed.sh), а
#     трьом смугам у спільному файлі нема чого робити;
#   * его не згадується як «бажано»: пуш без зеленого цільового прогону
#     заборонений, а частковий результат лишається звітом, не гілкою.
#
# Виклик:
#   gh issue view <n> --json number,title,body | scripts/swarm/build-prompt.sh \
#     --issue <n> --worker <host> --slug <slug> --base <sha> [--owner id] [--timeout-min N]
set -euo pipefail

ISSUE=""; WORKER=""; SLUG=""; BASE=""; OWNER="swarm-hub"; TIMEOUT_MIN=90
while [ $# -gt 0 ]; do
  case "$1" in
    --issue) ISSUE="${2:?}"; shift 2 ;;
    --worker) WORKER="${2:?}"; shift 2 ;;
    --slug) SLUG="${2:?}"; shift 2 ;;
    --base) BASE="${2:?}"; shift 2 ;;
    --owner) OWNER="${2:?}"; shift 2 ;;
    --timeout-min) TIMEOUT_MIN="${2:?}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
for need in ISSUE WORKER SLUG BASE; do
  [ -n "${!need}" ] || { echo "missing --${need,,}" >&2; exit 2; }
done

JSON="$(cat)"
TITLE="$(printf '%s' "$JSON" | jq -r '.title')"
BODY="$(printf '%s' "$JSON" | jq -r '.body')"
[ "$TITLE" != "null" ] || { echo "stdin is not an issue JSON (no .title)" >&2; exit 2; }

cat <<EOF
Ти — виконавець сварму DSH на машині $WORKER. Працюєш автономно, без людини
поруч: питання ставити нікуди (у цьому режимі ask_user_question не має
відповідача), тому неоднозначність ти розв'язуєш сам і записуєш рішення в
коментар до тікета.

ТІКЕТ #$ISSUE — $TITLE
База: origin/main ${BASE:0:12}

--- тіло тікета ---
$BODY
--- кінець тіла ---

ПОРЯДОК РОБОТИ
1. cd \$REPO_DIR (типовий шлях ~/swarm/slukhayka), потім
   NEW_LANE_OWNER=$OWNER scripts/new-lane.sh $ISSUE $SLUG
   Він бере свіжий origin/main, створює .worktrees/w$ISSUE і гілку t$ISSUE-$SLUG.
   Працюй ТІЛЬКИ в цій копії смуги. План не передавай: бриф — це цей текст.
2. Прочитай AGENTS.md, CONTEXT.md (це глосарій репозиторію), CONTRIBUTING.md і
   дотичні docs/adr/. Рішення, які код не пояснює сам, назви в коментарі.
   Увага: GLOSSARY.md і CODING_STANDARDS.md у репозиторії НЕМАЄ (останній існує
   лише в дереві координатора як незакомічена робота) — не витрачай час на їх
   пошук і не вважай їх відсутність дефектом тікета.
3. Зроби зміни й доведи їх: scripts/test-changed.sh (він сам вибирає найвужчий
   набір). Зелений цільовий прогін — обов'язкова умова пушу.
   Якщо диф чіпає еталони (app/src/test/snapshots/**), рядки
   (app/src/main/res/values*/strings*.xml) або lint-baseline.xml — додатково
   прогони класи-сторожі з app/src/test/java/com/slukhayka/audiobooks/testing/:
   вони пінять байти, підписи й покриття і в звужений вибір НЕ потрапляють.
   Щонайменше цей:
     ../gt ./gradlew --offline :app:testPureJvm -Ptest.selectedClasses=com.slukhayka.audiobooks.testing.SnapshotGoldenBytesGuardTest
   На #1163 саме він упав у CI, хоч локальний звужений прогін був зелений: три
   піни дев'яти «дрейфонебезпечних» еталонів лишились старими. Оновлення піна —
   свідома правка з причиною в тому ж коміті, а не «зробити зелено».
   Якщо .worktrees/gt (машино-локальна обгортка слота з
   docs/agents/local-environment.md) у цьому клоні відсутня — не вигадуй її
   вміст і не запускай ./gradlew голим: візьми той самий слот сам. Обидві
   команди нижче — з кореня КЛОНА (там, де лежить .worktrees/), а не з копії
   смуги: у самій смузі шляху .worktrees/ немає.
     cd "\$REPO_DIR" && flock -w 7200 "\$REPO_DIR/.worktrees/.build-slot" \
       python3 "\$REPO_DIR/scripts/build-slot-guard.py" --floor-mb 3500 -- \
       "\$REPO_DIR/scripts/test-changed.sh"
   Поріг 3500 МБ — не «зручний», а той, що його бере обгортка для всього, що
   стартує Gradle (docs/agents/local-environment.md: «wedge line, not a comfort
   line»); 2000 МБ пропустив би збірку в машину, яка вже на межі.
   і назви в коментарі, що обгортки не було (це факт середовища, а не твій збій).
4. Пуш лише своєї гілки: git push -u origin t$ISSUE-$SLUG

МЕЖІ (порушення = провал задачі)
  * НЕ чіпай main, не роби merge, не роби force-push, не чіпай чужі гілки.
  * НЕ відкривай PR: гілку пушить воркер, PR відкриває й описує hub — після
    власного ревʼю diff-а. Відкритий воркером PR — це другий опис тієї самої
    роботи, який координатор не перевіряв (у пілоті це сталося на #1345).
  * НЕ змінюй спільні файли: build.gradle.kts, gradle/, scripts/, .github/,
    app/schemas/. Якщо виправлення без них неможливе — зупинись і напиши це.
  * Емулятор і instrumented-тести тут недоступні: тільки unit-тести
    (pure-jvm / room-robolectric / compose-roborazzi).
  * Не «поліпшуй» сусідній код поза тікетом.

ЗВІТНІСТЬ
  * На старті: gh issue comment $ISSUE — факти смуги (гілка, база, копія).
  * Наприкінці: той самий коментар — що змінено, який прогін це довів, що
    лишилось недоведеним.
  * Жорсткий ліміт задачі: $TIMEOUT_MIN хв (ріже hub, не ти).
  * Останній рядок твого stdout — рівно:
    SWARM-RESULT: ok | ok-partial | blocked — <одне речення>
EOF
