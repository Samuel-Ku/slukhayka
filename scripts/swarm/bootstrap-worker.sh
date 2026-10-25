#!/usr/bin/env bash
#
# Готує машину-воркер для ssh-сварму: усе в user-space, без sudo й без нових
# портів. Ідемпотентний — повторний запуск нічого не перевстановлює.
#
# Навіщо саме так. Ядро DSH (0.2.0-rc.2) не має ні worker-протоколу, ні
# ssh-бекенду для subprocess/fs: віддати задачу можна лише тим, що вже є —
# `dsh --profile headless` поверх `ssh -T` (задача зі stdin, вердикт кодом
# виходу). Тому воркеру потрібні рівно три речі понад системні: JDK 21 (репо
# вимагає його, а на воркері був лише 17), Android platform 36 (compileSdk 36)
# і сам dsh. Усе інше — ізоляція: власний GRADLE_USER_HOME, щоб обмеження
# воркера (workers.max=1, -Xmx2g, без parallel) не зачіпали інші проєкти цієї
# машини й не змушували її гудіти на всі ядра.
#
# Виклик (на воркері):
#   bash ~/swarm/bin/bootstrap-worker.sh
#
# Змінні (усі з дефолтами): SWARM_ROOT, REPO_URL, DSH_VERSION, SDKMANAGER.
set -euo pipefail

SWARM_ROOT="${SWARM_ROOT:-$HOME/swarm}"
JDK_DIR="$SWARM_ROOT/jdk-21"
SDK_DIR="${SDK_DIR:-$SWARM_ROOT/android-sdk}"
REPO_DIR="${REPO_DIR:-$SWARM_ROOT/slukhayka}"
GRADLE_HOME="$SWARM_ROOT/gradle-home"
LOG_DIR="$SWARM_ROOT/logs"
BIN_DIR="$SWARM_ROOT/bin"
REPO_URL="${REPO_URL:-https://github.com/Samuel-Ku/slukhayka.git}"
DSH_VERSION="${DSH_VERSION:-0.2.0-rc.2}"
SDKMANAGER="${SDKMANAGER:-/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager}"
JDK_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"

log() { printf '[bootstrap] %s\n' "$*"; }
die() { printf '[bootstrap] FAIL: %s\n' "$*" >&2; exit 1; }

# --check: лише читає й друкує по рядку на складову; ненульовий код, якщо хоч
# одна зламана. Токени ротуються, тому «колись працювало» — не доказ.
if [ "${1:-}" = "--check" ]; then
  bad=0
  ok()  { printf '[check] ok   %s\n' "$1"; }
  bad_() { printf '[check] FAIL %s\n' "$1"; bad=1; }
  [ -x "$JDK_DIR/bin/java" ] && ok "JDK 21" || bad_ "JDK 21 ($JDK_DIR)"
  ls -d "$SDK_DIR"/platforms/android-36* >/dev/null 2>&1 && ok "SDK android-36" || bad_ "SDK android-36"
  ls -d "$SDK_DIR"/build-tools/36* >/dev/null 2>&1 && ok "build-tools 36" || bad_ "build-tools 36"
  # --check запускають через `bash script` по ssh, без логін-шелла, тож PATH
  # без env.sh порожній і dsh «не знаходиться». Спершу підхоплюємо env.sh.
  [ -f "$HOME/swarm/env.sh" ] && . "$HOME/swarm/env.sh"
  command -v dsh >/dev/null 2>&1 && ok "dsh у PATH" || bad_ "dsh у PATH (немає env.sh?)"
  "$BIN_DIR/gh" auth status >/dev/null 2>&1 && ok "gh auth" || bad_ "gh auth (токен недійсний?)"
  [ -s "$HOME/.dsh/.credentials.yaml" ] && ok "ключ провайдера" || bad_ "ключ провайдера"
  [ -e "$REPO_DIR/.git" ] && ok "клон репозиторію" || bad_ "клон репозиторію"
  free_gb=$(df -BG --output=avail "$REPO_DIR" 2>/dev/null | tail -1 | tr -dc 0-9)
  [ "${free_gb:-0}" -ge 10 ] && ok "диск (${free_gb}G вільно)" || bad_ "диск (${free_gb:-?}G вільно)"
  # dsh-процеси — норма під час виконання задачі; сирота відрізняється тим, що
  # його нема кому прибрати. Порахувати це ззовні не можна, тож рапортуємо факт
  # і не валимо перевірку: справжня ознака сироти — смуга без резервування, і
  # її видно в реєстрі, а не тут.
  running=$(pgrep -fc '[d]sh --profile headless' 2>/dev/null || echo 0)
  if [ "${running:-0}" -eq 0 ]; then ok "немає активних dsh"; else ok "dsh-процесів: $running (норма під час задачі)"; fi
  exit "$bad"
fi

mkdir -p "$SWARM_ROOT" "$LOG_DIR" "$BIN_DIR" "$GRADLE_HOME"

# Неінтерактивна ssh-сесія не читає ~/.bashrc, тож ~/.npm-global/bin у PATH не
# потрапляє — а саме туди npm -g кладе `dsh`. Без цього рядка скрипт ставив
# пакет і одразу казав, що dsh немає.
NPM_PREFIX="$(npm config get prefix 2>/dev/null || true)"
[ -n "$NPM_PREFIX" ] || NPM_PREFIX="$HOME/.npm-global"
export PATH="$NPM_PREFIX/bin:$PATH"

# 1) JDK 21 ---------------------------------------------------------------
if [ -x "$JDK_DIR/bin/java" ]; then
  log "JDK 21 уже є: $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
else
  log "ставляю JDK 21 -> $JDK_DIR"
  tmp="$(mktemp -d)"
  curl -fsSL "$JDK_URL" -o "$tmp/jdk21.tar.gz" || die "не завантажився JDK 21"
  mkdir -p "$JDK_DIR"
  tar -xzf "$tmp/jdk21.tar.gz" -C "$JDK_DIR" --strip-components=1
  rm -rf "$tmp"
  [ -x "$JDK_DIR/bin/java" ] || die "JDK 21 розпакувався без bin/java"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"

# 1b) передумови, без яких контракт задачі не виконується ----------------
# Контракт вимагає від воркера автентифікований `gh` (коментар у тікеті — перший
# і останній крок задачі) і утиліти, які вживають раннер (`flock`, `nice`,
# `ionice`, `timeout`) та `new-lane.sh` (`jq`, `python3`). Без них задача впаде
# не відразу й не там, де причина: на голій Fedora відсутність `gh` виявилася
# саме так (перевірено на dragonborn).
missing=""
for c in python3 jq flock nice ionice timeout git npm curl unzip; do
  command -v "$c" >/dev/null 2>&1 || missing="$missing $c"
done
[ -n "$missing" ] && die "немає обов'язкових утиліт:$missing (постав їх у PATH воркера)"
log "передумови: базові утиліти на місці"

# 1c) Android command-line tools, якщо на машині їх немає ------------------
# Попередня версія мовчки покладалася на `/opt/android-sdk/...`: на машині, де
# SDK ніколи не ставили (гола Fedora, dragonborn), крок 2 просто не виконувався,
# і «готово» друкувалося без SDK. Тепер інструменти або вже є за явним шляхом,
# або ставляться в user-space.
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$PATH"
if [ ! -x "$SDKMANAGER" ]; then
  log "Android SDK немає за $SDKMANAGER — ставлю command-line tools"
  tmp="$(mktemp -d)"
  zip_name="$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml 2>/dev/null \
    | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | head -1 || true)"
  : "${zip_name:=commandlinetools-linux-13114758_latest.zip}"
  curl -fsSL "https://dl.google.com/android/repository/$zip_name" -o "$tmp/clt.zip" \
    || die "не завантажились command-line tools ($zip_name)"
  unzip -q "$tmp/clt.zip" -d "$tmp/clt" || die "не розпакувався zip command-line tools"
  rm -rf "$SWARM_ROOT/cmdline-tools/latest"
  mkdir -p "$SWARM_ROOT/cmdline-tools"
  mv "$tmp/clt/cmdline-tools" "$SWARM_ROOT/cmdline-tools/latest"
  SDKMANAGER="$SWARM_ROOT/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$SDKMANAGER" ] || die "sdkmanager не зʼявився після розпакування"
  rm -rf "$tmp"
  log "sdkmanager: $SDKMANAGER"
fi

# 1d) gh у user-space (контракт без нього не виконується) -----------------
# `gh` потрібен і для коментарів у тікеті, і для push (через `gh auth setup-git`).
# На голій машині його немає, а sudo недоступний (на dragonborn `sudo -n` — ні),
# тому ставимо бінарник у $BIN_DIR і додаємо його в PATH. Залогованість
# перевіряємо ПІСЛЯ встановлення — токен приносить hub окремо (`gh auth login
# --with-token` зі stdin, як і решту секретів), і без нього задача впала б на
# першому ж кроці контракту.
export PATH="$BIN_DIR:$PATH"
if ! command -v gh >/dev/null 2>&1; then
  log "gh немає — ставлю в $BIN_DIR"
  tmp="$(mktemp -d)"
  gh_tag="$(curl -fsSL https://api.github.com/repos/cli/cli/releases/latest 2>/dev/null \
    | jq -r '.tag_name // empty' || true)"
  : "${gh_tag:=v2.63.2}"
  gh_ver="${gh_tag#v}"
  curl -fsSL "https://github.com/cli/cli/releases/download/$gh_tag/gh_${gh_ver}_linux_amd64.tar.gz" \
    -o "$tmp/gh.tar.gz" || die "не завантажився gh $gh_tag"
  tar -xzf "$tmp/gh.tar.gz" -C "$tmp" || die "не розпакувався gh"
  install -m 755 "$tmp/gh_${gh_ver}_linux_amd64/bin/gh" "$BIN_DIR/gh" \
    || die "не поклав gh у $BIN_DIR"
  rm -rf "$tmp"
  log "gh: $(command -v gh)"
fi
command -v gh >/dev/null 2>&1 \
  || die "немає gh: контракт вимагає коментарів у тікеті"
gh auth status >/dev/null 2>&1 \
  || die "gh не залогований: hub має принести токен (gh auth login --with-token)"
log "передумови: gh залогований"

# 2) Android SDK ----------------------------------------------------------
# Беремо найновіші 36.x платформу й build-tools, які реально пропонує
# sdkmanager: compileSdk у репо — release(36) з minorApiLevel 1, і вгадувати
# точний пакет руками означає зламати збірку на першій же невідповідності.
if [ -x "$SDKMANAGER" ]; then
  # Перевіряємо САМЕ потрібний пакет, а не будь-яку теку в platforms/: машина з
  # android-35 (або взагалі без build-tools) пройшла б таку перевірку й зламала
  # збірку пізніше — саме тому питання не «чи є платформи», а «чи є 36».
  have_platform="$(ls -d "$SDK_DIR"/platforms/android-36* 2>/dev/null | head -1 || true)"
  have_tools="$(ls -d "$SDK_DIR"/build-tools/36* 2>/dev/null | head -1 || true)"
  if [ -n "$have_platform" ] && [ -n "$have_tools" ]; then
    log "Android SDK уже має потрібне: $(basename "$have_platform"), $(basename "$have_tools")"
  else
    log "ставлю Android SDK 36 -> $SDK_DIR"
    list="$LOG_DIR/sdkmanager-available.txt"
    "$SDKMANAGER" --sdk_root="$SDK_DIR" --list --channel=0 >"$list" 2>&1 || true
    platform="$(grep -oE 'platforms;android-36[.0-9]*' "$list" | sort -V | tail -1 || true)"
    build_tools="$(grep -oE 'build-tools;36[.0-9]*' "$list" | sort -V | tail -1 || true)"
    : "${platform:=platforms;android-36}"
    : "${build_tools:=build-tools;36.0.0}"
    log "пакети: platform-tools $platform $build_tools"
    yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >"$LOG_DIR/sdk-licenses.log" 2>&1 || true
    "$SDKMANAGER" --sdk_root="$SDK_DIR" platform-tools "$platform" "$build_tools" \
      >"$LOG_DIR/sdkmanager-install.log" 2>&1 \
      || { tail -25 "$LOG_DIR/sdkmanager-install.log" >&2; die "sdkmanager не встановив пакети"; }
  fi
else
  log "УВАГА: sdkmanager не знайдено в $SDKMANAGER — Android-збірка тут не працюватиме"
fi
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

# 3) dsh ------------------------------------------------------------------
if command -v dsh >/dev/null 2>&1; then
  log "dsh уже є: $(dsh --version 2>&1 | head -1)"
else
  log "ставлю @deepseek-ai/dsh@$DSH_VERSION (npm global, з lifecycle-скриптами)"
  # npm 11 блокує postinstall за замовчуванням, а dsh тримається на нативних
  # пакетах: node-pty і koffi без своїх скриптів не збираються, і dsh падає вже
  # на першому запуску. Список узято з попередження самого npm.
  npm install -g \
    --allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs \
    "@deepseek-ai/dsh@$DSH_VERSION" >"$LOG_DIR/npm-dsh.log" 2>&1 \
    || { tail -25 "$LOG_DIR/npm-dsh.log" >&2; die "npm не поставив dsh"; }
  command -v dsh >/dev/null 2>&1 || die "dsh не з'явився в PATH після npm -g"
fi

# 4) профіль headless -----------------------------------------------------
# `dsh --profile headless` розгортає профіль САМ (initProfile пише package.json,
# cordis.patch.yml і pnpm-workspace.yaml), але `--from-default-profile` для
# shipped-імені не годиться: «profile "headless" is shipped and cannot be a
# custom profile target». Тому розгортання робить `--dump-config`: він
# ініціалізує профіль, друкує складене дерево й виходить, не витративши жодного
# токена. Мінімум пакетів: base+headless — залежності самого dsh, тож
# node_modules і pnpm тут не потрібні (перший запуск нічого не довантажує).
if [ -f "$HOME/.dsh/profiles/headless/package.json" ]; then
  log "профіль headless уже є"
else
  log "розгортаю профіль headless"
  dsh --profile headless --dump-config >"$LOG_DIR/profile-dump.yml" 2>"$LOG_DIR/profile-dump.err" \
    || log "УВАГА: --dump-config вийшов з помилкою — див. $LOG_DIR/profile-dump.err"
fi
[ -f "$HOME/.dsh/profiles/headless/package.json" ] \
  && log "профіль headless на місці" \
  || log "УВАГА: профіль headless не створився — див. $LOG_DIR/profile-dump.err"

# Шар самого профілю: модель головного агента й маршрут провайдера. Рядок
# agent-default-model замінює config ЦІЛКОМ, тож базовий deepseek-flash тут не
# лишається; для opencode-go досить apiKeyEnv — список моделей дає каталог
# pi-ai. Ключ читається зі стору за іменем OPENCODE_API_KEY (у життєвому
# профілі є ще OPENCODE_GO_API_KEY, але його не посилається ЖОДЕН рядок — це
# пастка, а не альтернатива).
if [ -d "$HOME/.dsh/profiles/headless" ]; then
  cat >"$HOME/.dsh/profiles/headless/cordis.patch.yml" <<'EOF'
# Керується scripts/swarm/bootstrap-worker.sh — не редагувати руками.
- id: agent-default-model
  name: '@deepseek-ai/dsh-agent-default-model'
  config:
    provider: opencode-go
    model: deepseek-v4.1-flash
    reasoningEffort: high
- id: llm-pi-ai
  name: '@deepseek-ai/dsh-llm-pi-ai'
  config:
    providers:
      opencode-go:
        apiKeyEnv: OPENCODE_API_KEY
        models:
          - id: deepseek-v4.1-flash
            name: DeepSeek V4.1 Flash
            contextWindow: 1000000
            maxTokens: 384000
EOF
  log "записав cordis.patch.yml профілю (opencode-go/deepseek-v4.1-flash)"
fi

# 5) клон репо ------------------------------------------------------------
# HTTPS, а не ssh: на цій машині вже залогований `gh` з credential.helper
# store, а ssh-ключ до GitHub не доданий (перевірено: Permission denied).
if [ -d "$REPO_DIR/.git" ]; then
  git -C "$REPO_DIR" fetch origin main --quiet 2>/dev/null || true
  if [ "$(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD)" = main ] \
     && [ -z "$(git -C "$REPO_DIR" status --porcelain)" ]; then
    git -C "$REPO_DIR" merge --ff-only origin/main --quiet 2>/dev/null || true
  fi
  log "клон уже є: $(git -C "$REPO_DIR" rev-parse --short HEAD)"
else
  log "клоную $REPO_URL -> $REPO_DIR"
  git clone "$REPO_URL" "$REPO_DIR" >"$LOG_DIR/clone.log" 2>&1 \
    || { tail -25 "$LOG_DIR/clone.log" >&2; die "клон не вдався"; }
fi

# 5a) право на push і власна ідентичність комітів -------------------------
# `credential.helper=store` без ~/.git-credentials мовчки чекав би пароль, а
# неінтерактивний push просто падає. `gh auth setup-git` ставить помічник, що
# бере токен зі сховища gh, — і тоді push воркера працює без людини. Власна
# ідентичність потрібна, бо на цій машині git її не має взагалі (перевірено:
# user.name порожній), а без неї commit відмовляє.
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh auth setup-git >/dev/null 2>&1 || log "УВАГА: gh auth setup-git не пройшов"
else
  log "УВАГА: gh не залогований — push і коментарі з воркера не працюватимуть"
fi
git -C "$REPO_DIR" config user.name "DSH swarm worker"
git -C "$REPO_DIR" config user.email "dsh-swarm@localhost"
log "git identity: $(git -C "$REPO_DIR" config user.name) <$(git -C "$REPO_DIR" config user.email)>"

# 6) ізоляція Gradle ------------------------------------------------------
cat >"$GRADLE_HOME/gradle.properties" <<'EOF'
# Керується scripts/swarm/bootstrap-worker.sh — не редагувати руками.
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.daemon.idletimeout=60000
# Воркер ділить 4 ядра й 19G з 17 контейнерами: два робітники й 3G heap.
org.gradle.workers.max=1
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
EOF

# local.properties — саме з кореня клону його переносить у смугу new-lane.sh.
printf 'sdk.dir=%s\n' "$SDK_DIR" >"$REPO_DIR/local.properties"

# 7) env-файл, який джерелить раннер -------------------------------------
cat >"$SWARM_ROOT/env.sh" <<EOF
# Керується scripts/swarm/bootstrap-worker.sh — не редагувати руками.
export SWARM_ROOT="$SWARM_ROOT"
export REPO_DIR="$REPO_DIR"
export JAVA_HOME="$JDK_DIR"
export PATH="$JDK_DIR/bin:$BIN_DIR:$NPM_PREFIX/bin:\$PATH"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export GRADLE_USER_HOME="$GRADLE_HOME"
export SLUKHAYKA_JAVA_HOME="$JDK_DIR"
EOF

log "env: $SWARM_ROOT/env.sh"

# 8) димовий прогін: ключ провайдера мусить працювати ДО диспетчу -----------
# Найдешевша перевірка, яка ловить найдорожчу відмову: без робочого ключа
# воркер спалить увесь 90-хвилинний lease й упаде на першому зверненні до
# моделі (у пілоті #1286 так і сталося — `401 api_key_expired` за 0 хвилин,
# але вже після резервування тікета).
smoke="$(env -i HOME="$HOME" PATH="$PATH" bash -lc \
  "source '$SWARM_ROOT/env.sh' && cd '$REPO_DIR' && DSH_PERMISSION_MODE=danger-full-access timeout 150 dsh --profile headless 'Reply with exactly: READY'" 2>&1 | tail -1 || true)"
case "$smoke" in
  *READY*) log "димовий прогін: ключ працює" ;;
  *) die "димовий прогін не дав READY (вивід: ${smoke:-порожній}) — перевір ~/.dsh/.credentials.yaml" ;;
esac

log "готово"
