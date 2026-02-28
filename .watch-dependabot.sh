#!/bin/bash
# ============================================================
# Surveillance Dependabot — gsbatch
# Vérifie toutes les 5 min les alertes open avec un patch
# disponible sur Maven Central, et applique le fix auto.
# ============================================================

REPO=/d/work/makalisio/gsbatch
CHECK_INTERVAL=300
LOG="$REPO/.watch-dependabot.log"
APPLIED_FILE="$REPO/.watch-dependabot-applied"

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }
info() { log "INFO  $*"; }
warn() { log "WARN  $*"; }
err()  { log "ERROR $*"; }

# ------------------------------------------------------------------
# Table : groupId:artifactId → propriété Spring Boot overridable
# Vide → fallback <dependencyManagement>
# ------------------------------------------------------------------
declare -A SB_PROP
SB_PROP["com.fasterxml.jackson.core:jackson-core"]="jackson-bom.version"
SB_PROP["com.fasterxml.jackson.core:jackson-databind"]="jackson-bom.version"
SB_PROP["com.fasterxml.jackson.core:jackson-annotations"]="jackson-bom.version"
SB_PROP["ch.qos.logback:logback-core"]="logback.version"
SB_PROP["ch.qos.logback:logback-classic"]="logback.version"
SB_PROP["org.springframework:spring-core"]="spring-framework.version"
SB_PROP["org.springframework:spring-web"]="spring-framework.version"
SB_PROP["org.springframework:spring-context"]="spring-framework.version"
SB_PROP["org.springframework.boot:spring-boot"]="spring-boot.version"
SB_PROP["org.yaml:snakeyaml"]="snakeyaml.version"
SB_PROP["net.minidev:json-smart"]=""

# ------------------------------------------------------------------
# Vérifie si une version existe sur Maven Central (HEAD request)
# ------------------------------------------------------------------
version_exists_on_central() {
  local groupId="$1" artifactId="$2" version="$3"
  local path
  path=$(echo "$groupId" | tr '.' '/')
  local url="https://repo1.maven.org/maven2/${path}/${artifactId}/${version}/${artifactId}-${version}.pom"
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$url")" = "200" ]
}

# ------------------------------------------------------------------
# Extrait un champ depuis le JSON de l'alerte via perl (robuste
# face aux objets imbriqués et aux valeurs multi-lignes)
# Usage : json_field <json> <field_name>
# ------------------------------------------------------------------
json_field() {
  echo "$1" | perl -0777 -ne 'if (/"'"$2"'"\s*:\s*"([^"]*)"/) { print $1; last }'
}

# ------------------------------------------------------------------
# Extrait le first_patched_version depuis le champ
# "security_vulnerability" (la vulnérabilité matchée pour ce projet).
# On délimite le bloc entre "security_vulnerability":{...} et le
# champ "url" qui suit, pour gérer les accolades imbriquées.
# ------------------------------------------------------------------
extract_patch_version() {
  local json="$1"
  echo "$json" | perl -0777 -ne '
    if (/"security_vulnerability"\s*:\s*\{(.+?)"url"\s*:\s*"https:\/\/api/s) {
      my $block = $1;
      if ($block =~ /"identifier"\s*:\s*"([^"]+)"/) { print $1 }
    }
  '
}

# ------------------------------------------------------------------
# Applique le fix dans pom.xml
# ------------------------------------------------------------------
apply_fix() {
  local artifact="$1" version="$2" ghsa="$3"
  local groupId="${artifact%%:*}"
  local artifactId="${artifact##*:}"
  local prop="${SB_PROP[$artifact]}"
  local pom="$REPO/pom.xml"

  if [ -n "$prop" ]; then
    # Stratégie 1 : propriété Spring Boot
    if grep -q "<${prop}>" "$pom"; then
      sed -i "s|<${prop}>[^<]*</${prop}>|<${prop}>${version}</${prop}>|" "$pom"
      info "  [$artifact] Propriété <${prop}> mise à jour → ${version}"
    else
      sed -i "s|</spring-framework.version>|</spring-framework.version>\n        <!-- ${ghsa}: ${artifact} (patch: ${version}) -->\n        <${prop}>${version}</${prop}>|" "$pom"
      info "  [$artifact] Propriété <${prop}>${version}</${prop}> insérée"
    fi
  else
    # Stratégie 2 : dependencyManagement
    if grep -q "<artifactId>${artifactId}</artifactId>" "$pom"; then
      perl -i -0pe \
        "s|(<groupId>${groupId}</groupId>\s*<artifactId>${artifactId}</artifactId>\s*<version>)[^<]*(</version>)|\${1}${version}\${2}|s" \
        "$pom"
      info "  [$artifact] <dependencyManagement> mis à jour → ${version}"
    else
      sed -i "s|</dependencyManagement>|        <!-- ${ghsa}: ${artifact} (patch: ${version}) -->\n            <dependency>\n                <groupId>${groupId}</groupId>\n                <artifactId>${artifactId}</artifactId>\n                <version>${version}</version>\n            </dependency>\n        </dependencies>\n    </dependencyManagement>|" "$pom"
      perl -i -0pe \
        's|</dependencies>\s*</dependencies>\s*</dependencyManagement>|</dependencies>\n    </dependencyManagement>|s' \
        "$pom"
      info "  [$artifact] Entrée <dependencyManagement> ajoutée pour ${version}"
    fi
  fi
}

# ------------------------------------------------------------------
# Boucle principale
# ------------------------------------------------------------------
info "=== Démarrage du watcher Dependabot unifié (intervalle: ${CHECK_INTERVAL}s) ==="
touch "$APPLIED_FILE"

while true; do
  info "--- Vérification des alertes Dependabot open ---"

  ALERTS=$(gh api "repos/makalisio/gsbatch/dependabot/alerts?state=open&per_page=100" 2>&1)
  if echo "$ALERTS" | grep -q '"message"'; then
    warn "Erreur API GitHub : $(echo "$ALERTS" | grep -o '"message":"[^"]*"')"
    sleep "$CHECK_INTERVAL"
    continue
  fi

  ALERT_NUMBERS=$(echo "$ALERTS" | grep -o '"number":[0-9]*' | grep -o '[0-9]*')
  ALERT_COUNT=$(echo "$ALERT_NUMBERS" | grep -c '[0-9]')
  info "Alertes open : ${ALERT_COUNT}"

  FIXED_ANY=false

  for NUMBER in $ALERT_NUMBERS; do
    ALERT_JSON=$(gh api "repos/makalisio/gsbatch/dependabot/alerts/${NUMBER}" 2>/dev/null)

    GHSA=$(json_field "$ALERT_JSON" "ghsa_id")
    SEVERITY=$(echo "$ALERT_JSON" | perl -0777 -ne \
      'if (/"security_advisory".*?"severity"\s*:\s*"([^"]+)"/s) { print $1; last }')
    ARTIFACT=$(echo "$ALERT_JSON" | perl -0777 -ne \
      'if (/"dependency".*?"name"\s*:\s*"([^"]+)"/s) { print $1; last }')

    # Patch extrait depuis security_vulnerability (vulnérabilité matchée)
    PATCH=$(extract_patch_version "$ALERT_JSON")

    [ -z "$GHSA" ] && continue

    if [ -z "$PATCH" ]; then
      info "Alert #${NUMBER} [$GHSA] ${SEVERITY} — ${ARTIFACT} : aucun patch disponible"
      continue
    fi

    # Déjà appliqué ?
    if grep -q "${GHSA}:${PATCH}" "$APPLIED_FILE" 2>/dev/null; then
      info "Alert #${NUMBER} [$GHSA] — déjà appliqué (${ARTIFACT} ${PATCH})"
      continue
    fi

    info "Alert #${NUMBER} [$GHSA] ${SEVERITY} — ${ARTIFACT} → patch ${PATCH}"

    GROUP="${ARTIFACT%%:*}"
    ARTID="${ARTIFACT##*:}"

    if version_exists_on_central "$GROUP" "$ARTID" "$PATCH"; then
      info "  Patch ${PATCH} disponible — application..."

      cd "$REPO" || { err "Répertoire introuvable"; continue; }
      git pull --rebase >> "$LOG" 2>&1 || { err "git pull a échoué"; continue; }

      cp pom.xml pom.xml.bak
      apply_fix "$ARTIFACT" "$PATCH" "$GHSA"

      info "  Build de vérification..."
      if mvn clean install -DskipTests -q >> "$LOG" 2>&1; then
        RESOLVED=$(mvn dependency:list -q 2>/dev/null \
          | grep "${ARTID}" | grep -o '[0-9][0-9]*\.[0-9][^:]*' | head -1)
        rm -f pom.xml.bak

        git add pom.xml
        git commit -m "fix: ${GHSA} — ${ARTIFACT} ${PATCH}

Alerte Dependabot #${NUMBER} (${SEVERITY})
Résolution effective : ${RESOLVED:-${PATCH}}

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>" >> "$LOG" 2>&1

        if git push >> "$LOG" 2>&1; then
          info "  Pousse avec succès (${ARTIFACT} ${RESOLVED:-${PATCH}})"
          echo "${GHSA}:${PATCH}" >> "$APPLIED_FILE"
          FIXED_ANY=true
        else
          err "  git push échoué — commit local conservé"
        fi
      else
        err "  Build échoué — rollback"
        cp pom.xml.bak pom.xml && rm -f pom.xml.bak
        git checkout pom.xml >> "$LOG" 2>&1
      fi
    else
      info "  Patch ${PATCH} non encore disponible sur Maven Central"
    fi
  done

  $FIXED_ANY || info "Aucun nouveau fix. Prochaine vérification dans $((CHECK_INTERVAL/60)) min."
  sleep "$CHECK_INTERVAL"
done
